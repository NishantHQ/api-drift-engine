package com.enterprise.apidrift.config;

import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzConfig {

    @Bean
    public JobDetail specPollingJobDetail() {
        return JobBuilder.newJob(SpecPollingJob.class)
                .withIdentity("specPollingJob")
                .withDescription("Polls vendor OpenAPI specs and runs diff pipeline")
                .storeDurably()
                .build();
    }

    /**
     * Default trigger: every hour.
     * Per-vendor cron schedules are managed dynamically via the VendorController
     * or can override this global trigger.
     */
    @Bean
    public Trigger specPollingTrigger() {
        return TriggerBuilder.newTrigger()
                .forJob(specPollingJobDetail())
                .withIdentity("specPollingTrigger")
                .withDescription("Hourly spec polling trigger")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?"))
                .build();
    }
}
