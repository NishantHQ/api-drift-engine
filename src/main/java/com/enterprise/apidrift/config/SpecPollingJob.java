package com.enterprise.apidrift.config;

import com.enterprise.apidrift.service.IngestionOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * Quartz job that triggers the full ingestion → diff → alert pipeline.
 * Scheduled per-vendor via cron expressions in vendor_configs.
 *
 * This global job runs for ALL active vendors on each execution.
 * For per-vendor scheduling, individual Quartz triggers can be registered
 * dynamically via the VendorController when vendors are added.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecPollingJob extends QuartzJobBean {

    private final IngestionOrchestrator orchestrator;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        log.info("SpecPollingJob triggered at {}", context.getFireTime());
        try {
            orchestrator.runForAllActiveVendors();
        } catch (Exception e) {
            log.error("SpecPollingJob failed: {}", e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
