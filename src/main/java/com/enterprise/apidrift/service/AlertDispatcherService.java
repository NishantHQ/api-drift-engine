package com.enterprise.apidrift.service;

import com.enterprise.apidrift.dto.AlertPayload;
import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.enterprise.apidrift.entity.VendorConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    private final MeterRegistry meterRegistry;

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

    @Value("${alerts.batch-mode:true}")
    private boolean batchMode;

    @Value("${alerts.ui-base-url:http://localhost:8080}")
    private String uiBaseUrl;

    public AlertDispatcherService(WebClient.Builder webClientBuilder, MeterRegistry meterRegistry) {
        this.webClient = webClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Dispatch alerts for the given changes. Async to avoid blocking the diff pipeline.
     * In batch mode, all changes are aggregated into a single notification per channel.
     */
    @Async("alertExecutor")
    public void dispatchAlerts(VendorConfig vendor, List<DetectedChange> alertableChanges) {
        if (alertableChanges.isEmpty()) {
            log.info("No alertable changes for vendor {}", vendor.getVendorName());
            return;
        }

        log.info("Dispatching {} alerts for vendor {} (batchMode={})",
                alertableChanges.size(), vendor.getVendorName(), batchMode);

        if (batchMode) {
            dispatchBatch(vendor, alertableChanges);
        } else {
            dispatchIndividually(vendor, alertableChanges);
        }

        // Record metrics
        for (DetectedChange change : alertableChanges) {
            Counter.builder("alerts.dispatched.total")
                    .tag("vendor", vendor.getVendorName())
                    .tag("severity", change.getSeverity().name())
                    .tag("channel", "aggregated")
                    .register(meterRegistry)
                    .increment();
        }
    }

    // --- Individual dispatch (legacy mode) ---

    private void dispatchIndividually(VendorConfig vendor, List<DetectedChange> alertableChanges) {
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

    // --- Batch dispatch ---

    private void dispatchBatch(VendorConfig vendor, List<DetectedChange> alertableChanges) {
        List<AlertPayload> payloads = alertableChanges.stream()
                .map(c -> buildAlertPayload(vendor, c))
                .toList();

        boolean hasHighOrCritical = alertableChanges.stream()
                .anyMatch(c -> c.getSeverity() == ChangeSeverity.CRITICAL
                        || c.getSeverity() == ChangeSeverity.HIGH);

        if (hasHighOrCritical) {
            dispatchJiraBatch(vendor, payloads);
        }

        // PagerDuty still fires per-CRITICAL change for immediate attention
        for (AlertPayload payload : payloads) {
            if ("CRITICAL".equals(payload.getSeverity())) {
                dispatchPagerDuty(payload);
            }
        }

        dispatchSlackBatch(vendor, payloads);
    }

    // --- Jira Batch ---

    private void dispatchJiraBatch(VendorConfig vendor, List<AlertPayload> payloads) {
        if (!jiraEnabled || jiraBaseUrl.isBlank()) {
            log.debug("Jira disabled; skipping batch ticket for vendor {}", vendor.getVendorName());
            return;
        }

        try {
            long breaking = payloads.stream().filter(AlertPayload::isBreaking).count();
            long critical = payloads.stream().filter(p -> "CRITICAL".equals(p.getSeverity())).count();

            String summary = String.format("[API-DRIFT] %s — %d changes (%d breaking, %d critical)",
                    vendor.getVendorName(), payloads.size(), breaking, critical);

            String description = buildJiraBatchDescription(vendor, payloads);

            String requestBody = String.format(
                    "{\"fields\":{\"project\":{\"key\":\"%s\"},\"summary\":\"%s\","
                            + "\"description\":\"%s\",\"issuetype\":{\"name\":\"Bug\"},"
                            + "\"labels\":[\"api-drift\",\"batch\",\"%s\"]}}",
                    jiraProjectKey,
                    escapeJson(summary),
                    escapeJson(description),
                    escapeJson(vendor.getVendorName().toLowerCase()));

            webClient.post()
                    .uri(jiraBaseUrl + "/rest/api/2/issue")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + jiraAuthToken)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(requestBody)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Jira batch ticket created: {} ({} changes)", summary, payloads.size());
        } catch (Exception e) {
            log.error("Failed to create Jira batch ticket: {}", e.getMessage());
        }
    }

    private String buildJiraBatchDescription(VendorConfig vendor, List<AlertPayload> payloads) {
        StringBuilder sb = new StringBuilder();
        sb.append("h2. API Drift Detected — Batch Summary\n");
        sb.append("*Vendor:* ").append(vendor.getVendorName()).append("\n");
        sb.append("*Total Changes:* ").append(payloads.size()).append("\n\n");

        sb.append("h3. Changes\n");
        sb.append("|| Severity || Endpoint || Change Type || Breaking || Description ||\n");

        for (AlertPayload p : payloads) {
            sb.append("| ").append(p.getSeverity())
                    .append(" | ").append(p.getHttpMethod()).append(" ").append(p.getEndpointPath())
                    .append(" | ").append(p.getChangeType())
                    .append(" | ").append(p.isBreaking() ? "Yes" : "No")
                    .append(" | ").append(p.getDescription())
                    .append(" |\n");
        }

        sb.append("\nh3. Impacted Services\n");
        List<String> services = payloads.stream()
                .map(AlertPayload::getConsumingService)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        sb.append(services.isEmpty() ? "None registered" : String.join(", ", services));

        return sb.toString();
    }

    // --- Slack Batch ---

    private void dispatchSlackBatch(VendorConfig vendor, List<AlertPayload> payloads) {
        if (!slackEnabled || slackWebhookUrl.isBlank()) {
            log.debug("Slack disabled; skipping batch notification for vendor {}", vendor.getVendorName());
            return;
        }

        try {
            long breaking = payloads.stream().filter(AlertPayload::isBreaking).count();
            long critical = payloads.stream().filter(p -> "CRITICAL".equals(p.getSeverity())).count();

            String color = critical > 0 ? "#FF0000" : breaking > 0 ? "#FFA500" : "#36A64F";

            StringBuilder fieldsJson = new StringBuilder();
            for (AlertPayload p : payloads) {
                String severityEmoji = switch (p.getSeverity()) {
                    case "CRITICAL" -> "🔴";
                    case "HIGH" -> "🟠";
                    case "MEDIUM" -> "🟡";
                    default -> "🟢";
                };
                fieldsJson.append(String.format(
                        "{\"title\":\"%s %s %s\",\"value\":\"%s\",\"short\":false},",
                        severityEmoji,
                        p.getHttpMethod(), p.getEndpointPath(),
                        escapeJson(p.getDescription())));
            }
            // Remove trailing comma
            if (fieldsJson.length() > 0 && fieldsJson.charAt(fieldsJson.length() - 1) == ',') {
                fieldsJson.setLength(fieldsJson.length() - 1);
            }

            String slackPayload = String.format(
                    "{\"attachments\":[{\"color\":\"%s\","
                            + "\"title\":\"API Drift: %s — %d changes (%d breaking)\","
                            + "\"text\":\"%s\\n<%s|View in API Drift Engine>\","
                            + "\"fields\":[%s],"
                            + "\"footer\":\"API Drift Engine — Batch Alert\"}]}",
                    color,
                    vendor.getVendorName(), payloads.size(), breaking,
                    "The following drift was detected in the latest spec poll:",
                    uiBaseUrl + "/api/v1/diffs/active/" + vendor.getId(),
                    fieldsJson.toString());

            webClient.post()
                    .uri(slackWebhookUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(slackPayload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Slack batch notification sent for vendor {} ({} changes)", vendor.getVendorName(), payloads.size());
        } catch (Exception e) {
            log.error("Failed to send Slack batch notification: {}", e.getMessage());
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
