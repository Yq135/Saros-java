package com.kairon.saros.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 与阶段二 /api/health 对齐（前端探活用）：{"status": "ok", "version": <阶段二 app.version>}。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "version", "0.2.0");
    }
}
