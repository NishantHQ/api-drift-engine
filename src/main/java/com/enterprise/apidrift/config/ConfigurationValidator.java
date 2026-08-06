package com.enterprise.apidrift.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Validates critical configuration at startup.
 * Fails fast with a clear error message if required settings are missing or insecure.
 */
@Slf4j
@Component
public class ConfigurationValidator {

    // Default credential values that should be overridden in production.
    // These are used for comparison only, not as actual credentials.
    private static final String DEFAULT_DB_PASSWORD = "apidrift"; // NOSONAR
    private static final String DEFAULT_ADMIN_PASSWORD = "admin"; // NOSONAR

    @Value("${encryption.aes-key}")
    private String aesKey;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${spring.security.user.password:}")
    private String adminPassword;

    @Value("${alerts.slack.enabled:false}")
    private boolean slackEnabled;

    @Value("${alerts.jira.enabled:false}")
    private boolean jiraEnabled;

    @Value("${alerts.pagerduty.enabled:false}")
    private boolean pagerDutyEnabled;

    @PostConstruct
    public void validate() {
        boolean isProd = activeProfiles.contains("prod");

        log.info("=== Configuration Validation ===");
        log.info("Active profiles: {}", activeProfiles);
        log.info("Alert channels — Slack: {}, Jira: {}, PagerDuty: {}",
                slackEnabled, jiraEnabled, pagerDutyEnabled);

        validateAesKey(isProd);
        validateDatabasePassword(isProd);
        validateAdminPassword(isProd);
        warnMissingAlertChannels();

        log.info("=== Configuration validation passed ===");
    }

    private void validateAesKey(boolean isProd) {
        byte[] keyBytes = aesKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            String msg = String.format(
                    "AES_ENCRYPTION_KEY must be at least 32 bytes (currently %d bytes). "
                    + "Set AES_ENCRYPTION_KEY env var.",
                    keyBytes.length);
            if (isProd) {
                log.error(msg);
                throw new IllegalStateException(msg);
            } else {
                log.warn(msg);
            }
        } else {
            log.info("AES encryption key: {} bytes (valid)", keyBytes.length);
        }
    }

    @SuppressWarnings("java:S2068")
    private void validateDatabasePassword(boolean isProd) {
        if (dbPassword == null || dbPassword.isBlank()) {
            String msg = "DB_PASSWORD is not set. Set DB_PASSWORD env var.";
            if (isProd) {
                log.error(msg);
                throw new IllegalStateException(msg);
            } else {
                log.warn(msg);
            }
        } else if (DEFAULT_DB_PASSWORD.equals(dbPassword) && isProd) {
            log.warn("DB_PASSWORD is set to default '{}' — change this in production.", DEFAULT_DB_PASSWORD);
        } else {
            log.info("Database password: configured");
        }
    }

    @SuppressWarnings("java:S2068")
    private void validateAdminPassword(boolean isProd) {
        if (adminPassword == null || adminPassword.isBlank()) {
            String msg = "ADMIN_PASSWORD is not set. Set ADMIN_PASSWORD env var.";
            if (isProd) {
                log.error(msg);
                throw new IllegalStateException(msg);
            } else {
                log.warn(msg);
            }
        } else if (DEFAULT_ADMIN_PASSWORD.equals(adminPassword) && isProd) {
            log.warn("ADMIN_PASSWORD is set to default '{}' — change this in production.", DEFAULT_ADMIN_PASSWORD);
        } else {
            log.info("Admin password: configured");
        }
    }

    private void warnMissingAlertChannels() {
        if (!slackEnabled && !jiraEnabled && !pagerDutyEnabled) {
            log.warn("No alert channels enabled (Slack, Jira, PagerDuty are all disabled). "
                    + "Breaking changes will be detected but NOT dispatched to any channel.");
        }
    }
}
