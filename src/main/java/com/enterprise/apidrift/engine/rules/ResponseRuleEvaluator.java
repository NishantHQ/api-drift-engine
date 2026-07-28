package com.enterprise.apidrift.engine.rules;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Evaluates response-side breaking changes.
 *
 * Rules (as per BRD FR-3.2):
 *  - Removing existing property from response → BREAKING
 *  - Adding new property to response → NON-BREAKING (tolerant reader)
 *  - Changing property data type → BREAKING
 *  - Adding new enum value to response property → BREAKING (strict deserializers)
 */
@Component
public class ResponseRuleEvaluator implements BreakingRule {

    @Override
    public String name() {
        return "response-rule-evaluator";
    }

    @Override
    public String direction() {
        return "RESPONSE";
    }

    @Override
    public List<DetectedChange> evaluate(String httpMethod, String endpointPath,
                                         JsonNode oldNode, JsonNode newNode, String jsonPointer) {
        List<DetectedChange> changes = new ArrayList<>();
        if (oldNode == null || newNode == null) return changes;

        // Get response schemas for each status code
        JsonNode oldResponses = oldNode.get("responses");
        JsonNode newResponses = newNode.get("responses");
        if (oldResponses == null || newResponses == null) return changes;

        // Compare each status code response
        Iterator<String> statusCodes = oldResponses.fieldNames();
        while (statusCodes.hasNext()) {
            String code = statusCodes.next();
            JsonNode newResp = newResponses.get(code);
            if (newResp == null) {
                // Response status code removed
                changes.add(DetectedChange.builder()
                        .changeType("RESPONSE_CODE_REMOVED")
                        .severity(ChangeSeverity.HIGH)
                        .direction("RESPONSE")
                        .httpMethod(httpMethod)
                        .endpointPath(endpointPath)
                        .jsonPointer(jsonPointer + "/responses/" + code)
                        .description("Response status code " + code + " removed — BREAKING")
                        .isBreaking(true)
                        .build());
                continue;
            }

            JsonNode oldSchema = responseSchema(oldResponses.get(code));
            JsonNode newSchema = responseSchema(newResp);
            if (oldSchema != null && newSchema != null) {
                changes.addAll(compareResponseSchemas(httpMethod, endpointPath,
                        oldSchema, newSchema, jsonPointer + "/responses/" + code));
            }
        }

        // Detect new response codes (NON-BREAKING)
        Iterator<String> newCodes = newResponses.fieldNames();
        while (newCodes.hasNext()) {
            String code = newCodes.next();
            if (!oldResponses.has(code)) {
                changes.add(DetectedChange.builder()
                        .changeType("RESPONSE_CODE_ADDED")
                        .severity(ChangeSeverity.INFO)
                        .direction("RESPONSE")
                        .httpMethod(httpMethod)
                        .endpointPath(endpointPath)
                        .jsonPointer(jsonPointer + "/responses/" + code)
                        .description("New response status code " + code + " added — NON-BREAKING")
                        .isBreaking(false)
                        .build());
            }
        }

        return changes;
    }

    private List<DetectedChange> compareResponseSchemas(String httpMethod, String endpointPath,
                                                         JsonNode oldSchema, JsonNode newSchema,
                                                         String pointer) {
        List<DetectedChange> results = new ArrayList<>();
        JsonNode oldProps = oldSchema.get("properties");
        JsonNode newProps = newSchema.get("properties");

        if (oldProps == null && newProps == null) return results;

        Set<String> oldPropNames = propertyNames(oldProps);
        Set<String> newPropNames = propertyNames(newProps);

        // Removed properties → BREAKING
        for (String name : oldPropNames) {
            if (!newPropNames.contains(name)) {
                results.add(DetectedChange.builder()
                        .changeType("RESPONSE_PROPERTY_REMOVED")
                        .severity(ChangeSeverity.HIGH)
                        .direction("RESPONSE")
                        .httpMethod(httpMethod)
                        .endpointPath(endpointPath)
                        .jsonPointer(pointer + "/properties/" + name)
                        .description("Response property '" + name + "' removed — BREAKING")
                        .isBreaking(true)
                        .build());
            }
        }

        // Added properties → NON-BREAKING
        for (String name : newPropNames) {
            if (!oldPropNames.contains(name)) {
                results.add(DetectedChange.builder()
                        .changeType("RESPONSE_PROPERTY_ADDED")
                        .severity(ChangeSeverity.INFO)
                        .direction("RESPONSE")
                        .httpMethod(httpMethod)
                        .endpointPath(endpointPath)
                        .jsonPointer(pointer + "/properties/" + name)
                        .description("Response property '" + name + "' added — NON-BREAKING (tolerant reader)")
                        .isBreaking(false)
                        .build());
            }
        }

        // Type changes and enum additions in shared properties
        if (oldProps != null && newProps != null) {
            for (String name : oldPropNames) {
                if (newProps.has(name)) {
                    JsonNode oldProp = oldProps.get(name);
                    JsonNode newProp = newProps.get(name);

                    // Type change → BREAKING
                    String oldType = oldProp.has("type") ? oldProp.get("type").asText() : null;
                    String newType = newProp.has("type") ? newProp.get("type").asText() : null;
                    if (oldType != null && newType != null && !oldType.equals(newType)) {
                        // Allow string → [string, null] (nullable normalization)
                        boolean isNullNormalization = newProp.get("type").isArray()
                                && newType.equals(oldType);
                        if (!isNullNormalization) {
                            results.add(DetectedChange.builder()
                                    .changeType("PROPERTY_TYPE_CHANGED")
                                    .severity(ChangeSeverity.CRITICAL)
                                    .direction("RESPONSE")
                                    .httpMethod(httpMethod)
                                    .endpointPath(endpointPath)
                                    .jsonPointer(pointer + "/properties/" + name)
                                    .description("Property '" + name + "' type changed from " +
                                            oldType + " to " + newType + " — BREAKING")
                                    .isBreaking(true)
                                    .build());
                        }
                    }

                    // New enum value added → BREAKING (strict deserializers)
                    JsonNode oldEnum = oldProp.get("enum");
                    JsonNode newEnum = newProp.get("enum");
                    if (oldEnum != null && newEnum != null) {
                        Set<String> oldValues = new HashSet<>();
                        oldEnum.forEach(v -> oldValues.add(v.asText()));
                        for (JsonNode v : newEnum) {
                            if (!oldValues.contains(v.asText())) {
                                results.add(DetectedChange.builder()
                                        .changeType("RESPONSE_ENUM_VALUE_ADDED")
                                        .severity(ChangeSeverity.MEDIUM)
                                        .direction("RESPONSE")
                                        .httpMethod(httpMethod)
                                        .endpointPath(endpointPath)
                                        .jsonPointer(pointer + "/properties/" + name)
                                        .description("New enum value '" + v.asText() +
                                                "' added to response property '" + name +
                                                "' — BREAKING (strict deserializer risk)")
                                        .isBreaking(true)
                                        .build());
                            }
                        }
                    }
                }
            }
        }

        return results;
    }

    private JsonNode responseSchema(JsonNode response) {
        if (response == null) return null;
        JsonNode content = response.get("content");
        if (content == null) return null;
        JsonNode jsonContent = content.get("application/json");
        if (jsonContent != null) return jsonContent.get("schema");
        // Try first content type
        Iterator<String> types = content.fieldNames();
        if (types.hasNext()) {
            return content.get(types.next()).get("schema");
        }
        return null;
    }

    private Set<String> propertyNames(JsonNode props) {
        Set<String> names = new HashSet<>();
        if (props == null) return names;
        Iterator<String> it = props.fieldNames();
        while (it.hasNext()) names.add(it.next());
        return names;
    }
}
