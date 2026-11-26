package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.service.PowerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * feature-4 供电调度：馈线降容告警、两种顺序分时分配对照、削减补偿、
 * 恢复次序、不可变回执与告警撤销后的恢复新方案。
 */
@Tag(name = "供电调度 Power Dispatch",
        description = "馈线临时降容到 1.2MW 时的岸电分时分配、货物安全、补偿依据与回执")
@RestController
@RequestMapping("/api/power")
public class PowerController {

    private final PowerService powerService;

    public PowerController(PowerService powerService) {
        this.powerService = powerService;
    }

    public record RaiseAlarmRequest(
            @io.swagger.v3.oas.annotations.media.Schema(description = "降容后上限 MW，默认 1.2",
                    example = "1.2") Double deratedCapacityMw,
            String reason,
            LocalDateTime alarmAt,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            @io.swagger.v3.oas.annotations.media.Schema(description = "分时时段长度（分钟），默认 30",
                    example = "30") Integer slotMinutes) {}

    public record RevokeRequest(String reason) {}

    public record IssueReceiptRequest(String note) {}

    @Operation(summary = "供电调度页总览",
            description = "馈线、三艘在泊船与已确认用电作业、当前告警及双方案、回执台账、逐船独立降额反例")
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return powerService.overview();
    }

    @Operation(summary = "录入馈线降容告警并生成两种顺序的对照方案",
            description = "同时生成 CONFIRMATION_ORDER（按确认时间）与 CARGO_SAFETY（按货物最低安全负荷）"
                    + "两套分时方案，默认对安全方案下达不可变回执")
    @PostMapping("/alarms")
    public Map<String, Object> raiseAlarm(@RequestBody(required = false) RaiseAlarmRequest req,
                                          HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        PowerService.AlarmInput input = req == null ? null : new PowerService.AlarmInput(
                req.deratedCapacityMw(), req.reason(), req.alarmAt(), req.windowStart(),
                req.windowEnd(), req.slotMinutes());
        return powerService.raiseAlarm(input, operator(http));
    }

    @Operation(summary = "告警列表")
    @GetMapping("/alarms")
    public List<Map<String, Object>> alarms() {
        return powerService.listAlarms();
    }

    @Operation(summary = "告警详情：双方案分配表/总量核验/受影响货物/恢复次序/回执")
    @GetMapping("/alarms/{id}")
    public Map<String, Object> alarm(@PathVariable long id) {
        return powerService.alarmDetail(id);
    }

    @Operation(summary = "撤销告警并生成 FULL_RESTORE 新方案与新回执",
            description = "原告警的两套方案与已签发回执原样保留，不做修改")
    @PostMapping("/alarms/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable long id,
                                      @RequestBody(required = false) RevokeRequest req,
                                      HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return powerService.revoke(id, req == null ? null : req.reason(), operator(http));
    }

    @Operation(summary = "对指定方案下达/补打不可变调度回执")
    @PostMapping("/plans/{planId}/issue")
    public Map<String, Object> issue(@PathVariable long planId,
                                     @RequestBody(required = false) IssueReceiptRequest req,
                                     HttpServletRequest http) {
        // 值班电工(TEAM)也可签收/下达回执
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN, Role.TEAM);
        String note = req == null || req.note() == null ? "值班电工下达供电调度回执" : req.note();
        return powerService.issueReceipt(planId, note, operator(http));
    }

    @Operation(summary = "方案明细（含分时分配、削减补偿、货物影响、恢复次序）")
    @GetMapping("/plans/{planId}")
    public Map<String, Object> plan(@PathVariable long planId) {
        return powerService.planDetail(planId);
    }

    @Operation(summary = "不可变回执台账")
    @GetMapping("/receipts")
    public List<Map<String, Object>> receipts() {
        return powerService.listReceipts();
    }

    @Operation(summary = "回执详情（含签发时的方案快照）")
    @GetMapping("/receipts/{id}")
    public Map<String, Object> receipt(@PathVariable long id) {
        return powerService.receiptDetail(id);
    }

    private String operator(HttpServletRequest http) {
        var u = WebAuth.user(http);
        return u.displayName() + "(" + u.username() + ")";
    }
}
