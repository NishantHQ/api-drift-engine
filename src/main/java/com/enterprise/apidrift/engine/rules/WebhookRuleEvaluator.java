package com.enterprise.apidrift.engine.rules;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Evaluates webhook/event breaking changes.
 *
 * Rules (as per BRD FR-3.3):
 *  - Modifying webhook payload schemas → BREAKING
 *  - Removing webhook event types → BREAKING
 */
@Component
public class WebhookRuleEvaluator implements BreakingRule {

    @Override
    public String name() {
        return "webhook-rule-evaluator";
    }

    @Override
    public String direction() {
        return "WEBHOOK";
    }

    @Override
    public List<DetectedChange> evaluate(String httpMethod, String endpointPath,
                                         JsonNode oldNode, JsonNode newNode, String jsonPointer) {
        List<DetectedChange> changes = new ArrayList<>();
        if (oldNode == null || newNode == null) return changes;

        // Webhooks in OpenAPI 3.1 are under the "webhooks" top-level key
        // Some vendors use x-webhooks or a dedicated webhooks section
        JsonNode oldWebhooks = findWebhooks(oldNode);
        JsonNode newWebhooks = findWebhooks(newNode);

        if (oldWebhooks == null && newWebhooks == null) return changes;
        if (oldWebhooks == null) oldWebhooks = JsonNodeFactory.instance.objectNode();
        if (newWebhooks == null) newWebhooks = JsonNodeFactory.instance.objectNode();

        // Compare webhook event names
        Set<String> oldEvents = fieldNames(oldWebhooks);
        Set<String> newEvents = fieldNames(newWebhooks);

        // Removed webhook events → BREAKING
        for (String event : oldEvents) {
            if (!newEvents.contains(event)) {
                changes.add(DetectedChange.builder()
                        .changeType("WEBHOOK_EVENT_REMOVED")
                        .severity(ChangeSeverity.CRITICAL)
                        .direction("WEBHOOK")
                        .httpMethod("POST")
                        .endpointPath(event)
                        .jsonPointer(jsonPointer + "/" + event)
                        .description("Webhook event '" + event + "' removed — BREAKING")
                        .isBreaking(true)
                        .build());
            }
        }

        // Modified webhook payload schemas
        for (String event : oldEvents) {
            if (newWebhooks.has(event)) {
                JsonNode oldPayload = resolveWebhookPayload(oldWebhooks.get(event));
                JsonNode newPayload = resolveWebhookPayload(newWebhooks.get(event));

                if (oldPayload != null && newPayload != null && !oldPayload.equals(newPayload)) {
                    changes.addAll(compareWebhookSchemas(oldPayload, newPayload, event, jsonPointer));
                }
            }
        }

        return changes;
    }

    private List<DetectedChange> compareWebhookSchemas(JsonNode oldSchema, JsonNode newSchema,
                                                        String eventName, String pointer) {
        List<DetectedChange> results = new ArrayList<>();
        JsonNode oldProps = oldSchema.get("properties");
        JsonNode newProps = newSchema.get("properties");

        Set<String> oldPropNames = propertyNames(oldProps);
        Set<String> newPropNames = propertyNames(newProps);

        // Required property changes
        Set<String> oldRequired = requiredProps(oldSchema);
        Set<String> newRequired = requiredProps(newSchema);

        for (String name : newRequired) {
            if (!oldRequired.contains(name) && oldPropNames.contains(name)) {
                results.add(DetectedChange.builder()
                        .changeType("WEBHOOK_PROPERTY_MADE_REQUIRED")
                        .severity(ChangeSeverity.HIGH)
                        .direction("WEBHOOK")
                        .httpMethod("POST")
                        .endpointPath(eventName)
                        .jsonPointer(pointer + "/" + eventName + "/properties/" + name)
                        .description("Webhook '" + eventName + "' property '" + name +
                                "' made required — BREAKING")
                        .isBreaking(true)
                        .build());
            }
        }

        // Removed properties
        for (String name : oldPropNames) {
            if (!newPropNames.contains(name)) {
                results.add(DetectedChange.builder()
                        .changeType("WEBHOOK_PROPERTY_REMOVED")
                        .severity(ChangeSeverity.HIGH)
                        .direction("WEBHOOK")
                        .httpMethod("POST")
                        .endpointPath(eventName)
                        .jsonPointer(pointer + "/" + eventName + "/properties/" + name)
                        .description("Webhook '" + eventName + "' property '" + name +
                                "' removed — BREAKING")
                        .isBreaking(true)
                        .build());
            }
        }

        // Type changes
        if (oldProps != null && newProps != null) {
            for (String name : oldPropNames) {
                if (newProps.has(name)) {
                    String oldType = typeOf(oldProps.get(name));
                    String newType = typeOf(newProps.get(name));
                    if (oldType != null && newType != null && !oldType.equals(newType)) {
                        results.add(DetectedChange.builder()
                                .changeType("WEBHOOK_PROPERTY_TYPE_CHANGED")
                                .severity(ChangeSeverity.CRITICAL)
                                .direction("WEBHOOK")
                                .httpMethod("POST")
                                .endpointPath(eventName)
                                .jsonPointer(pointer + "/" + eventName + "/properties/" + name)
                                .description("Webhook '" + eventName + "' property '" + name +
                                        "' type changed from " + oldType + " to " + newType +
                                        " — BREAKING")
                                .isBreaking(true)
                                .build());
                    }
                }
            }
        }

        return results;
    }

    private JsonNode findWebhooks(JsonNode node) {
        if (node.has("webhooks")) return node.get("webhooks");
        if (node.has("x-webhooks")) return node.get("x-webhooks");
        return null;
    }

    private JsonNode resolveWebhookPayload(JsonNode webhook) {
        if (webhook == null) return null;
        JsonNode requestBody = webhook.get("requestBody");
        if (requestBody != null) {
            JsonNode content = requestBody.get("content");
            if (content != null) {
                JsonNode json = content.get("application/json");
                if (json != null) return json.get("schema");
            }
        }
        // Some specs put schema directly under post.requestBody
        if (webhook.has("post")) {
            return resolveWebhookPayload(webhook.get("post"));
        }
        return null;
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        if (node == null) return names;
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) names.add(it.next());
        return names;
    }

    private Set<String> propertyNames(JsonNode props) {
        return fieldNames(props);
    }

    private Set<String> requiredProps(JsonNode schema) {
        Set<String> req = new HashSet<>();
        if (schema == null) return req;
        JsonNode r = schema.get("required");
        if (r != null && r.isArray()) {
            r.forEach(v -> req.add(v.asText()));
        }
        return req;
    }

    private String typeOf(JsonNode prop) {
        if (prop == null) return null;
        JsonNode type = prop.get("type");
        if (type == null) return null;
        return type.isArray() ? type.toString() : type.asText();
    }
}
