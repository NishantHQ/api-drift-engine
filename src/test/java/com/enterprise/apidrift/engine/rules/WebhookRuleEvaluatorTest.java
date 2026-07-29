package com.enterprise.apidrift.engine.rules;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookRuleEvaluatorTest {

    private WebhookRuleEvaluator evaluator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        evaluator = new WebhookRuleEvaluator();
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Name and direction metadata are correct")
    void metadata() {
        assertThat(evaluator.name()).isEqualTo("webhook-rule-evaluator");
        assertThat(evaluator.direction()).isEqualTo("WEBHOOK");
    }

    @Test
    @DisplayName("No webhooks in either spec → no changes")
    void noWebhooksNoChanges() throws Exception {
        JsonNode spec = mapper.readTree("""
                { "openapi": "3.1.0", "info": {"title": "API"} }
                """);
        List<DetectedChange> changes = evaluator.evaluate("POST", "/webhooks", spec, spec, "#/webhooks");
        assertThat(changes).isEmpty();
    }

    @Test
    @DisplayName("X-webhooks key is also checked")
    void xWebhooksChecked() throws Exception {
        JsonNode oldSpec = mapper.readTree("""
                { "x-webhooks": {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": { "type": "object", "properties": {
                            "orderId": {"type": "string"}
                        }}}
                    }}}}
                }}
                """);
        JsonNode newSpec = mapper.readTree("""
                { "x-webhooks": {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": { "type": "object", "properties": {
                            "orderId": {"type": "integer"}
                        }}}
                    }}}}
                }}
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/webhooks", oldSpec, newSpec, "#/x-webhooks");

        assertThat(changes).isNotEmpty();
        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("WEBHOOK_PROPERTY_TYPE_CHANGED");
    }

    @Test
    @DisplayName("Webhook event removed → BREAKING (CRITICAL)")
    void webhookEventRemoved() throws Exception {
        JsonNode oldSpec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": { "type": "object", "properties": {} }}
                    }}}},
                    "order.updated": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": { "type": "object", "properties": {} }}
                    }}}}
                }
                """);
        JsonNode newSpec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": { "type": "object", "properties": {} }}
                    }}}}
                }
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/webhooks", oldSpec, newSpec, "#/webhooks");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("WEBHOOK_EVENT_REMOVED");
        var removed = changes.stream()
                .filter(c -> "WEBHOOK_EVENT_REMOVED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(removed.isBreaking()).isTrue();
        assertThat(removed.getSeverity()).isEqualTo(ChangeSeverity.CRITICAL);
    }

    @Test
    @DisplayName("Webhook property made required → BREAKING (HIGH)")
    void webhookPropertyMadeRequired() throws Exception {
        JsonNode oldSpec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": {
                            "type": "object",
                            "properties": { "orderId": {"type": "string"} },
                            "required": []
                        }}
                    }}}}
                }
                """);
        JsonNode newSpec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": {
                            "type": "object",
                            "properties": { "orderId": {"type": "string"} },
                            "required": ["orderId"]
                        }}
                    }}}}
                }
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/webhooks", oldSpec, newSpec, "#/webhooks");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("WEBHOOK_PROPERTY_MADE_REQUIRED");
        var madeReq = changes.stream()
                .filter(c -> "WEBHOOK_PROPERTY_MADE_REQUIRED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(madeReq.isBreaking()).isTrue();
        assertThat(madeReq.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
    }

    @Test
    @DisplayName("Webhook property removed → BREAKING (HIGH)")
    void webhookPropertyRemoved() throws Exception {
        JsonNode oldSpec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": {
                            "type": "object",
                            "properties": { "orderId": {"type": "string"}, "amount": {"type": "number"} }
                        }}
                    }}}}
                }
                """);
        JsonNode newSpec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": {
                            "type": "object",
                            "properties": { "orderId": {"type": "string"} }
                        }}
                    }}}}
                }
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/webhooks", oldSpec, newSpec, "#/webhooks");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("WEBHOOK_PROPERTY_REMOVED");
        var removed = changes.stream()
                .filter(c -> "WEBHOOK_PROPERTY_REMOVED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(removed.isBreaking()).isTrue();
        assertThat(removed.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
    }

    @Test
    @DisplayName("Webhook property type changed → BREAKING (CRITICAL)")
    void webhookPropertyTypeChanged() throws Exception {
        JsonNode oldSpec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": {
                            "type": "object",
                            "properties": { "orderId": {"type": "string"} }
                        }}
                    }}}}
                }
                """);
        JsonNode newSpec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": {
                            "type": "object",
                            "properties": { "orderId": {"type": "integer"} }
                        }}
                    }}}}
                }
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/webhooks", oldSpec, newSpec, "#/webhooks");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("WEBHOOK_PROPERTY_TYPE_CHANGED");
        var typeChanged = changes.stream()
                .filter(c -> "WEBHOOK_PROPERTY_TYPE_CHANGED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(typeChanged.isBreaking()).isTrue();
        assertThat(typeChanged.getSeverity()).isEqualTo(ChangeSeverity.CRITICAL);
    }

    @Test
    @DisplayName("Identical webhooks produce no changes")
    void identicalWebhooksNoChanges() throws Exception {
        JsonNode spec = specWithWebhooks("""
                {
                    "order.created": { "post": { "requestBody": { "content": {
                        "application/json": { "schema": {
                            "type": "object",
                            "properties": { "orderId": {"type": "string"} }
                        }}
                    }}}}
                }
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/webhooks", spec, spec, "#/webhooks");
        assertThat(changes).isEmpty();
    }

    // --- helper ---

    private JsonNode specWithWebhooks(String webhooksJson) throws Exception {
        return mapper.readTree("""
                { "openapi": "3.1.0", "webhooks": %s }
                """.formatted(webhooksJson));
    }
}
