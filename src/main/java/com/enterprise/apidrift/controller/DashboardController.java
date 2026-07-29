package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.DashboardResponse;
import com.enterprise.apidrift.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations dashboard for high-level drift visibility.
 * Endpoint: /api/v1/dashboard
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Get the operations dashboard summary.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> getDashboard() {
        log.info("GET /api/v1/dashboard");
        return ResponseEntity.ok(dashboardService.buildDashboard());
    }
}
