package com.enterprise.apidrift.service;

import com.enterprise.apidrift.dto.AlertPayload;
import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.enterprise.apidrift.entity.VendorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Dispatches structured alerts to Jira, Slack, and PagerDuty.
 *
 * BRD FR-6:
 *  - FR-6.1: Jira auto-ticket creation for CRITICAL/HIGH changes
 *  - FR-6.2: Webhook/notification routing to Slack, Teams, PagerDuty, Kafka
 */
@Slf4j
@Service
public class AlertDispatcherService {

    private final WebClient webClient;

    @Value("${alerts.jira.enabled:false}")
    private boolean jiraEnabled;

    @Value("${alerts.jira.base-url:}")
    private String jiraBaseUrl;

    @Value("${alerts.jira.project-key:APIDRIFT}")
    private String jiraProjectKey;

    @Value("${alerts.jira.auth-token:}")
    private String jiraAuthToken;

    @Value("${alerts.slack.enabled:false}")
    private boolean slackEnabled;

    @Value("${alerts.slack.webhook-url:}")
    private String slackWebhookUrl;

    @Value("${alerts.pagerduty.enabled:false}")
    private boolean pagerDutyEnabled;

    @Value("${alerts.pagerduty.routing-key:}")
    private String pagerDutyRoutingKey;

    public AlertDispatcherService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * Dispatch alerts for the given changes. Async to avoid blocking the diff pipeline.
     */
    @Async
    public void dispatchAlerts(VendorConfig vendor, List<DetectedChange> alertableChanges) {
        if (alertableChanges.isEmpty()) {
            log.info("No alertable changes for vendor {}", vendor.getVendorName());
            return;
        }

        log.info("Dispatching {} alerts for vendor {}", alertableChanges.size(), vendor.getVendorName());

        for (DetectedChange change : alertableChanges) {
            AlertPayload payload = buildAlertPayload(vendor, change);

            if (change.getSeverity() == ChangeSeverity.CRITICAL
                    || change.getSeverity() == ChangeSeverity.HIGH) {
                dispatchJira(payload);
                dispatchPagerDuty(payload);
            }

            dispatchSlack(payload);
        }
    }

    private AlertPayload buildAlertPayload(VendorConfig vendor, DetectedChange change) {
        return AlertPayload.builder()
                .vendorName(vendor.getVendorName())
                .vendorId(vendor.getId())
                .changeType(change.getChangeType())
                .severity(change.getSeverity().name())
                .direction(change.getDirection())
                .httpMethod(change.getHttpMethod())
                .endpointPath(change.getEndpointPath())
                .jsonPointer(change.getJsonPointer())
                .description(change.getDescription())
                .consumingService(change.getConsumingService())
                .fingerprintHash(change.getFingerprintHash())
                .isBreaking(change.isBreaking())
                .build();
    }

    // --- Jira Integration (FR-6.1) ---

    private void dispatchJira(AlertPayload payload) {
        if (!jiraEnabled || jiraBaseUrl.isBlank()) {
            log.debug("Jira disabled; skipping ticket for {}", payload.getFingerprintHash());
            return;
        }

        try {
            String summary = String.format("[API-DRIFT] %s — %s %s %s",
                    payload.getSeverity(),
                    payload.getHttpMethod(),
                    payload.getEndpointPath(),
                    payload.getChangeType());

            String description = buildJiraDescription(payload);

            String requestBody = String.format(
                    "{\"fields\":{\"project\":{\"key\":\"%s\"},\"summary\":\"%s\","
                            + "\"description\":\"%s\",\"issuetype\":{\"name\":\"Bug\"},"
                            + "\"labels\":[\"api-drift\",\"%s\"]}}",
                    jiraProjectKey,
                    escapeJson(summary),
                    escapeJson(description),
                    escapeJson(payload.getSeverity().toLowerCase()));

            webClient.post()
                    .uri(jiraBaseUrl + "/rest/api/2/issue")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + jiraAuthToken)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(requestBody)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Jira ticket created: {}", summary);
        } catch (Exception e) {
            log.error("Failed to create Jira ticket: {}", e.getMessage());
        }
    }

    private String buildJiraDescription(AlertPayload payload) {
        return String.format(
                "h2. API Drift Detected\n"
                        + "*Vendor:* %s\n"
                        + "*Endpoint:* %s %s\n"
                        + "*JSON Pointer:* %s\n"
                        + "*Severity:* %s\n"
                        + "*Change Type:* %s\n"
                        + "*Direction:* %s\n\n"
                        + "h3. Description\n%s\n\n"
                        + "h3. Impact\n"
                        + "*Consuming Service(s):* %s\n"
                        + "*Breaking:* %s\n"
                        + "*Fingerprint:* %s",
                payload.getVendorName(),
                payload.getHttpMethod(), payload.getEndpointPath(),
                payload.getJsonPointer(),
                payload.getSeverity(),
                payload.getChangeType(),
                payload.getDirection(),
                payload.getDescription(),
                payload.getConsumingService() != null ? payload.getConsumingService() : "None",
                payload.isBreaking() ? "Yes" : "No",
                payload.getFingerprintHash());
    }

    // --- Slack Integration (FR-6.2) ---

    private void dispatchSlack(AlertPayload payload) {
        if (!slackEnabled || slackWebhookUrl.isBlank()) {
            log.debug("Slack disabled; skipping notification for {}", payload.getFingerprintHash());
            return;
        }

        try {
            String color = switch (payload.getSeverity()) {
                case "CRITICAL" -> "#FF0000";
                case "HIGH" -> "#FFA500";
                case "MEDIUM" -> "#FFD700";
                default -> "#36A64F";
            };

            String slackPayload = String.format(
                    "{\"attachments\":[{\"color\":\"%s\",\"title\":\"API Drift: %s\","
                            + "\"fields\":["
                            + "{\"title\":\"Vendor\",\"value\":\"%s\",\"short\":true},"
                            + "{\"title\":\"Severity\",\"value\":\"%s\",\"short\":true},"
                            + "{\"title\":\"Endpoint\",\"value\":\"%s %s\",\"short\":true},"
                            + "{\"title\":\"Change\",\"value\":\"%s\",\"short\":true},"
                            + "{\"title\":\"Impact\",\"value\":\"%s\",\"short\":false},"
                            + "{\"title\":\"Fingerprint\",\"value\":\"%s\",\"short\":false}"
                            + "]}]}",
                    color, payload.getChangeType(),
                    payload.getVendorName(), payload.getSeverity(),
                    payload.getHttpMethod(), payload.getEndpointPath(),
                    payload.getDescription(),
                    payload.isBreaking() ? "BREAKING — immediate attention required"
                            : "Non-breaking — informational",
                    payload.getFingerprintHash());

            webClient.post()
                    .uri(slackWebhookUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(slackPayload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Slack notification sent for {}", payload.getFingerprintHash());
        } catch (Exception e) {
            log.error("Failed to send Slack notification: {}", e.getMessage());
        }
    }

    // --- PagerDuty Integration ---

    private void dispatchPagerDuty(AlertPayload payload) {
        if (!pagerDutyEnabled || pagerDutyRoutingKey.isBlank()) {
            log.debug("PagerDuty disabled; skipping page for {}", payload.getFingerprintHash());
            return;
        }

        try {
            String pdPayload = String.format(
                    "{\"routing_key\":\"%s\",\"event_action\":\"trigger\","
                            + "\"payload\":{\"summary\":\"%s\",\"source\":\"api-drift-engine\","
                            + "\"severity\":\"%s\",\"component\":\"%s\","
                            + "\"custom_details\":{\"vendor\":\"%s\",\"endpoint\":\"%s %s\","
                            + "\"change_type\":\"%s\",\"description\":\"%s\"}}}",
                    pagerDutyRoutingKey,
                    "API Drift: " + payload.getChangeType() + " on " + payload.getVendorName(),
                    payload.getSeverity().toLowerCase(),
                    payload.getEndpointPath(),
                    payload.getVendorName(),
                    payload.getHttpMethod(), payload.getEndpointPath(),
                    payload.getChangeType(),
                    escapeJson(payload.getDescription()));

            webClient.post()
                    .uri("https://events.pagerduty.com/v2/enqueue")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(pdPayload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("PagerDuty incident triggered for {}", payload.getFingerprintHash());
        } catch (Exception e) {
            log.error("Failed to trigger PagerDuty incident: {}", e.getMessage());
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
