package com.port.berth;

import com.port.berth.domain.PowerAllocationMath;
import com.port.berth.domain.PowerAllocationMath.Load;
import com.port.berth.domain.PowerAllocationMath.NaiveDerate;
import com.port.berth.domain.PowerAllocationMath.Op;
import com.port.berth.domain.PowerAllocationMath.SlotCheck;
import com.port.berth.domain.PowerAllocationMath.Strategy;
import com.port.berth.domain.PowerAllocationMath.StrategyResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * feature-4 供电分配内核规格：
 * 1.2MW 馈线 + 0.6/0.5/0.4 三船、冷藏 0.4 连续安全下限下，两种顺序的精确结果，
 * 以及“逐船独立降额不能代替馈线总量核验”。
 */
class PowerAllocationMathTest {

    private static final int LIMIT = 1200;
    private static final int NORMAL = 2000;

    /** 与播种一致的三艘船：id 1/2 普通船先确认，id 3 冷藏船最后确认。 */
    private List<Load> loads() {
        Load p1 = new Load(1L, "PA-01", "普通船1", 600, 0, false, 1, 200, List.of(
                new Op(101L, "PC-0101", "基础负荷", 300, 300, false),
                new Op(102L, "PC-0102", "1号克令吊", 300, 0, true)));
        Load p2 = new Load(2L, "PA-02", "普通船2", 500, 0, false, 2, 200, List.of(
                new Op(201L, "PC-0201", "基础负荷", 200, 200, false),
                new Op(202L, "PC-0202", "2号船吊", 300, 0, true)));
        Load rf = new Load(3L, "PA-03", "冷藏船", 400, 400, true, 3, 0, List.of(
                new Op(301L, "PC-0301", "制冷机组", 320, 320, false),
                new Op(302L, "PC-0302", "融霜监控", 80, 80, false)));
        return List.of(p1, p2, rf);
    }

    private int alloc(StrategyResult r, long accountId, int slot) {
        return r.accountSlots().stream()
                .filter(a -> a.accountId() == accountId && a.slot() == slot)
                .findFirst().orElseThrow().allocatedKw();
    }

    @Test
    void confirmationOrder_protectsFirstTwo_andLeavesReeferOnlyPointOne() {
        StrategyResult r = PowerAllocationMath.allocate(
                Strategy.CONFIRMATION_ORDER, loads(), LIMIT, NORMAL, 4, 30);

        // 0.6 + 0.5 = 1.1，剩余 0.1 给最晚确认的冷藏船
        for (int t = 0; t < 4; t++) {
            assertEquals(600, alloc(r, 1L, t));
            assertEquals(500, alloc(r, 2L, t));
            assertEquals(100, alloc(r, 3L, t), "冷藏船只拿到剩余 0.1MW");
        }
        // 馈线总量不超限（恰好 1.2），但货物安全不可行
        assertTrue(r.feederTotalCheckPass());
        assertTrue(r.slotChecks().stream().allMatch(s -> s.totalAllocatedKw() == 1200));
        assertFalse(r.safetyFeasible(), "冷藏 0.1 < 0.4 安全下限");
        assertTrue(r.accountSlots().stream()
                .anyMatch(a -> a.accountId() == 3L && a.safetyViolation()));
    }

    @Test
    void cargoSafety_holdsReeferAtPointFour_andShiftsOrdinaryShips() {
        StrategyResult r = PowerAllocationMath.allocate(
                Strategy.CARGO_SAFETY, loads(), LIMIT, NORMAL, 4, 30);

        assertTrue(r.safetyFeasible());
        assertTrue(r.feederTotalCheckPass());

        for (int t = 0; t < 4; t++) {
            assertEquals(400, alloc(r, 3L, t), "冷藏每时段连续 0.4MW");
            assertEquals(1200, r.slotChecks().get(t).totalAllocatedKw());
        }
        // 精确错峰：偶数档 船1满/船2基础，奇数档 船1基础/船2满
        assertEquals(600, alloc(r, 1L, 0));
        assertEquals(200, alloc(r, 2L, 0));
        assertEquals(300, alloc(r, 1L, 1));
        assertEquals(500, alloc(r, 2L, 1));
        assertEquals(600, alloc(r, 1L, 2));
        assertEquals(200, alloc(r, 2L, 2));
        assertEquals(300, alloc(r, 1L, 3));
        assertEquals(500, alloc(r, 2L, 3));

        // 4 条削减（每艘普通船各两个 30 分钟档），总电量 0.6MWh，补偿 120 元
        assertEquals(4, r.curtailments().size());
        double energy = r.curtailments().stream()
                .mapToDouble(PowerAllocationMath.Curtailment::curtailedEnergyMwh).sum();
        double amount = r.curtailments().stream()
                .mapToDouble(PowerAllocationMath.Curtailment::compensationAmountCny).sum();
        assertEquals(0.6, PowerAllocationMath.round3(energy), 1e-9);
        assertEquals(120.0, PowerAllocationMath.round2(amount), 1e-9);
    }

    @Test
    void independentPerShipDerating_stillOverloadsFeeder_andTotalCheckCatchesIt() {
        // 典型错误：每艘船独立压到 ≤0.5MW，各自都“达标”，合计却 1.4MW > 1.2MW
        NaiveDerate trap = PowerAllocationMath.naiveIndependentDerating(loads(), 500, LIMIT);
        assertEquals(List.of(500, 500, 400), trap.allocationsKw());
        assertTrue(trap.everyShipWithinCap());
        assertEquals(1400, trap.totalKw());
        assertFalse(trap.feederTotalPass(), "逐船独立降额后馈线仍超载 0.2MW");

        // 总量核验必须能抓住任意超载时段
        List<SlotCheck> bad = List.of(
                new SlotCheck(0, 1200, 1200, 0, true),
                new SlotCheck(1, 1400, 1200, -200, false));
        assertFalse(PowerAllocationMath.verifyFeederTotal(bad));
    }

    @Test
    void everyGeneratedEmergencyPlan_respectsFeederTotalEverySlot() {
        for (Strategy s : List.of(Strategy.CONFIRMATION_ORDER, Strategy.CARGO_SAFETY)) {
            StrategyResult r = PowerAllocationMath.allocate(s, loads(), LIMIT, NORMAL, 6, 20);
            for (SlotCheck c : r.slotChecks()) {
                assertTrue(c.totalAllocatedKw() <= LIMIT, s + " 时段 " + c.slot() + " 超馈线上限");
                assertTrue(c.withinLimit());
            }
        }
    }

    @Test
    void fullRestore_returnsAllShipsToDemand_withinNormalCapacity() {
        StrategyResult r = PowerAllocationMath.allocate(
                Strategy.FULL_RESTORE, loads(), LIMIT, NORMAL, 1, 60);
        assertTrue(r.feederTotalCheckPass());
        assertTrue(r.safetyFeasible());
        assertEquals(1500, r.slotChecks().get(0).totalAllocatedKw());
        assertTrue(r.curtailments().isEmpty(), "全额恢复不应再有削减");
        for (long id : List.of(1L, 2L, 3L)) {
            assertEquals(id == 1L ? 600 : id == 2L ? 500 : 400, alloc(r, id, 0));
        }
    }

    @Test
    void safetyRestoration_putsReeferFirst_confirmationRestorationPutsItLast() {
        StrategyResult safe = PowerAllocationMath.allocate(
                Strategy.CARGO_SAFETY, loads(), LIMIT, NORMAL, 4, 30);
        assertEquals(3L, safe.restoration().get(0).accountId());
        assertEquals("CARGO_SAFETY_FIRST", safe.restoration().get(0).priority());

        StrategyResult conf = PowerAllocationMath.allocate(
                Strategy.CONFIRMATION_ORDER, loads(), LIMIT, NORMAL, 4, 30);
        assertEquals(3L, conf.restoration().get(conf.restoration().size() - 1).accountId());
        // 最后一步累计 1500kW，超过降容期 1200kW，必须等告警撤销
        assertFalse(conf.restoration().get(conf.restoration().size() - 1).withinAlarmLimit());
        assertTrue(conf.restoration().get(conf.restoration().size() - 1).withinNormalLimit());
    }
}
