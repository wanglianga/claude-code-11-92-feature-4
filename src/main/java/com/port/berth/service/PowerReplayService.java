package com.port.berth.service;

import com.port.berth.repo.PowerDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * feature-4 供电调度一键回放/自检：
 * 精确核对 1.2MW 降容、0.6/0.5/0.4 需量下两种顺序的数值结果、
 * 逐时段馈线总量核验、受影响货物、补偿依据、恢复次序，以及“撤销只发新方案、
 * 旧回执不变”和数据库层回执不可变。
 */
@Service
public class PowerReplayService {

    private final SeedService seedService;
    private final PowerService powerService;
    private final PowerDao powerDao;

    public PowerReplayService(SeedService seedService, PowerService powerService,
                              PowerDao powerDao) {
        this.seedService = seedService;
        this.powerService = powerService;
        this.powerDao = powerDao;
    }

    public Map<String, Object> runPower() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "⑥ 馈线降容 1.2MW：两种供电顺序 / 总量核验 / 补偿 / 撤销与不可变回执");

        // 1) 基础样例数值 -------------------------------------------------
        Map<String, Object> ov = powerService.overview();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) ov.get("accounts");
        check(checks, "三艘在泊船需量恰为 0.6/0.5/0.4MW",
                demandedByCode(accounts, "PA-01") == 600
                        && demandedByCode(accounts, "PA-02") == 500
                        && demandedByCode(accounts, "PA-03") == 400,
                "PA-01=600,PA-02=500,PA-03=400",
                demandedByCode(accounts, "PA-01") + "/" + demandedByCode(accounts, "PA-02")
                        + "/" + demandedByCode(accounts, "PA-03"));
        long pa03 = idByCode(accounts, "PA-03");

        // 2) 录入告警（默认 1.2MW，20:00-22:00，30 分钟 4 档） -------------
        Map<String, Object> alarm = powerService.raiseAlarm(
                new PowerService.AlarmInput(1.2d, "回放：馈线差动保护动作，临时降容",
                        null, null, null, 30), "回放调度");
        @SuppressWarnings("unchecked")
        Map<String, Object> alarmHeader = (Map<String, Object>) alarm.get("alarm");
        long alarmId = ((Number) alarmHeader.get("id")).longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) alarm.get("plans");
        Map<String, Object> confirm = planByStrategy(plans, "CONFIRMATION_ORDER");
        Map<String, Object> safety = planByStrategy(plans, "CARGO_SAFETY");

        check(checks, "告警后生成且仅生成两种应急顺序方案",
                confirm != null && safety != null && plans.size() == 2,
                List.of("CONFIRMATION_ORDER", "CARGO_SAFETY"),
                plans.stream().map(p -> p.get("strategy")).toList());

        // 3) 顺序一：按确认时间 → 0.6 + 0.5 保全，冷藏仅 0.1 --------------
        Map<Long, List<Map<String, Object>>> confAlloc = allocByAccount(confirm);
        boolean confReefer010 = confAlloc.get(pa03).stream()
                .allMatch(a -> intV(a, "allocatedKw") == 100);
        check(checks, "按确认时间：最晚确认的冷藏船只拿到剩余 0.1MW（100kW）",
                confReefer010, "冷藏每时段 allocatedKw=100",
                confAlloc.get(pa03).stream().map(a -> a.get("allocatedKw")).toList());

        long pa01 = idByCode(accounts, "PA-01");
        long pa02 = idByCode(accounts, "PA-02");
        boolean ordinaryFull = confAlloc.get(pa01).stream().allMatch(a -> intV(a, "allocatedKw") == 600)
                && confAlloc.get(pa02).stream().allMatch(a -> intV(a, "allocatedKw") == 500);
        check(checks, "按确认时间：先确认的两艘普通船 0.6/0.5MW 全额保住",
                ordinaryFull, "PA-01=600 且 PA-02=500 每时段", "见分配表");

        check(checks, "按确认时间：馈线逐时段总量仍 ≤1.2MW（总量核验通过）",
                Boolean.TRUE.equals(confirm.get("feederTotalCheckPass"))
                        && allSlotTotals(confirm, 1200),
                "every slot total<=1200", slotTotals(confirm));
        check(checks, "按确认时间：货物安全不可行（冷藏 0.1<0.4）",
                Boolean.FALSE.equals(confirm.get("safetyFeasible")),
                false, confirm.get("safetyFeasible"));

        Map<String, Object> confReeferImpact = impactOfPlan(confirm, pa03);
        check(checks, "按确认时间：冷藏货物影响为 CRITICAL 且要求转移冷藏箱",
                confReeferImpact != null
                        && "CRITICAL".equals(confReeferImpact.get("riskLevel"))
                        && Boolean.TRUE.equals(confReeferImpact.get("transferRequired")),
                "CRITICAL + transferRequired=true",
                confReeferImpact == null ? null
                        : Map.of("riskLevel", confReeferImpact.get("riskLevel"),
                        "transferRequired", confReeferImpact.get("transferRequired")));

        // 4) 顺序二：货物安全 → 冷藏 0.4 全程，普通船错峰，总量 1.2 --------
        Map<Long, List<Map<String, Object>>> safeAlloc = allocByAccount(safety);
        boolean reeferSafe = safeAlloc.get(pa03).stream()
                .allMatch(a -> intV(a, "allocatedKw") >= 400
                        && !Boolean.TRUE.equals(a.get("safetyViolation")));
        check(checks, "按货物安全：冷藏船每时段连续保有至少 0.4MW",
                reeferSafe && Boolean.TRUE.equals(safety.get("safetyFeasible")),
                "all slots >=400 且 safetyFeasible=true",
                safeAlloc.get(pa03).stream().map(a -> a.get("allocatedKw")).toList());

        check(checks, "按货物安全：馈线逐时段合计恒为 1.2MW，绝不超安全上限",
                Boolean.TRUE.equals(safety.get("feederTotalCheckPass"))
                        && slotTotals(safety).stream().allMatch(t -> t == 1200),
                "every slot total=1200", slotTotals(safety));

        // 精确错峰表：slot0/2 = (600,200)，slot1/3 = (300,500)，冷藏 400
        boolean alternating = true;
        for (Map<String, Object> a : (List<Map<String, Object>>) safety.get("allocations")) {
            int slot = intV(a, "slotIndex");
            long acc = longV(a, "accountId");
            int alloc = intV(a, "allocatedKw");
            if (acc == pa03) { if (alloc != 400) alternating = false; }
            else if (slot % 2 == 0) {
                if (acc == pa01 && alloc != 600) alternating = false;
                if (acc == pa02 && alloc != 200) alternating = false;
            } else {
                if (acc == pa01 && alloc != 300) alternating = false;
                if (acc == pa02 && alloc != 500) alternating = false;
            }
        }
        check(checks, "按货物安全：两艘普通船按半小时档错峰（600/200 与 300/500 轮换）",
                alternating, "even:(PA01=600,PA02=200), odd:(300,500), RF=400",
                "见 safety.allocations");

        // 5) 被削减的已确认作业 + 补偿依据 -------------------------------
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cuts = (List<Map<String, Object>>) safety.get("curtailments");
        boolean attributed = cuts.stream().anyMatch(c -> "PC-0102".equals(c.get("commitmentNo")))
                && cuts.stream().anyMatch(c -> "PC-0202".equals(c.get("commitmentNo")));
        check(checks, "削减可归因到普通船具体已确认作业（1号克令吊 / 2号船吊）",
                attributed && cuts.size() == 4, "PC-0102 与 PC-0202 各 2 档，共 4 条",
                cuts.stream().map(c -> c.get("commitmentNo") + "@slot" + c.get("slotIndex")).toList());

        double energy = dblV(safety, "curtailedEnergyMwhTotal");
        double comp = dblV(safety, "compensationTotalCny");
        check(checks, "补偿依据：总削减电量 0.600MWh、按 200元/MWh 合计补偿 120 元",
                Math.abs(energy - 0.6d) < 1e-9 && Math.abs(comp - 120d) < 1e-9,
                "energy=0.6MWh, compensation=120CNY",
                Map.of("energyMwh", energy, "compensationCny", comp));

        // 6) 逐船独立降额陷阱 + 恢复次序 ---------------------------------
        Object trapObj = safety.get("naiveTrap");
        Map<?, ?> trap = trapObj instanceof Map<?, ?> tm ? tm : Map.of();
        check(checks, "反例：逐船独立 cap 0.5MW 得 500/500/400，合计 1.4MW 仍超 1.2MW",
                Boolean.FALSE.equals(trap.get("feederTotalPass"))
                        && Integer.valueOf(1400).equals(trap.get("totalKw")),
                "totalKw=1400, feederTotalPass=false",
                Map.of("totalKw", trap.get("totalKw"), "pass", trap.get("feederTotalPass")));

        List<Map<String, Object>> safeRestore = restoration(safety);
        check(checks, "安全顺序恢复次序：冷藏船第一步（CARGO_SAFETY_FIRST）",
                !safeRestore.isEmpty() && longV(safeRestore.get(0), "accountId") == pa03
                        && "CARGO_SAFETY_FIRST".equals(safeRestore.get(0).get("priority")),
                "step1=PA-03 REEFER",
                safeRestore.stream().map(r -> r.get("shipName")).toList());
        List<Map<String, Object>> confRestore = restoration(confirm);
        check(checks, "确认顺序恢复次序：冷藏船被排在最后，且累计 1500kW 超降容上限",
                !confRestore.isEmpty()
                        && longV(confRestore.get(confRestore.size() - 1), "accountId") == pa03
                        && Boolean.FALSE.equals(
                        confRestore.get(confRestore.size() - 1).get("withinAlarmLimit")),
                "last=PA-03 and withinAlarmLimit=false@1500",
                confRestore.stream().map(r -> Map.of("ship", r.get("shipName"),
                        "cumulativeKw", r.get("cumulativeKw"),
                        "withinAlarmLimit", r.get("withinAlarmLimit"))).toList());

        // 7) 撤销前快照旧回执原始行 --------------------------------------
        Map<Long, Map<String, Object>> receiptsBefore = rawReceipts();
        check(checks, "告警下达后已自动签发推荐安全方案的不可变回执",
                receiptsBefore.size() >= 1, ">=1 receipt", receiptsBefore.size());

        // 8) 撤销 → 新恢复方案 + 新回执，旧回执原样 ------------------------
        powerService.revoke(alarmId, "回放：抢修结束，撤销降容", "回放调度");
        Map<String, Object> alarmAfter = powerService.alarmDetail(alarmId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plansAfter = (List<Map<String, Object>>) alarmAfter.get("plans");
        Map<String, Object> restore = planByStrategy(plansAfter, "FULL_RESTORE");
        check(checks, "告警撤销后生成独立的 FULL_RESTORE 新方案",
                restore != null && "RESTORED".equals(restore.get("phase")),
                "FULL_RESTORE/RESTORED",
                plansAfter.stream().map(p -> p.get("strategy") + "/" + p.get("phase")).toList());

        boolean allFull = restore == null ? false
                : ((List<Map<String, Object>>) restore.get("allocations")).stream()
                .allMatch(a -> intV(a, "allocatedKw") == intV(a, "demandedKw"));
        check(checks, "恢复方案三船全额 0.6/0.5/0.4，合计 1.5MW ≤ 正常 2.0MW",
                restore != null && allFull
                        && Boolean.TRUE.equals(restore.get("feederTotalCheckPass"))
                        && slotTotals(restore).stream().allMatch(t -> t == 1500),
                "each full; total=1500", restore == null ? null : slotTotals(restore));

        Map<Long, Map<String, Object>> receiptsAfter = rawReceipts();
        boolean oldUntouched = true;
        for (Map.Entry<Long, Map<String, Object>> e : receiptsBefore.entrySet()) {
            Map<String, Object> after = receiptsAfter.get(e.getKey());
            if (after == null
                    || !String.valueOf(after.get("snapshot_json"))
                    .equals(String.valueOf(e.getValue().get("snapshot_json")))
                    || !String.valueOf(after.get("receipt_no"))
                    .equals(String.valueOf(e.getValue().get("receipt_no")))) {
                oldUntouched = false;
            }
        }
        check(checks, "撤销不改旧回执：撤销前所有回执编号与方案快照逐字保持不变",
                oldUntouched && receiptsAfter.size() > receiptsBefore.size(),
                "old rows identical + new receipt added",
                Map.of("before", receiptsBefore.size(), "after", receiptsAfter.size()));

        // 9) DB 触发器层自证回执不可改/不可删 -----------------------------
        long anyReceiptId = receiptsBefore.keySet().iterator().next();
        Map<String, Object> updProof = proveBlocked(
                "UPDATE power_receipt SET note='HACKED' WHERE id=" + anyReceiptId);
        Map<String, Object> delProof = proveBlocked(
                "DELETE FROM power_receipt WHERE id=" + anyReceiptId);
        check(checks, "数据库触发器阻止 UPDATE 供电回执",
                Boolean.TRUE.equals(updProof.get("blocked")), "blocked=true", updProof);
        check(checks, "数据库触发器阻止 DELETE 供电回执",
                Boolean.TRUE.equals(delProof.get("blocked")), "blocked=true", delProof);
        Integer stillThere = powerDao.jdbc().queryForObject(
                "SELECT count(*) FROM power_receipt WHERE id=?", Integer.class, anyReceiptId);
        check(checks, "强删失败后旧回执仍在库中",
                stillThere != null && stillThere == 1, 1, stillThere);

        // 10) 重复撤销被拒绝（历史不可变语义） ----------------------------
        boolean doubleRevokeRejected = false;
        try {
            powerService.revoke(alarmId, "重复撤销", "回放调度");
        } catch (Exception e) {
            doubleRevokeRejected = e.getMessage() != null
                    && e.getMessage().contains("已撤销");
        }
        check(checks, "重复撤销同一告警被拒绝（旧回执/方案不受影响）",
                doubleRevokeRejected, "409 POWER_ALARM_ALREADY_REVOKED",
                doubleRevokeRejected ? "rejected" : "not-rejected");

        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("receiptsBeforeRevoke", receiptsBefore.size());
        out.put("receiptsAfterRevoke", receiptsAfter.size());
        out.put("receiptImmutableUpdateProof", updProof);
        out.put("receiptImmutableDeleteProof", delProof);
        return out;
    }

    // ---------------- helpers ----------------

    private Map<String, Object> proveBlocked(String sql) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            powerDao.jdbc().execute(sql);
            m.put("blocked", false);
        } catch (Exception e) {
            m.put("blocked", true);
            Throwable t = e;
            while (t.getCause() != null && t.getCause() != t) t = t.getCause();
            m.put("error", t.getMessage());
        }
        return m;
    }

    private Map<Long, Map<String, Object>> rawReceipts() {
        List<Map<String, Object>> rows = powerDao.jdbc().queryForList(
                "SELECT id, receipt_no, plan_id, alarm_id, snapshot_json, note "
                        + "FROM power_receipt ORDER BY id");
        Map<Long, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) map.put(((Number) r.get("id")).longValue(), r);
        return map;
    }

    private int demandedByCode(List<Map<String, Object>> accounts, String code) {
        return accounts.stream().filter(a -> code.equals(a.get("code"))).findFirst()
                .map(a -> ((Number) a.get("demandedKw")).intValue()).orElse(-1);
    }

    private long idByCode(List<Map<String, Object>> accounts, String code) {
        return accounts.stream().filter(a -> code.equals(a.get("code"))).findFirst()
                .map(a -> ((Number) a.get("id")).longValue()).orElse(-1L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planByStrategy(List<Map<String, Object>> plans, String s) {
        return plans.stream().filter(p -> s.equals(p.get("strategy"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, List<Map<String, Object>>> allocByAccount(Map<String, Object> plan) {
        Map<Long, List<Map<String, Object>>> m = new LinkedHashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) plan.get("allocations")) {
            m.computeIfAbsent(longV(a, "accountId"), k -> new ArrayList<>()).add(a);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> slotTotals(Map<String, Object> plan) {
        List<Integer> l = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) plan.get("slotChecks")) {
            l.add(intV(s, "totalAllocatedKw"));
        }
        return l;
    }

    private boolean allSlotTotals(Map<String, Object> plan, int limit) {
        return slotTotals(plan).stream().allMatch(t -> t <= limit);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> impactOfPlan(Map<String, Object> plan, long accountId) {
        return ((List<Map<String, Object>>) plan.get("cargoImpacts")).stream()
                .filter(i -> longV(i, "accountId") == accountId).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> restoration(Map<String, Object> plan) {
        Object r = plan.get("restoration");
        return r instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private int intV(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : -1;
    }

    private long longV(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : -1L;
    }

    private double dblV(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private void check(List<Map<String, Object>> checks, String name, boolean pass,
                       Object expected, Object actual) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("pass", pass);
        c.put("expected", expected);
        c.put("actual", actual);
        checks.add(c);
    }
}
