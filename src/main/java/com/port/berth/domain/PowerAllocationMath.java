package com.port.berth.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 馈线临时降容告警下的岸电分时分配 —— 纯数学内核（无 Spring / 无数据库依赖，便于单测）。
 *
 * <p>内部一律用整数 <b>kW</b> 表示功率（0.6MW=600kW），避免二进制浮点导致
 * 0.1+0.2≠0.3 一类误差；MW 仅用于接口/展示换算。</p>
 *
 * <p>两条不可动摇的校验红线：</p>
 * <ol>
 *   <li><b>馈线总量核验</b>：每个时段都必须满足 ∑各船实配 ≤ 馈线安全上限，
 *       且由本类统一求和判定，绝不允许“每艘船各自降额”替代；</li>
 *   <li><b>货物安全负荷核验</b>：冷藏等关键负荷每个时段都必须 ≥ safetyFloorKw
 *       （冷藏船连续 ≥0.4MW）。总量不超限不代表安全负荷得到保护。</li>
 * </ol>
 */
public final class PowerAllocationMath {

    private PowerAllocationMath() {}

    public static final int KW_PER_MW = 1000;

    /** 两种现实中都有用的排序，外加告警撤销后的全量恢复方案。 */
    public enum Strategy {
        /** 按供电承诺确认时间先后，先确认者先保（合同/承诺优先）。 */
        CONFIRMATION_ORDER,
        /** 按货物最低安全负荷重新分配，冷藏等安全负荷优先（货运安全优先）。 */
        CARGO_SAFETY,
        /** 告警撤销、馈线恢复额定容量后的全量供电新方案。 */
        FULL_RESTORE
    }

    /** 一条已确认用电作业（承诺的分解项）。 */
    public record Op(long commitmentId, String no, String name,
                     int requiredKw, int minKw, boolean flexible) {}

    /** 一艘在泊受电船。confirmSeq 由 confirmed_at 升序映射（越早越小）。 */
    public record Load(long accountId, String code, String shipName,
                       int demandedKw, int safetyFloorKw, boolean continuous,
                       int confirmSeq, int compensationRatePerMwh, List<Op> ops) {

        /** 不可转移/不可削减的基础负荷：所有非灵活作业之和；无作业明细时退回安全下限。 */
        public int inflexibleKw() {
            if (ops == null || ops.isEmpty()) return safetyFloorKw;
            int sum = 0;
            for (Op op : ops) if (!op.flexible()) sum += op.requiredKw();
            return sum;
        }
    }

    /** 单船单时段的分配结果。 */
    public record AccountSlot(long accountId, int slot, int demandedKw, int allocatedKw,
                              int curtailedKw, int safetyFloorKw, boolean safetyViolation,
                              String basis) {}

    /** 一个时段的全馈线总量核验结果（关键：跨船求和）。 */
    public record SlotCheck(int slot, int totalAllocatedKw, int limitKw,
                            int headroomKw, boolean withinLimit) {}

    /** 一条削减/补偿依据：某船某时段被削减的功率，可归因到具体已确认作业。 */
    public record Curtailment(long accountId, int slot, Long commitmentId, String commitmentNo,
                              String operationName, int requestedKw, int allocatedKw,
                              int curtailedKw, int durationMin, double curtailedEnergyMwh,
                              int compensationRatePerMwh, double compensationAmountCny,
                              String basisCode) {}

    /** 恢复次序中的一步（按该顺序把船恢复到全额需求，并做累计馈线核验）。 */
    public record RestoreStep(int seq, long accountId, String shipName, String priority,
                              int stepLoadKw, int cumulativeKw, int requiredCapacityKw,
                              boolean withinNormalLimit, boolean withinAlarmLimit, String note) {}

    public record StrategyResult(String strategy, int limitKw, int normalLimitKw,
                                 int slotMinutes, int slotCount,
                                 boolean feederTotalCheckPass, boolean safetyFeasible,
                                 List<AccountSlot> accountSlots, List<SlotCheck> slotChecks,
                                 List<Curtailment> curtailments, List<RestoreStep> restoration,
                                 List<String> warnings) {}

    // ====================================================================
    // 对外主入口
    // ====================================================================

    public static StrategyResult allocate(Strategy strategy, List<Load> loads,
                                         int alarmLimitKw, int normalLimitKw,
                                         int slotCount, int slotMinutes) {
        if (loads == null || loads.isEmpty()) {
            throw new IllegalArgumentException("没有在泊受电船舶，无法分配");
        }
        if (slotCount <= 0) slotCount = 1;
        if (slotMinutes <= 0) slotMinutes = 60;

        List<Load> byConfirm = loads.stream()
                .sorted(Comparator.comparingInt(Load::confirmSeq)
                        .thenComparingLong(Load::accountId))
                .toList();

        return switch (strategy) {
            case CONFIRMATION_ORDER -> confirmationOrder(
                    byConfirm, alarmLimitKw, normalLimitKw, slotCount, slotMinutes);
            case CARGO_SAFETY, FULL_RESTORE -> cargoSafety(
                    strategy, byConfirm,
                    strategy == Strategy.FULL_RESTORE ? normalLimitKw : alarmLimitKw,
                    alarmLimitKw, normalLimitKw, slotCount, slotMinutes);
        };
    }

    // --------------------------------------------------------------------
    // 顺序一：按确认时间保护既有供电承诺（先到先得，余量给最后确认者）
    // --------------------------------------------------------------------
    private static StrategyResult confirmationOrder(List<Load> byConfirm, int limitKw,
                                                    int normalLimitKw, int slotCount,
                                                    int slotMinutes) {
        List<AccountSlot> rows = new ArrayList<>();
        List<SlotCheck> checks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int t = 0; t < slotCount; t++) {
            int remaining = limitKw;
            int total = 0;
            for (Load load : byConfirm) {
                int alloc = Math.min(load.demandedKw(), Math.max(0, remaining));
                remaining -= alloc;
                total += alloc;
                int cut = load.demandedKw() - alloc;
                boolean violate = alloc < load.safetyFloorKw();
                String basis;
                if (violate) basis = "BELOW_SAFETY_FLOOR";
                else if (cut == 0) basis = "COMMITMENT_FULL";
                else if (alloc > 0) basis = "REMAINDER_PARTIAL";
                else basis = "NO_CAPACITY_REMAINDER";
                rows.add(new AccountSlot(load.accountId(), t, load.demandedKw(), alloc, cut,
                        load.safetyFloorKw(), violate, basis));
                if (violate) {
                    warnings.add("船舶 " + load.shipName() + " 按确认顺序仅分得 " + mw3(alloc)
                            + "MW，低于货物安全负荷 " + mw3(load.safetyFloorKw()) + "MW");
                }
            }
            checks.add(new SlotCheck(t, total, limitKw, limitKw - total, total <= limitKw));
        }

        boolean feederPass = checks.stream().allMatch(SlotCheck::withinLimit);
        boolean safetyOk = rows.stream().noneMatch(AccountSlot::safetyViolation);
        if (!safetyOk) {
            warnings.add("按确认时间优先虽满足馈线总量上限，但最晚确认的冷藏船安全负荷失守，"
                    + "必须转移冷藏箱并存在货损风险");
        }

        List<Curtailment> cuts = buildCurtailments(rows, byConfirm, slotMinutes);
        List<RestoreStep> restore = restorationByConfirmation(
                byConfirm, limitKw, normalLimitKw);
        return new StrategyResult(Strategy.CONFIRMATION_ORDER.name(), limitKw, normalLimitKw,
                slotMinutes, slotCount, feederPass, safetyOk, rows, checks, cuts, restore,
                warnings.stream().distinct().toList());
    }

    // --------------------------------------------------------------------
    // 顺序二：货物最低安全负荷优先 + 普通船错峰/削减可转移作业
    // --------------------------------------------------------------------
    private static StrategyResult cargoSafety(Strategy strategy, List<Load> byConfirm,
                                              int limitKw, int alarmLimitKw, int normalLimitKw,
                                              int slotCount, int slotMinutes) {
        // 受保护基础负荷 = max(货物安全下限, 不可转移作业之和)
        int[] base = new int[byConfirm.size()];
        int sumBase = 0;
        for (int i = 0; i < byConfirm.size(); i++) {
            Load l = byConfirm.get(i);
            base[i] = Math.min(l.demandedKw(),
                    Math.max(l.safetyFloorKw(), l.inflexibleKw()));
            sumBase += base[i];
        }

        List<AccountSlot> rows = new ArrayList<>();
        List<SlotCheck> checks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        boolean baseOverLimit = sumBase > limitKw;
        if (baseOverLimit) {
            warnings.add("安全/基础负荷合计 " + sumBase + "kW 已超过馈线上限 " + limitKw
                    + "kW，无法同时保住全部安全负荷，需按安全优先级限电并启动冷藏箱转移");
        }

        // 有灵活作业空间的船（demanded - base > 0），用于错峰轮转峰值
        List<Integer> flexIdx = new ArrayList<>();
        int[] flex = new int[byConfirm.size()];
        for (int i = 0; i < byConfirm.size(); i++) {
            flex[i] = Math.max(0, byConfirm.get(i).demandedKw() - base[i]);
            if (flex[i] > 0) flexIdx.add(i);
        }

        for (int t = 0; t < slotCount; t++) {
            int[] alloc = new int[byConfirm.size()];
            if (baseOverLimit) {
                // 极端兜底：按安全优先级（冷藏优先，再按确认顺序）分配有限容量
                int remaining = limitKw;
                List<Integer> order = safetyPriorityOrder(byConfirm);
                for (int i : order) {
                    int give = Math.min(base[i], Math.max(0, remaining));
                    alloc[i] = give;
                    remaining -= give;
                }
            } else {
                for (int i = 0; i < byConfirm.size(); i++) alloc[i] = base[i];
                int budget = limitKw - sumBase;
                int peak = flexIdx.isEmpty() ? -1 : flexIdx.get(t % flexIdx.size());
                if (peak >= 0 && budget > 0) {
                    int give = Math.min(flex[peak], budget);
                    alloc[peak] += give;
                    budget -= give;
                }
                for (int i : flexIdx) {
                    if (i == peak || budget <= 0) continue;
                    int already = alloc[i] - base[i];
                    int room = flex[i] - already;
                    int give = Math.min(room, budget);
                    alloc[i] += give;
                    budget -= give;
                }
            }

            int total = 0;
            for (int i = 0; i < byConfirm.size(); i++) {
                Load l = byConfirm.get(i);
                int a = Math.min(alloc[i], l.demandedKw());
                alloc[i] = a;
                total += a;
                int cut = l.demandedKw() - a;
                boolean violate = a < l.safetyFloorKw();
                String basis;
                if (violate) basis = "BELOW_SAFETY_FLOOR";
                else if (cut == 0) basis = "FULL_SUPPLY";
                else if (a <= base[i]) basis = "BASELOAD_ONLY_FLEX_SHIFTED";
                else basis = "PARTIAL_FLEX_ALLOC";
                rows.add(new AccountSlot(l.accountId(), t, l.demandedKw(), a, cut,
                        l.safetyFloorKw(), violate, basis));
                if (violate) {
                    warnings.add("船舶 " + l.shipName() + " 仍低于安全负荷 "
                            + l.safetyFloorKw() + "kW");
                }
            }
            checks.add(new SlotCheck(t, total, limitKw, limitKw - total, total <= limitKw));
        }

        boolean feederPass = checks.stream().allMatch(SlotCheck::withinLimit);
        boolean safetyOk = rows.stream().noneMatch(AccountSlot::safetyViolation);
        if (strategy == Strategy.CARGO_SAFETY && safetyOk) {
            warnings.add("冷藏船全程保有 " + mw3(byConfirm.stream()
                    .filter(l -> l.safetyFloorKw() > 0).findFirst()
                    .map(Load::safetyFloorKw).orElse(0))
                    + "MW 安全负荷；普通船通过错峰/削减可转移作业平衡缺口，并据此出具补偿依据");
        }

        List<Curtailment> cuts = buildCurtailments(rows, byConfirm, slotMinutes);
        List<RestoreStep> restore = restorationBySafety(
                byConfirm, alarmLimitKw, normalLimitKw);
        return new StrategyResult(strategy.name(), limitKw, normalLimitKw,
                slotMinutes, slotCount, feederPass, safetyOk, rows, checks, cuts, restore,
                warnings.stream().distinct().toList());
    }

    // ====================================================================
    // 削减记录 → 归因到具体已确认作业 + 补偿测算
    // ====================================================================
    private static List<Curtailment> buildCurtailments(List<AccountSlot> rows,
                                                       List<Load> loads, int slotMinutes) {
        Map<Long, Load> byId = new LinkedHashMap<>();
        for (Load l : loads) byId.put(l.accountId(), l);

        List<Curtailment> out = new ArrayList<>();
        for (AccountSlot r : rows) {
            if (r.curtailedKw() <= 0) continue;
            Load l = byId.get(r.accountId());
            Op op = pickResponsibleOp(l, r.curtailedKw());
            int duration = slotMinutes;
            double energy = round3(r.curtailedKw() * (duration / 60.0) / KW_PER_MW);
            double amount = round2(energy * l.compensationRatePerMwh());
            out.add(new Curtailment(
                    l.accountId(), r.slot(),
                    op == null ? (Long) null : op.commitmentId(),
                    op == null ? null : op.no(),
                    op == null ? "已确认用电负荷（综合）" : op.name(),
                    r.demandedKw(), r.allocatedKw(), r.curtailedKw(),
                    duration, energy, l.compensationRatePerMwh(), amount,
                    r.basis()));
        }
        return out;
    }

    /** 找最能解释本次削减的灵活作业：功率恰好匹配者优先，否则取最大的可转移作业。 */
    private static Op pickResponsibleOp(Load l, int curtailedKw) {
        if (l.ops() == null || l.ops().isEmpty()) return null;
        Op exact = null;
        Op largestFlex = null;
        for (Op op : l.ops()) {
            if (!op.flexible()) continue;
            if (op.requiredKw() == curtailedKw) exact = op;
            if (largestFlex == null || op.requiredKw() > largestFlex.requiredKw()) {
                largestFlex = op;
            }
        }
        if (exact != null) return exact;
        if (largestFlex != null) return largestFlex;
        return l.ops().get(0);
    }

    // ====================================================================
    // 恢复次序
    // ====================================================================

    /** 承诺顺序的恢复：确认越早越先恢复（冷藏船确认最晚 → 最后恢复，明确警示）。 */
    private static List<RestoreStep> restorationByConfirmation(List<Load> byConfirm,
                                                               int alarmLimitKw,
                                                               int normalLimitKw) {
        List<RestoreStep> steps = new ArrayList<>();
        int cum = 0;
        int seq = 1;
        for (Load l : byConfirm) {
            cum += l.demandedKw();
            boolean inAlarm = cum <= alarmLimitKw;
            String note;
            if (l.safetyFloorKw() > 0) {
                note = "冷藏船确认时间最晚，按承诺顺序排在最后恢复；在累计达到 " + cum
                        + "kW 前其安全负荷持续失守，需先转移冷藏箱";
            } else {
                note = "按确认时间优先恢复既有供电承诺";
            }
            steps.add(new RestoreStep(seq++, l.accountId(), l.shipName(),
                    "CONFIRMATION_TIME", l.demandedKw(), cum, cum,
                    cum <= normalLimitKw, inAlarm, note));
        }
        return steps;
    }

    /** 安全顺序的恢复：关键/连续负荷（冷藏）优先，其余普通船再按确认时间。 */
    private static List<RestoreStep> restorationBySafety(List<Load> loads,
                                                         int alarmLimitKw,
                                                         int normalLimitKw) {
        List<Load> order = safetyPriorityOrder(loads).stream()
                .map(idx -> loads.get(idx)).toList();
        List<RestoreStep> steps = new ArrayList<>();
        int cum = 0;
        int seq = 1;
        for (Load l : order) {
            cum += l.demandedKw();
            boolean inAlarm = cum <= alarmLimitKw;
            String note;
            if (l.safetyFloorKw() > 0 || l.continuous()) {
                note = "冷藏/关键连续负荷第一优先，恢复并全程保持 ≥"
                        + mw3(l.safetyFloorKw()) + "MW 安全下限";
            } else {
                note = "关键负荷稳住后，按确认时间恢复普通船已确认作业";
            }
            if (!inAlarm) {
                note += "；累计 " + cum + "kW 超过降容上限 " + alarmLimitKw
                        + "kW，需待馈线撤销告警/容量恢复后方可整船恢复";
            }
            steps.add(new RestoreStep(seq++, l.accountId(), l.shipName(),
                    l.safetyFloorKw() > 0 ? "CARGO_SAFETY_FIRST" : "THEN_CONFIRMATION_TIME",
                    l.demandedKw(), cum, cum, cum <= normalLimitKw, inAlarm, note));
        }
        return steps;
    }

    /** 安全优先级排序：有安全下限/连续要求者最前，其次确认时间早者。返回下标。 */
    private static List<Integer> safetyPriorityOrder(List<Load> loads) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < loads.size(); i++) idx.add(i);
        idx.sort(Comparator
                .comparing((Integer i) -> (loads.get(i).safetyFloorKw() > 0
                        || loads.get(i).continuous()) ? 0 : 1)
                .thenComparingInt(i -> loads.get(i).confirmSeq())
                .thenComparingLong(i -> loads.get(i).accountId()));
        return idx;
    }

    // ====================================================================
    // “逐船独立降额”反例：每艘船都满足独立上限，馈线总量却可能超载
    // ====================================================================
    public record NaiveDerate(int perShipCapKw, List<Integer> allocationsKw,
                              int totalKw, int feederLimitKw, boolean everyShipWithinCap,
                              boolean feederTotalPass, String lesson) {}

    /**
     * 演示一个典型错误做法：给每艘船独立设一个功率上限（如 0.5MW）逐船降额，
     * 却不汇总核验馈线。结果 500+500+400=1400kW &gt; 1200kW，馈线仍然超载 200kW。
     */
    public static NaiveDerate naiveIndependentDerating(List<Load> loads, int perShipCapKw,
                                                       int feederLimitKw) {
        List<Integer> alloc = new ArrayList<>();
        boolean eachWithin = true;
        int total = 0;
        for (Load l : loads) {
            int a = Math.min(l.demandedKw(), perShipCapKw);
            alloc.add(a);
            total += a;
            if (a > perShipCapKw) eachWithin = false;
        }
        boolean feederPass = total <= feederLimitKw;
        String lesson = "逐船独立降到 ≤" + mw3(perShipCapKw) + "MW 后，各船看似都不超标，"
                + "但合计 " + mw3(total) + "MW " + (feederPass ? "≤" : ">")
                + " 馈线安全上限 " + mw3(feederLimitKw) + "MW；"
                + "因此必须逐时段汇总做馈线总量核验，不能以逐船降额代替。";
        return new NaiveDerate(perShipCapKw, alloc, total, feederLimitKw,
                eachWithin, feederPass, lesson);
    }

    /** 核验任意给定的一整套时段分配是否满足馈线总量上限（供持久化后复核/测试）。 */
    public static boolean verifyFeederTotal(List<SlotCheck> checks) {
        return checks != null && !checks.isEmpty()
                && checks.stream().allMatch(SlotCheck::withinLimit);
    }

    public static double round3(double v) { return Math.round(v * 1000d) / 1000d; }
    public static double round2(double v) { return Math.round(v * 100d) / 100d; }

    public static String mw3(int kw) {
        return String.format(java.util.Locale.ROOT, "%.2f", kw / (double) KW_PER_MW);
    }
}
