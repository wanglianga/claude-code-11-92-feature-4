package com.port.berth.service;

import com.port.berth.repo.CatalogDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 本地模拟数据：船舶、船次、泊位、潮汐（30 分钟一档）、
 * 引航员、拖轮、岸桥与工作窗口、测试账号。
 * resetAndSeed() 供一键回放前恢复固定样例。
 */
@Service
@Order(20)
public class SeedService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    public static final LocalDateTime D = LocalDateTime.of(2026, 11, 26, 0, 0);

    private final JdbcTemplate jdbc;
    private final CatalogDao catalogDao;
    private final PowerService powerService;

    public SeedService(JdbcTemplate jdbc, CatalogDao catalogDao, PowerService powerService) {
        this.jdbc = jdbc;
        this.catalogDao = catalogDao;
        this.powerService = powerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
        // 老库可能已有账号数据而没有 feature-4 供电表数据，单独保证供电样例存在
        powerService.seedIfEmpty();
    }

    public synchronized void seedIfEmpty() {
        Integer users = jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class);
        if (users != null && users > 0) return;
        seedAll();
        log.info("模拟数据初始化完成");
    }

    /**清空全部业务表并恢复固定样例（回放/演示用）*/
    public synchronized void resetAndSeed() {
        jdbc.execute("""
                TRUNCATE TABLE power_receipt, power_curtailment, power_cargo_impact,
                    power_allocation, power_plan, power_alarm, power_commitment,
                    power_account, power_feeder,
                    impact_item, disruption, plan_event, work_record,
                    resource_occupation, resource_lock, plan_segment, berth_plan,
                    equipment_fault, work_window, quay_crane, tug, pilot, tide_slot,
                    berth_cargo_limit, berth, voyage, ship, auth_token, app_user
                RESTART IDENTITY CASCADE
                """);
        jdbc.execute("ALTER SEQUENCE power_receipt_seq RESTART WITH 1");
        seedAll();
        log.info("场景已重置并重新播种");
    }

    // ====================================================================

    private void seedAll() {
        seedUsers();
        seedShipsAndVoyages();
        seedBerths();
        seedTides();
        seedPilots();
        seedTugs();
        seedCranes();
        powerService.seedFixedScenario();
    }

    private void seedUsers() {
        user("agent", "agent123", "王代理（华港船代）", "AGENT");
        user("dispatch", "dispatch123", "赵调度（码头计划室）", "DISPATCHER");
        user("pilot", "pilot123", "孙引航班长", "TEAM");
        user("tug", "tug123", "周拖轮调度", "TEAM");
        user("crane", "crane123", "吴岸桥班长", "TEAM");
        user("admin", "admin123", "系统管理员", "ADMIN");
    }

    private void user(String u, String pwd, String name, String role) {
        jdbc.update("INSERT INTO app_user(username,password,display_name,role) VALUES (?,?,?,?)",
                u, pwd, name, role);
    }

    private void seedShipsAndVoyages() {
        ship("SHIP-A", "远集 A 轮", 300, 11.5, "CONTAINER");
        ship("SHIP-B", "远集 B 轮", 295, 11.5, "CONTAINER");
        ship("SHIP-C", "港城快航", 180, 7.0, "CONTAINER");
        ship("SHIP-D", "海峡滚装 6 号", 160, 5.5, "RORO");
        ship("SHIP-E", "晚霞海", 260, 10.0, "CONTAINER");

        long a = catalogDao.findShip(1).orElseThrow().id();
        // id 按插入顺序为 1..5
        catalogDao.createVoyage("VOY-A", a, "华港船代·王岚",
                D.withHour(19).withMinute(30), D.withHour(20).withMinute(30),
                60, 2, "核心样例：ETA 19:30-20:30，深水大船，B1 20:00 是唯一安全窗口");
        catalogDao.createVoyage("VOY-B", 2L, "华港船代·王岚",
                D.withHour(19).withMinute(0), D.withHour(20).withMinute(0),
                60, 2, "已提交 20:00 靠泊，与 A 竞争同一 B1+引航+拖轮组合");
        catalogDao.createVoyage("VOY-C", 3L, "联海船代·陈晨",
                D.withHour(13), D.withHour(15), 120, 1, "内贸集装箱班轮");
        catalogDao.createVoyage("VOY-D", 4L, "海峡轮渡公司",
                D.withHour(16), D.withHour(18), 90, 0, "滚装船，使用跳板，不需要岸桥");
        catalogDao.createVoyage("VOY-E", 5L, "远洋船代·林一",
                D.withHour(18), D.withHour(19), 120, 2,
                "开场故事船次：ETA 由 18:00-19:00 补报为更晚区间");
    }

    private void ship(String code, String name, double len, double draft, String cargo) {
        jdbc.update("INSERT INTO ship(code,name,length_m,draft_m,cargo_type) VALUES (?,?,?,?,?)",
                code, name, len, draft, cargo);
    }

    private void seedBerths() {
        berth("B1", "一号深水泊位", 320, 9.5, 1.0,
                List.of("CONTAINER", "BULK", "TANKER", "GENERAL"));
        berth("B2", "二号集装箱泊位", 240, 7.5, 1.0,
                List.of("CONTAINER", "GENERAL", "RORO"));
        berth("B3", "三号滚装杂货泊位", 180, 6.0, 1.0,
                List.of("RORO", "GENERAL"));
    }

    private void berth(String code, String name, double maxLen, double depth,
                       double ukc, List<String> cargos) {
        jdbc.update("""
                INSERT INTO berth(code,name,max_length_m,chart_depth_m,ukc_min_m)
                VALUES (?,?,?,?,?)
                """, code, name, maxLen, depth, ukc);
        Long id = jdbc.queryForObject("SELECT id FROM berth WHERE code=?", Long.class, code);
        for (String c : cargos) {
            jdbc.update("INSERT INTO berth_cargo_limit(berth_id,cargo_type) VALUES (?,?)", id, c);
        }
    }

    /**
     * 30 分钟一档潮汐。基准为半日潮余弦曲线（高潮中心约在首日 20:30 与次日 09:00），
     * 再对样例关键窗口做显式覆盖，保证“仅 20:00 一个可行起点”精确成立。
     */
    private void seedTides() {
        LocalDateTime start = D;
        LocalDateTime end = D.plusDays(2);
        // 关键覆盖：dayOffset -> "HH:mm" -> 水位
        Map<String, Double> override = Map.ofEntries(
                Map.entry("0|19:00", 2.50),
                Map.entry("0|19:30", 2.80),
                Map.entry("0|20:00", 3.40),
                Map.entry("0|20:30", 3.30),
                Map.entry("0|21:00", 3.20),
                Map.entry("0|21:30", 2.75),
                Map.entry("0|22:00", 2.60),
                Map.entry("0|22:30", 2.40),
                Map.entry("1|07:30", 2.80),
                Map.entry("1|08:00", 3.20),
                Map.entry("1|08:30", 3.30),
                Map.entry("1|09:00", 3.20),
                Map.entry("1|09:30", 2.90)
        );

        for (LocalDateTime t = start; t.isBefore(end); t = t.plusMinutes(30)) {
            double hoursSinceD = java.time.Duration.between(D, t).toMinutes() / 60.0;
            double h = 2.05 + 1.35 * Math.cos(2 * Math.PI * (hoursSinceD - 20.5) / 12.42);
            h = Math.round(h * 100.0) / 100.0;

            int dayOffset = t.getDayOfYear() - D.getDayOfYear();
            String key = dayOffset + "|" + String.format("%02d:%02d", t.getHour(), t.getMinute());
            if (override.containsKey(key)) h = override.get(key);

            jdbc.update("INSERT INTO tide_slot(slot_start,slot_end,height_m) VALUES (?,?,?)",
                    Timestamp.valueOf(t), Timestamp.valueOf(t.plusMinutes(30)), h);
        }
    }

    private void seedPilots() {
        pilot("PILOT-1", "张远航", "DEEP", 320, 12.5, 30);
        pilot("PILOT-2", "李近岸", "COASTAL", 200, 8.5, 20);
        pilot("PILOT-3", "王晨深", "DEEP", 320, 12.5, 30);
        window("PILOT", 1, D.withHour(17), D.plusDays(1).withHour(2));
        window("PILOT", 2, D.withHour(17), D.plusDays(1).withHour(1));
        window("PILOT", 3, D.plusDays(1).withHour(6), D.plusDays(1).withHour(16));
    }

    private void pilot(String code, String name, String qual,
                       double maxLen, double maxDraft, int handover) {
        jdbc.update("""
                INSERT INTO pilot(code,name,qualification,max_length_m,max_draft_m,handover_min)
                VALUES (?,?,?,?,?,?)
                """, code, name, qual, maxLen, maxDraft, handover);
    }

    private void seedTugs() {
        tug("TUG-1", "港拖 16", 320, 12.5, 68, 30);
        tug("TUG-2", "港拖 8", 200, 8.5, 32, 20);
        tug("TUG-3", "港拖 18", 320, 12.5, 70, 30);
        window("TUG", 1, D.withHour(17), D.plusDays(1).withHour(2));
        window("TUG", 2, D.withHour(17), D.plusDays(1).withHour(1));
        window("TUG", 3, D.plusDays(1).withHour(6), D.plusDays(1).withHour(14));
    }

    private void tug(String code, String name, double maxLen, double maxDraft,
                     double bp, int turnaround) {
        jdbc.update("""
                INSERT INTO tug(code,name,max_length_m,max_draft_m,bollard_pull_t,turnaround_min)
                VALUES (?,?,?,?,?,?)
                """, code, name, maxLen, maxDraft, bp, turnaround);
    }

    private void seedCranes() {
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO quay_crane(code,name,transfer_min,active) VALUES (?,?,20,true)",
                    "QC-" + i, i + " 号岸桥");
            // 当日白班 / 夜班两段 + 次日早班
            window("CRANE", i, D.withHour(6), D.withHour(14));
            window("CRANE", i, D.withHour(14), D.plusDays(1).withHour(0)); // 14:00-24:00
            window("CRANE", i, D.plusDays(1).withHour(6), D.plusDays(1).withHour(14));
        }
    }

    private void window(String type, long id, LocalDateTime s, LocalDateTime e) {
        jdbc.update("INSERT INTO work_window(resource_type,resource_id,start_at,end_at) " +
                "VALUES (?,?,?,?)", type, id, Timestamp.valueOf(s), Timestamp.valueOf(e));
    }
}
