package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.service.PowerReplayService;
import com.port.berth.service.ReplayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**一键回放三种顺序、并发竞速、策略对照、扰动演练与 feature-4 供电调度。*/
@RestController
@RequestMapping("/api/replays")
public class ReplayController {

    private final ReplayService replayService;
    private final PowerReplayService powerReplayService;

    public ReplayController(ReplayService replayService,
                            PowerReplayService powerReplayService) {
        this.replayService = replayService;
        this.powerReplayService = powerReplayService;
    }

    @PostMapping("/orderings")
    public Map<String, Object> orderings(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return replayService.runOrderings();
    }

    @PostMapping("/disruptions")
    public Map<String, Object> disruptions(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return replayService.runDisruptions();
    }

    @PostMapping("/power")
    public Map<String, Object> power(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        return powerReplayService.runPower();
    }

    @PostMapping("/full")
    public Map<String, Object> full(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.DISPATCHER, Role.ADMIN);
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("orderings", replayService.runOrderings());
        all.put("disruptions", replayService.runDisruptions());
        all.put("power", powerReplayService.runPower());
        boolean pass = Boolean.TRUE.equals(((Map<?, ?>) all.get("orderings")).get("allPassed"))
                && Boolean.TRUE.equals(((Map<?, ?>) all.get("disruptions")).get("allPassed"))
                && Boolean.TRUE.equals(((Map<?, ?>) all.get("power")).get("allPassed"));
        all.put("allPassed", pass);
        return all;
    }
}
