package com.port.berth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.port.berth.domain.PowerAllocationMath;
import com.port.berth.domain.PowerAllocationMath.AccountSlot;
import com.port.berth.domain.PowerAllocationMath.Load;
import com.port.berth.domain.PowerAllocationMath.NaiveDerate;
import com.port.berth.domain.PowerAllocationMath.Op;
import com.port.berth.domain.PowerAllocationMath.Strategy;
import com.port.berth.domain.PowerAllocationMath.StrategyResult;
import com.port.berth.repo.PowerDao;
import com.port.berth.repo.PowerDao.AccountRow;
import com.port.berth.repo.PowerDao.AlarmRow;
import com.port.berth.repo.PowerDao.CommitmentRow;
import com.port.berth.repo.PowerDao.FeederRow;
import com.port.berth.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * feature-4 馈线降容供电调度：
 * 录入告警 → 同时给出“按确认时间”与“按货物安全”两种顺序的分时方案对照
 * （分配表 / 逐时段馈线总量核验 / 受影响货物 / 削减作业与补偿依据 / 恢复次序 /
 * “逐船独立降额”反例），下达不可变回执；告警撤销时只生成 FULL_RESTORE 新方案与新回执，
 * 绝不修改旧方案与旧回执。
 */
@Service
public class PowerService {

    /** 固定样例时间：2026-11-26 晚高峰。 */
    public static final LocalDateTime D = SeedService.D;

    private final PowerDao dao;
    private final ObjectMapper json;

    public PowerService(PowerDao dao, ObjectMapper json) {
        this.dao = dao;
        this.json = json;
    }

    public record AlarmInput(Double deratedCapacityMw, String reason,
                             LocalDateTime alarmAt, LocalDateTime windowStart,
                             LocalDateTime windowEnd, Integer slotMinutes) {}

    // ====================================================================
    // 播种
    // ====================================================================

    public void seedIfEmpty() {
        if (dao.feederDataExists()) return;
        seedFixedScenario();
    }

    /** 固定数值故事：馈线正常 2.0MW，临时降到 1.2MW；三船需量 0.6/0.5/0.4；冷藏船最后确认。 */
    public void seedFixedScenario() {
        long feeder = dao.insertFeeder("FD-01", "一号岸电馈线（6.6kV 临港线）",
                2000, 2000, "NORMAL");

        LocalDateTime c1 = D.withHour(18).withMinute(5);
        LocalDateTime c2 = D.withHour(18).withMinute(25);
        LocalDateTime c3 = D.withHour(18).withMinute(40);

        long a1 = dao.insertAccount(new PowerDao.AccountSeed(feeder, "PA-01", "B2",
                "SHIP-P1", "沪航普运 18", "GENERAL",
                "普通集装箱 + 件杂货（无温控强制负荷）", 0,
                600, 0, false, false, c1, 1, 200,
                "普通船：可错峰安排起货机，短时降负荷不产生货损，仅产生作业顺延与补偿"));
        dao.insertCommitment(a1, "PC-0101", 1, "停泊基础负荷（冷却泵/风机/照明）",
                300, 300, false, c1);
        dao.insertCommitment(a1, "PC-0102", 2, "1 号克令吊起货作业（可错峰/暂停）",
                300, 0, true, c1);

        long a2 = dao.insertAccount(new PowerDao.AccountSeed(feeder, "PA-02", "B3",
                "SHIP-P2", "浙海杂货 9", "GENERAL",
                "件杂货 + 少量钢材（无温控强制负荷）", 0,
                500, 0, false, false, c2, 2, 200,
                "普通船：船吊作业可与 1 号船错峰，降负荷只影响装卸进度"));
        dao.insertCommitment(a2, "PC-0201", 1, "停泊基础负荷（机舱风机/泵/生活用电）",
                200, 200, false, c2);
        dao.insertCommitment(a2, "PC-0202", 2, "2 号船吊/起货机作业（可错峰/暂停）",
                300, 0, true, c2);

        dao.insertAccount(new PowerDao.AccountSeed(feeder, "PA-03", "B1",
                "SHIP-RF3", "远洋冷王（冷藏船）", "REEFER",
                "冷藏集装箱 40 FEU（冷冻肉 / 生鲜），需连续温控", 40,
                400, 400, true, true, c3, 3, 0,
                "确认时间最晚但货物安全负荷最高：供电低于 0.4MW 将导致冷机停机、箱温回升，"
                        + "超过耐受时间必须把冷藏箱转移到岸上插座/堆场，产生翻箱、拖车、"
                        + "插电位与货损风险"));
        long a3 = dao.listAccounts(feeder).stream()
                .filter(a -> "PA-03".equals(a.code())).findFirst().orElseThrow().id();
        dao.insertCommitment(a3, "PC-0301", 1, "冷藏箱压缩/制冷机组（连续温控，不可中断）",
                320, 320, false, c3);
        dao.insertCommitment(a3, "PC-0302", 2, "冷藏箱融霜/通风/监控（连续）",
                80, 80, false, c3);
    }

    // ====================================================================
    // 总览
    // ====================================================================

    public Map<String, Object> overview() {
        seedIfEmpty();
        FeederRow feeder = dao.defaultFeeder();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("feeder", camel(feeder));
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (AccountRow a : dao.listAccounts(feeder.id())) {
            Map<String, Object> am = camel(a);
            am.put("commitments", dao.listCommitments(a.id()).stream().map(this::camel).toList());
            accounts.add(am);
        }
        m.put("accounts", accounts);

        AlarmRow active = dao.findActiveAlarm(feeder.id());
        m.put("activeAlarm", active == null ? null : alarmDetail(active.id()));
        m.put("alarmHistory", dao.listAlarms().stream().map(a -> {
            Map<String, Object> x = camel(a);
            x.remove("reason");
            return x;
        }).toList());
        m.put("receipts", listReceipts());

        // 页面首次进入、尚未录入告警时也能预览“逐船独立降额陷阱”
        List<Load> loads = loads(feeder.id());
        m.put("naiveDeratingTrapPreview",
                PowerAllocationMath.naiveIndependentDerating(loads, 500, 1200));
        return m;
    }

    // ====================================================================
    // 录入告警 → 两种顺序方案
    // ====================================================================

    @Transactional
    public Map<String, Object> raiseAlarm(AlarmInput in, String operator) {
        seedIfEmpty();
        FeederRow feeder = dao.defaultFeeder();
        if (feeder == null) throw ApiException.notFound("缺少馈线基础数据");
        AlarmRow active = dao.findActiveAlarm(feeder.id());
        if (active != null) {
            throw ApiException.conflict("POWER_ALARM_ACTIVE",
                    "馈线已有生效中的告警 " + active.alarmNo()
                            + "；请先撤销该告警（会生成恢复新方案，旧回执保留），或重置样例");
        }

        int deratedKw = in != null && in.deratedCapacityMw() != null
                ? (int) Math.round(in.deratedCapacityMw() * 1000) : 1200;
        if (deratedKw <= 0 || deratedKw > feeder.normalCapacityKw()) {
            throw ApiException.badRequest("BAD_DERATED_CAPACITY",
                    "降容后上限必须在 0 与正常容量 "
                            + PowerAllocationMath.mw3(feeder.normalCapacityKw()) + "MW 之间");
        }
        LocalDateTime alarmAt = in != null && in.alarmAt() != null
                ? in.alarmAt() : D.withHour(19).withMinute(50);
        LocalDateTime start = in != null && in.windowStart() != null
                ? in.windowStart() : D.withHour(20).withMinute(0);
        LocalDateTime end = in != null && in.windowEnd() != null
                ? in.windowEnd() : D.withHour(22).withMinute(0);
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("BAD_WINDOW", "供电窗口结束时间必须晚于开始时间");
        }
        int slotMinutes = in != null && in.slotMinutes() != null && in.slotMinutes() > 0
                ? in.slotMinutes() : 30;
        long mins = Duration.between(start, end).toMinutes();
        if (mins % slotMinutes != 0) {
            throw ApiException.badRequest("BAD_SLOT",
                    "窗口时长 " + mins + " 分钟必须能被时段长度 " + slotMinutes + " 整除");
        }
        int slotCount = (int) (mins / slotMinutes);
        String reason = in != null && in.reason() != null && !in.reason().isBlank()
                ? in.reason() : "临港馈线电缆/上级开关临时故障，继电保护限流，调度令临时降容运行";

        long alarmId = dao.insertAlarm(feeder.id(), feeder.safetyCapacityKw(), deratedKw,
                reason, alarmAt, start, end, slotMinutes, operator);
        dao.updateFeederCapacity(feeder.id(), deratedKw, "DERATED");

        List<Load> loads = loads(feeder.id());
        NaiveDerate trap = PowerAllocationMath.naiveIndependentDerating(loads, 500, deratedKw);
        String trapJson = writeJson(trap);

        // 顺序一：按确认时间
        StrategyResult byConfirm = PowerAllocationMath.allocate(
                Strategy.CONFIRMATION_ORDER, loads, deratedKw,
                feeder.normalCapacityKw(), slotCount, slotMinutes);
        persistPlan(alarmId, feeder.id(), "CONF", Strategy.CONFIRMATION_ORDER,
                "EMERGENCY", deratedKw, start, slotMinutes, byConfirm, loads, trapJson,
                "顺序一·按确认时间：严格按供电承诺确认先后分配，先确认的 0.6/0.5MW 普通船"
                        + "全额保住，最后确认的冷藏船只拿到馈线剩余 0.1MW（低于 0.4MW 安全下限）。"
                        + "它不违反馈线总量上限，却违反货物安全负荷，需转移冷藏箱并承担货损风险。"
                        + "用途：合同/承诺优先、界定既有供电承诺责任。");

        // 顺序二：按货物安全
        StrategyResult bySafety = PowerAllocationMath.allocate(
                Strategy.CARGO_SAFETY, loads, deratedKw,
                feeder.normalCapacityKw(), slotCount, slotMinutes);
        long safetyPlanId = persistPlan(alarmId, feeder.id(), "SAFE", Strategy.CARGO_SAFETY,
                "EMERGENCY", deratedKw, start, slotMinutes, bySafety, loads, trapJson,
                "顺序二·按货物最低安全负荷：先锁定冷藏船连续 0.4MW 安全负荷，剩余 0.8MW 由"
                        + "两艘普通船错峰共享——同一时段只让一艘船运行 0.3MW 起货机，另一艘只保留"
                        + "基础负荷，下一时段轮换。馈线逐时段合计始终 =1.2MW，不超安全上限；"
                        + "代价是改动普通船已确认作业计划，并按削减电量×补偿标准出具补偿依据。"
                        + "用途：货运安全/防货损优先。");

        // 值班电工默认下达并回执推荐的安全方案
        issueReceipt(safetyPlanId, "告警录入后自动下达推荐的货物安全方案", operator);

        return alarmDetail(alarmId);
    }

    // ====================================================================
    // 撤销告警 → FULL_RESTORE 新方案 + 新回执（旧数据不动）
    // ====================================================================

    @Transactional
    public Map<String, Object> revoke(long alarmId, String reason, String operator) {
        AlarmRow alarm = dao.findAlarm(alarmId);
        if (alarm == null) throw ApiException.notFound("告警 #" + alarmId + " 不存在");
        if ("REVOKED".equals(alarm.status())) {
            throw ApiException.conflict("POWER_ALARM_ALREADY_REVOKED",
                    "告警 " + alarm.alarmNo() + " 已撤销；旧方案/回执保持不变，不能重复撤销。"
                            + "如需再次演练请重新录入告警或重置样例。");
        }
        FeederRow feeder = dao.findFeeder(alarm.feederId());
        LocalDateTime revokedAt = alarm.windowEnd();
        String revokeReason = (reason != null && !reason.isBlank())
                ? reason : "故障隔离/检修完成，馈线恢复正常额定容量供电";

        dao.revokeAlarm(alarmId, revokeReason, revokedAt, feeder.normalCapacityKw());
        dao.updateFeederCapacity(feeder.id(), feeder.normalCapacityKw(), "NORMAL");

        List<Load> loads = loads(feeder.id());
        StrategyResult restored = PowerAllocationMath.allocate(
                Strategy.FULL_RESTORE, loads, alarm.deratedCapacityKw(),
                feeder.normalCapacityKw(), 1, 60);
        long restorePlanId = persistPlan(alarmId, feeder.id(), "RESTORE",
                Strategy.FULL_RESTORE, "RESTORED", feeder.normalCapacityKw(),
                revokedAt, 60, restored, loads, null,
                "告警撤销后生成的【新方案】：馈线恢复 "
                        + PowerAllocationMath.mw3(feeder.normalCapacityKw())
                        + "MW 正常容量，三船均恢复全额需量 0.6/0.5/0.4MW，合计 1.5MW。"
                        + "该方案是新记录；告警期间的两种方案与已签发回执均为历史凭证，不做任何修改。");
        issueReceipt(restorePlanId, "告警撤销，恢复正常供电的新回执", operator);

        return alarmDetail(alarmId);
    }

    // ====================================================================
    // 回执
    // ====================================================================

    @Transactional
    public Map<String, Object> issueReceipt(long planId, String note, String operator) {
        Map<String, Object> plan = dao.findPlanRow(planId);
        if (plan == null) throw ApiException.notFound("供电方案 #" + planId + " 不存在");
        dao.markPlanIssued(planId);

        String receiptNo = dao.nextReceiptNo();
        Object alarmIdObj = plan.get("alarm_id");
        Long alarmId = alarmIdObj == null ? null : ((Number) alarmIdObj).longValue();
        long feederId = ((Number) plan.get("feeder_id")).longValue();
        FeederRow feeder = dao.findFeeder(feederId);

        Map<String, Object> snapshot = planDetail(planId);
        String snapshotJson = writeJson(snapshot);
        String strategy = String.valueOf(plan.get("strategy"));
        String phase = String.valueOf(plan.get("phase"));
        int limitKw = ((Number) plan.get("capacity_limit_kw")).intValue();

        long id = dao.insertReceipt(receiptNo, planId, alarmId,
                feeder == null ? null : feeder.code(), strategy, phase, limitKw,
                operator, note, snapshotJson);
        return receiptDetail(id);
    }

    public List<Map<String, Object>> listReceipts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : dao.listReceiptRows()) {
            out.add(receiptFromRow(row, false));
        }
        return out;
    }

    public Map<String, Object> receiptDetail(long receiptId) {
        Map<String, Object> row = dao.findReceiptRow(receiptId);
        if (row == null) throw ApiException.notFound("回执 #" + receiptId + " 不存在");
        return receiptFromRow(row, true);
    }

    private Map<String, Object> receiptFromRow(Map<String, Object> row, boolean withSnapshot) {
        Map<String, Object> m = camel(row);
        Object snap = row.get("snapshot_json");
        if (snap != null) {
            try {
                m.put("snapshot", json.readValue(String.valueOf(snap), Object.class));
            } catch (Exception e) {
                m.put("snapshotParseError", e.getMessage());
            }
        }
        if (!withSnapshot) m.remove("snapshot");
        m.remove("snapshotJson");
        return m;
    }

    // ====================================================================
    // 告警/方案明细
    // ====================================================================

    public List<Map<String, Object>> listAlarms() {
        seedIfEmpty();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AlarmRow a : dao.listAlarms()) out.add(camel(a));
        return out;
    }

    public Map<String, Object> alarmDetail(long alarmId) {
        AlarmRow alarm = dao.findAlarm(alarmId);
        if (alarm == null) throw ApiException.notFound("告警 #" + alarmId + " 不存在");
        FeederRow feeder = dao.findFeeder(alarm.feederId());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alarm", camel(alarm));
        m.put("feeder", camel(feeder));

        List<Map<String, Object>> accounts = new ArrayList<>();
        for (AccountRow a : dao.listAccounts(alarm.feederId())) {
            Map<String, Object> am = camel(a);
            am.put("commitments", dao.listCommitments(a.id()).stream().map(this::camel).toList());
            accounts.add(am);
        }
        m.put("accounts", accounts);

        List<Map<String, Object>> plans = new ArrayList<>();
        for (Map<String, Object> p : dao.listPlansByAlarm(alarmId)) {
            Object idObj = p.get("id");
            long pid = ((Number) idObj).longValue();
            plans.add(planDetail(pid));
        }
        m.put("plans", plans);

        List<Map<String, Object>> receipts = listReceipts().stream()
                .filter(r -> {
                    Object aid = r.get("alarmId");
                    return aid instanceof Number n && alarmId == n.longValue();
                })
                .toList();
        m.put("receipts", receipts);
        return m;
    }

    public Map<String, Object> planDetail(long planId) {
        Map<String, Object> row = dao.findPlanRow(planId);
        if (row == null) throw ApiException.notFound("供电方案 #" + planId + " 不存在");
        Map<String, Object> m = camel(row);

        parseJsonField(m, "naiveTrapJson", "naiveTrap");
        parseJsonField(m, "slotChecksJson", "slotChecks");
        parseJsonField(m, "restorationJson", "restoration");
        parseJsonField(m, "warningsJson", "warnings");
        m.remove("naiveTrapJson");
        m.remove("slotChecksJson");
        m.remove("restorationJson");
        m.remove("warningsJson");

        List<Map<String, Object>> allocations = new ArrayList<>();
        for (Map<String, Object> a : dao.listAllocations(planId)) allocations.add(camel(a));
        m.put("allocations", allocations);

        List<Map<String, Object>> curtailments = new ArrayList<>();
        double energyTotal = 0, compensationTotal = 0;
        for (Map<String, Object> c : dao.listCurtailments(planId)) {
            Map<String, Object> cm = camel(c);
            curtailments.add(cm);
            Object e = cm.get("curtailedEnergyMwh");
            Object amt = cm.get("compensationAmountCny");
            if (e instanceof Number n) energyTotal += n.doubleValue();
            if (amt instanceof Number n) compensationTotal += n.doubleValue();
        }
        m.put("curtailments", curtailments);
        m.put("curtailedEnergyMwhTotal", PowerAllocationMath.round3(energyTotal));
        m.put("compensationTotalCny", PowerAllocationMath.round2(compensationTotal));

        List<Map<String, Object>> impacts = new ArrayList<>();
        for (Map<String, Object> i : dao.listCargoImpacts(planId)) impacts.add(camel(i));
        m.put("cargoImpacts", impacts);
        return m;
    }

    private void parseJsonField(Map<String, Object> m, String from, String to) {
        Object raw = m.get(from);
        if (raw == null) { m.put(to, null); return; }
        try {
            m.put(to, json.readValue(String.valueOf(raw), Object.class));
        } catch (Exception e) {
            m.put(to, null);
        }
    }

    // ====================================================================
    // 内部：组装数学输入 / 持久化方案
    // ====================================================================

    private List<Load> loads(long feederId) {
        List<Load> loads = new ArrayList<>();
        for (AccountRow a : dao.listAccounts(feederId)) {
            List<Op> ops = new ArrayList<>();
            for (CommitmentRow c : dao.listCommitments(a.id())) {
                ops.add(new Op(c.id(), c.commitNo(), c.operationName(), c.requiredKw(),
                        c.minKw(), c.flexible()));
            }
            loads.add(new Load(a.id(), a.code(), a.shipName(), a.demandedKw(),
                    a.safetyFloorKw(), a.continuous(), a.confirmSeq(),
                    a.compensationRatePerMwh(), ops));
        }
        return loads;
    }

    private long persistPlan(long alarmId, long feederId, String noTag, Strategy strategy,
                             String phase, int limitKw, LocalDateTime windowStart,
                             int slotMinutes, StrategyResult r, List<Load> loads,
                             String trapJson, String comparisonNote) {
        int slotCount = r.slotCount();
        int totalDemand = loads.stream().mapToInt(Load::demandedKw).sum();
        int peak = r.slotChecks().stream().mapToInt(
                PowerAllocationMath.SlotCheck::totalAllocatedKw).max().orElse(0);
        String planNo = "PP-A" + alarmId + "-" + noTag;

        long planId = dao.insertPlan(planNo, alarmId, feederId, strategy.name(), phase,
                limitKw, slotMinutes, slotCount, r.feederTotalCheckPass(),
                r.safetyFeasible(), r.feederTotalCheckPass(), totalDemand, peak,
                trapJson, writeJson(r.slotChecks()), writeJson(r.restoration()),
                writeJson(r.warnings()), comparisonNote);

        Map<Long, AccountRow> accountById = new LinkedHashMap<>();
        for (AccountRow a : dao.listAccounts(feederId)) accountById.put(a.id(), a);

        for (AccountSlot as : r.accountSlots()) {
            LocalDateTime s = windowStart.plusMinutes((long) as.slot() * slotMinutes);
            LocalDateTime e = s.plusMinutes(slotMinutes);
            dao.insertAllocation(planId, as.accountId(), as.slot(), s, e,
                    as.demandedKw(), as.allocatedKw(), as.curtailedKw(), as.safetyFloorKw(),
                    as.safetyViolation(), as.basis());
        }

        for (PowerAllocationMath.Curtailment c : r.curtailments()) {
            AccountRow a = accountById.get(c.accountId());
            LocalDateTime s = windowStart.plusMinutes((long) c.slot() * slotMinutes);
            LocalDateTime e = s.plusMinutes(slotMinutes);
            boolean unsafe = "BELOW_SAFETY_FLOOR".equals(c.basisCode());
            String reason;
            int rate = c.compensationRatePerMwh();
            double amount = c.compensationAmountCny();
            if (unsafe) {
                reason = "【安全下限失守】" + a.shipName() + " 在该时段仅分得 "
                        + PowerAllocationMath.mw3(c.allocatedKw()) + "MW，低于货物安全负荷 "
                        + PowerAllocationMath.mw3(a.safetyFloorKw())
                        + "MW；这不是可结算的自愿错峰，而是必须避免的失电，将触发冷藏箱转移与货损责任";
                rate = 0;
                amount = 0d;
            } else {
                reason = "馈线降容至 " + PowerAllocationMath.mw3(limitKw)
                        + "MW，按货物安全方案错峰/暂停已确认作业「" + c.operationName()
                        + "」，削减 " + c.curtailedKw() + "kW × " + c.durationMin()
                        + " 分钟 = " + c.curtailedEnergyMwh() + "MWh；"
                        + "据此按 " + rate + " 元/MWh 核算补偿 " + amount + " 元，作为改动普通船计划的补偿依据";
            }
            dao.insertCurtailment(new PowerDao.CurtailmentSeed(planId, c.accountId(),
                    c.commitmentId(), c.commitmentNo(), c.slot(), s, e, c.operationName(),
                    c.requestedKw(), c.allocatedKw(), c.curtailedKw(), c.durationMin(),
                    c.curtailedEnergyMwh(), rate, PowerAllocationMath.round2(amount),
                    unsafe ? "UNSAFE_SAFETY_FLOOR_BREACH" : c.basisCode(), reason));
        }

        // 受影响货物评估（按全窗口最低分配）
        for (AccountRow a : accountById.values()) {
            List<AccountSlot> mine = r.accountSlots().stream()
                    .filter(x -> x.accountId() == a.id()).toList();
            int minAlloc = mine.stream().mapToInt(AccountSlot::allocatedKw).min().orElse(0);
            int deficit = Math.max(0, a.demandedKw() - minAlloc);
            boolean floorBreach = minAlloc < a.safetyFloorKw();
            String risk;
            boolean transfer = false;
            String msg;
            if (floorBreach) {
                risk = "CRITICAL";
                transfer = a.transferIfBelow();
                msg = a.shipName() + "（" + a.cargoDetail() + "）最低仅得 "
                        + PowerAllocationMath.mw3(minAlloc) + "MW < 安全下限 "
                        + PowerAllocationMath.mw3(a.safetyFloorKw()) + "MW，缺口 "
                        + PowerAllocationMath.mw3(a.safetyFloorKw() - minAlloc)
                        + "MW；" + (transfer ? "必须转移 " + a.reeferBoxCount()
                        + " 个冷藏箱至岸上插电位，否则有货损风险。" : "关键负荷失电。")
                        + a.cargoRiskNote();
            } else if (deficit > 0) {
                risk = "WARNING";
                msg = a.shipName() + " 冷藏/安全负荷未受影响，但普通用电计划被错峰削减，"
                        + "最低分配 " + PowerAllocationMath.mw3(minAlloc) + "MW，需顺延作业并按削减电量补偿";
            } else {
                risk = "OK";
                msg = a.shipName() + " 全窗口足额供电，货物与已确认作业不受影响";
            }
            dao.insertCargoImpact(planId, a.id(), a.cargoClass(), a.cargoDetail(),
                    a.reeferBoxCount(), a.safetyFloorKw(), minAlloc, deficit, risk,
                    transfer, msg);
        }
        return planId;
    }

    // ====================================================================
    // 工具
    // ====================================================================

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new ApiException(500, "JSON_ERROR", "方案序列化失败：" + e.getMessage());
        }
    }

    private Map<String, Object> camel(Object record) {
        // record / 普通对象先交给 Jackson 转 Map（record 组件天然 camelCase）
        return json.convertValue(record,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /**把 queryForList 的 snake_case Map 转成 camelCase，并把时间值统一为 ISO 字符串。*/
    private Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(snakeToCamel(e.getKey()), normalizeValue(e.getValue()));
        }
        return out;
    }

    private Object normalizeValue(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.util.Date d) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(d);
        }
        if (v instanceof java.time.LocalDateTime lt) return lt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        if (v instanceof java.time.LocalDate ld) return ld.toString();
        return v;
    }

    private static String snakeToCamel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : s.toCharArray()) {
            if (ch == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return sb.toString();
    }
}
