package com.port.berth.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * feature-4 供电调度域持久化：馈线 / 在泊受电账户 / 已确认用电作业 /
 * 告警 / 方案 / 分时分配 / 削减补偿 / 货物影响 / 不可变回执。
 */
@Repository
public class PowerDao {

    private final JdbcTemplate jdbc;

    public PowerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 回放中做数据库层“回执不可变”自证时需要直接拿 JdbcTemplate。 */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    // ---------------- 行模型 ----------------

    public record FeederRow(long id, String code, String name, int normalCapacityKw,
                            int safetyCapacityKw, String status) {}

    public record AccountRow(long id, long feederId, String code, String berthCode,
                             String shipCode, String shipName, String cargoClass,
                             String cargoDetail, int reeferBoxCount, int demandedKw,
                             int safetyFloorKw, boolean continuous, boolean transferIfBelow,
                             LocalDateTime confirmedAt, int confirmSeq,
                             int compensationRatePerMwh, String cargoRiskNote) {}

    public record CommitmentRow(long id, long accountId, String commitNo, int seqNo,
                                String operationName, int requiredKw, int minKw,
                                boolean flexible, LocalDateTime confirmedAt, String status) {}

    public record AlarmRow(long id, String alarmNo, long feederId, int fromCapacityKw,
                           int deratedCapacityKw, String reason, LocalDateTime alarmAt,
                           LocalDateTime windowStart, LocalDateTime windowEnd, int slotMinutes,
                           String status, LocalDateTime revokedAt, String revokeReason,
                           Integer restoredCapacityKw, String createdBy) {}

    // ---------------- Mapper ----------------

    private static final RowMapper<FeederRow> FEEDER_MAPPER = (rs, n) -> new FeederRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getInt("normal_capacity_kw"), rs.getInt("safety_capacity_kw"),
            rs.getString("status"));

    private static final RowMapper<AccountRow> ACCOUNT_MAPPER = (rs, n) -> {
        Timestamp ca = rs.getTimestamp("confirmed_at");
        return new AccountRow(
                rs.getLong("id"), rs.getLong("feeder_id"), rs.getString("code"),
                rs.getString("berth_code"), rs.getString("ship_code"), rs.getString("ship_name"),
                rs.getString("cargo_class"), rs.getString("cargo_detail"),
                rs.getInt("reefer_box_count"), rs.getInt("demanded_kw"),
                rs.getInt("safety_floor_kw"), rs.getBoolean("continuous"),
                rs.getBoolean("transfer_if_below"),
                ca == null ? null : ca.toLocalDateTime(),
                rs.getInt("confirm_seq"), rs.getInt("compensation_rate_per_mwh"),
                rs.getString("cargo_risk_note"));
    };

    private static final RowMapper<CommitmentRow> COMMIT_MAPPER = (rs, n) -> {
        Timestamp ca = rs.getTimestamp("confirmed_at");
        return new CommitmentRow(
                rs.getLong("id"), rs.getLong("account_id"), rs.getString("commit_no"),
                rs.getInt("seq_no"), rs.getString("operation_name"),
                rs.getInt("required_kw"), rs.getInt("min_kw"), rs.getBoolean("flexible"),
                ca == null ? null : ca.toLocalDateTime(), rs.getString("status"));
    };

    private static final RowMapper<AlarmRow> ALARM_MAPPER = (rs, n) -> {
        Timestamp aa = rs.getTimestamp("alarm_at");
        Timestamp ws = rs.getTimestamp("window_start");
        Timestamp we = rs.getTimestamp("window_end");
        Timestamp rv = rs.getTimestamp("revoked_at");
        Integer restored = (Integer) rs.getObject("restored_capacity_kw");
        return new AlarmRow(
                rs.getLong("id"), rs.getString("alarm_no"), rs.getLong("feeder_id"),
                rs.getInt("from_capacity_kw"), rs.getInt("derated_capacity_kw"),
                rs.getString("reason"),
                aa == null ? null : aa.toLocalDateTime(),
                ws == null ? null : ws.toLocalDateTime(),
                we == null ? null : we.toLocalDateTime(),
                rs.getInt("slot_minutes"), rs.getString("status"),
                rv == null ? null : rv.toLocalDateTime(),
                rs.getString("revoke_reason"), restored, rs.getString("created_by"));
    };

    // ---------------- 查询 ----------------

    public boolean feederDataExists() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM power_feeder", Integer.class);
        return n != null && n > 0;
    }

    public FeederRow defaultFeeder() {
        List<FeederRow> list = jdbc.query(
                "SELECT * FROM power_feeder ORDER BY id LIMIT 1", FEEDER_MAPPER);
        return list.isEmpty() ? null : list.get(0);
    }

    public FeederRow findFeeder(long id) {
        List<FeederRow> list = jdbc.query(
                "SELECT * FROM power_feeder WHERE id=?", FEEDER_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<AccountRow> listAccounts(long feederId) {
        return jdbc.query(
                "SELECT * FROM power_account WHERE feeder_id=? ORDER BY confirm_seq, id",
                ACCOUNT_MAPPER, feederId);
    }

    public AccountRow findAccount(long id) {
        List<AccountRow> l = jdbc.query(
                "SELECT * FROM power_account WHERE id=?", ACCOUNT_MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<CommitmentRow> listCommitments(long accountId) {
        return jdbc.query(
                "SELECT * FROM power_commitment WHERE account_id=? ORDER BY seq_no, id",
                COMMIT_MAPPER, accountId);
    }

    public AlarmRow findActiveAlarm(long feederId) {
        List<AlarmRow> l = jdbc.query(
                "SELECT * FROM power_alarm WHERE feeder_id=? AND status='ACTIVE' "
                        + "ORDER BY id DESC LIMIT 1", ALARM_MAPPER, feederId);
        return l.isEmpty() ? null : l.get(0);
    }

    public AlarmRow findAlarm(long id) {
        List<AlarmRow> l = jdbc.query(
                "SELECT * FROM power_alarm WHERE id=?", ALARM_MAPPER, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<AlarmRow> listAlarms() {
        return jdbc.query("SELECT * FROM power_alarm ORDER BY id DESC", ALARM_MAPPER);
    }

    // ---------------- 写入 ----------------

    public long insertFeeder(String code, String name, int normalKw, int safetyKw, String status) {
        return jdbc.queryForObject("""
                INSERT INTO power_feeder(code,name,normal_capacity_kw,safety_capacity_kw,status)
                VALUES (?,?,?,?,?) RETURNING id
                """, Long.class, code, name, normalKw, safetyKw, status);
    }

    public long insertAccount(AccountSeed a) {
        return jdbc.queryForObject("""
                INSERT INTO power_account(feeder_id,code,berth_code,ship_code,ship_name,
                    cargo_class,cargo_detail,reefer_box_count,demanded_kw,safety_floor_kw,
                    continuous,transfer_if_below,confirmed_at,confirm_seq,
                    compensation_rate_per_mwh,cargo_risk_note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class,
                a.feederId, a.code, a.berthCode, a.shipCode, a.shipName, a.cargoClass,
                a.cargoDetail, a.reeferBoxCount, a.demandedKw, a.safetyFloorKw,
                a.continuous, a.transferIfBelow, Timestamp.valueOf(a.confirmedAt),
                a.confirmSeq, a.ratePerMwh, a.riskNote);
    }

    public record AccountSeed(long feederId, String code, String berthCode, String shipCode,
                              String shipName, String cargoClass, String cargoDetail,
                              int reeferBoxCount, int demandedKw, int safetyFloorKw,
                              boolean continuous, boolean transferIfBelow,
                              LocalDateTime confirmedAt, int confirmSeq, int ratePerMwh,
                              String riskNote) {}

    public void insertCommitment(long accountId, String no, int seq, String name, int reqKw,
                                 int minKw, boolean flexible, LocalDateTime confirmedAt) {
        jdbc.update("""
                INSERT INTO power_commitment(account_id,commit_no,seq_no,operation_name,
                    required_kw,min_kw,flexible,confirmed_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, accountId, no, seq, name, reqKw, minKw, flexible,
                Timestamp.valueOf(confirmedAt));
    }

    public long insertAlarm(long feederId, int fromKw, int deratedKw, String reason,
                            LocalDateTime alarmAt, LocalDateTime start, LocalDateTime end,
                            int slotMinutes, String createdBy) {
        long id = jdbc.queryForObject("""
                INSERT INTO power_alarm(alarm_no,feeder_id,from_capacity_kw,
                    derated_capacity_kw,reason,alarm_at,window_start,window_end,
                    slot_minutes,status,created_by)
                VALUES ('PENDING',?,?,?,?,?,?,?,?,'ACTIVE',?) RETURNING id
                """, Long.class, feederId, fromKw, deratedKw, reason,
                Timestamp.valueOf(alarmAt), Timestamp.valueOf(start), Timestamp.valueOf(end),
                slotMinutes, createdBy);
        String no = String.format("ALM-%04d", id);
        jdbc.update("UPDATE power_alarm SET alarm_no=? WHERE id=?", no, id);
        return id;
    }

    public void revokeAlarm(long alarmId, String reason, LocalDateTime revokedAt,
                            int restoredKw) {
        jdbc.update("""
                UPDATE power_alarm SET status='REVOKED', revoke_reason=?,
                    revoked_at=?, restored_capacity_kw=? WHERE id=?
                """, reason, Timestamp.valueOf(revokedAt), restoredKw, alarmId);
    }

    public void updateFeederCapacity(long feederId, int safetyKw, String status) {
        jdbc.update("UPDATE power_feeder SET safety_capacity_kw=?, status=? WHERE id=?",
                safetyKw, status, feederId);
    }

    public long insertPlan(String planNo, Long alarmId, long feederId, String strategy,
                           String phase, int limitKw, int slotMinutes, int slotCount,
                           boolean feasible, boolean safetyFeasible, boolean totalCheckPass,
                           int totalDemandKw, int peakTotalKw, String naiveTrapJson,
                           String slotChecksJson, String restorationJson,
                           String warningsJson, String comparisonNote) {
        return jdbc.queryForObject("""
                INSERT INTO power_plan(plan_no,alarm_id,feeder_id,strategy,phase,
                    capacity_limit_kw,slot_minutes,slot_count,feasible,safety_feasible,
                    feeder_total_check_pass,total_demand_kw,peak_total_kw,naive_trap_json,
                    slot_checks_json,restoration_json,warnings_json,comparison_note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, planNo, alarmId, feederId, strategy, phase, limitKw,
                slotMinutes, slotCount, feasible, safetyFeasible, totalCheckPass,
                totalDemandKw, peakTotalKw, naiveTrapJson, slotChecksJson, restorationJson,
                warningsJson, comparisonNote);
    }

    public void markPlanIssued(long planId) {
        jdbc.update("UPDATE power_plan SET issued=TRUE WHERE id=?", planId);
    }

    public long insertAllocation(long planId, long accountId, int slotIndex,
                                 LocalDateTime slotStart, LocalDateTime slotEnd,
                                 int demanded, int allocated, int curtailed, int floor,
                                 boolean violation, String basis) {
        return jdbc.queryForObject("""
                INSERT INTO power_allocation(plan_id,account_id,slot_index,slot_start,slot_end,
                    demanded_kw,allocated_kw,curtailed_kw,safety_floor_kw,
                    safety_violation,basis)
                VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, planId, accountId, slotIndex,
                Timestamp.valueOf(slotStart), Timestamp.valueOf(slotEnd),
                demanded, allocated, curtailed, floor, violation, basis);
    }

    public void insertCurtailment(CurtailmentSeed c) {
        jdbc.update("""
                INSERT INTO power_curtailment(plan_id,account_id,commitment_id,commitment_no,
                    slot_index,slot_start,slot_end,operation_name,requested_kw,allocated_kw,
                    curtailed_kw,duration_min,curtailed_energy_mwh,
                    compensation_rate_per_mwh,compensation_amount_cny,basis_code,reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, c.planId, c.accountId, c.commitmentId, c.commitmentNo, c.slotIndex,
                c.slotStart == null ? null : Timestamp.valueOf(c.slotStart),
                c.slotEnd == null ? null : Timestamp.valueOf(c.slotEnd),
                c.operationName, c.requestedKw, c.allocatedKw, c.curtailedKw, c.durationMin,
                c.energyMwh, c.rate, c.amountCny, c.basisCode, c.reason);
    }

    public record CurtailmentSeed(long planId, long accountId, Long commitmentId,
                                  String commitmentNo, int slotIndex,
                                  LocalDateTime slotStart, LocalDateTime slotEnd,
                                  String operationName, int requestedKw, int allocatedKw,
                                  int curtailedKw, int durationMin, double energyMwh,
                                  int rate, double amountCny, String basisCode, String reason) {}

    public void insertCargoImpact(long planId, long accountId, String cargoClass,
                                  String cargoDetail, int boxCount, int floorKw,
                                  int minAllocKw, int deficitKw, String riskLevel,
                                  boolean transferRequired, String message) {
        jdbc.update("""
                INSERT INTO power_cargo_impact(plan_id,account_id,cargo_class,cargo_detail,
                    reefer_box_count,safety_floor_kw,min_allocated_kw,deficit_kw,risk_level,
                    transfer_required,message)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, planId, accountId, cargoClass, cargoDetail, boxCount, floorKw,
                minAllocKw, deficitKw, riskLevel, transferRequired, message);
    }

    public String nextReceiptNo() {
        Long n = jdbc.queryForObject("SELECT nextval('power_receipt_seq')", Long.class);
        return String.format("RCP-%04d", n == null ? 1 : n);
    }

    public long insertReceipt(String receiptNo, long planId, Long alarmId, String feederCode,
                              String strategy, String phase, int limitKw, String issuedBy,
                              String note, String snapshotJson) {
        return jdbc.queryForObject("""
                INSERT INTO power_receipt(receipt_no,plan_id,alarm_id,feeder_code,strategy,
                    phase,capacity_limit_kw,issued_by,note,snapshot_json)
                VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, receiptNo, planId, alarmId, feederCode, strategy, phase,
                limitKw, issuedBy, note, snapshotJson);
    }

    // ---------------- 明细查询（Map 形式，键为 snake_case，服务层转 camel） ----------------

    public List<java.util.Map<String, Object>> listPlansByAlarm(long alarmId) {
        return jdbc.queryForList("SELECT * FROM power_plan WHERE alarm_id=? ORDER BY id", alarmId);
    }

    public java.util.Map<String, Object> findPlanRow(long planId) {
        List<java.util.Map<String, Object>> l =
                jdbc.queryForList("SELECT * FROM power_plan WHERE id=?", planId);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<java.util.Map<String, Object>> listAllocations(long planId) {
        return jdbc.queryForList(
                "SELECT * FROM power_allocation WHERE plan_id=? ORDER BY slot_index, id", planId);
    }

    public List<java.util.Map<String, Object>> listCurtailments(long planId) {
        return jdbc.queryForList(
                "SELECT * FROM power_curtailment WHERE plan_id=? ORDER BY slot_index, id", planId);
    }

    public List<java.util.Map<String, Object>> listCargoImpacts(long planId) {
        return jdbc.queryForList(
                "SELECT * FROM power_cargo_impact WHERE plan_id=? ORDER BY id", planId);
    }

    public List<java.util.Map<String, Object>> listReceiptRows() {
        return jdbc.queryForList("SELECT * FROM power_receipt ORDER BY id DESC");
    }

    public java.util.Map<String, Object> findReceiptRow(long receiptId) {
        List<java.util.Map<String, Object>> l =
                jdbc.queryForList("SELECT * FROM power_receipt WHERE id=?", receiptId);
        return l.isEmpty() ? null : l.get(0);
    }
}
