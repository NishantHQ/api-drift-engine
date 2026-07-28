package com.enterprise.apidrift.engine.rules;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Evaluates request-side (consumer-impact) breaking changes.
 *
 * Rules (as per BRD FR-3.1):
 *  - Adding mandatory param (query, header, path, cookie) → BREAKING
 *  - Adding optional param → NON-BREAKING
 *  - Adding required property to request body → BREAKING
 *  - Removing/relaxing constraint on request property → NON-BREAKING
 *  - Removing enum value from request property → BREAKING
 */
@Component
public class RequestRuleEvaluator implements BreakingRule {

    @Override
    public String name() {
        return "request-rule-evaluator";
    }

    @Override
    public String direction() {
        return "REQUEST";
    }

    @Override
    public List<DetectedChange> evaluate(String httpMethod, String endpointPath,
                                         JsonNode oldNode, JsonNode newNode, String jsonPointer) {
        List<DetectedChange> changes = new ArrayList<>();

        // Evaluate parameter changes
        changes.addAll(evaluateParameters(httpMethod, endpointPath, oldNode, newNode));

        // Evaluate request body changes
        changes.addAll(evaluateRequestBody(httpMethod, endpointPath, oldNode, newNode, jsonPointer));

        return changes;
    }

    private List<DetectedChange> evaluateParameters(String httpMethod, String endpointPath,
                                                     JsonNode oldNode, JsonNode newNode) {
        List<DetectedChange> results = new ArrayList<>();
        if (oldNode == null || newNode == null) return results;

        JsonNode oldParams = oldNode.get("parameters");
        JsonNode newParams = newNode.get("parameters");
        if (newParams == null) return results;

        Set<String> oldParamNames = paramNames(oldParams);
        Set<Map.Entry<String, Boolean>> newParamSet = paramSet(newParams);

        for (var entry : newParamSet) {
            String name = entry.getKey();
            boolean required = entry.getValue();
            if (!oldParamNames.contains(name)) {
                // New parameter added
                boolean breaking = required;
                results.add(DetectedChange.builder()
                        .changeType(breaking ? "MANDATORY_PARAM_ADDED" : "OPTIONAL_PARAM_ADDED")
                        .severity(breaking ? ChangeSeverity.HIGH : ChangeSeverity.INFO)
                        .direction("REQUEST")
                        .httpMethod(httpMethod)
                        .endpointPath(endpointPath)
                        .jsonPointer(jsonPointer + "/parameters/" + name)
                        .description(breaking
                                ? "New mandatory parameter '" + name + "' added — BREAKING"
                                : "New optional parameter '" + name + "' added — NON-BREAKING")
                        .isBreaking(breaking)
                        .build());
            }
        }

        // Detect removed parameters
        Set<String> newParamNames = paramNames(newParams);
        for (var entry : paramSet(oldParams)) {
            String name = entry.getKey();
            if (!newParamNames.contains(name)) {
                results.add(DetectedChange.builder()
                        .changeType("PARAM_REMOVED")
                        .severity(ChangeSeverity.HIGH)
                        .direction("REQUEST")
                        .httpMethod(httpMethod)
                        .endpointPath(endpointPath)
                        .jsonPointer(jsonPointer + "/parameters/" + name)
                        .description("Parameter '" + name + "' removed — BREAKING")
                        .isBreaking(true)
                        .build());
            }
        }

        return results;
    }

    private List<DetectedChange> evaluateRequestBody(String httpMethod, String endpointPath,
                                                      JsonNode oldNode, JsonNode newNode, String jsonPointer) {
        List<DetectedChange> results = new ArrayList<>();
        if (oldNode == null || newNode == null) return results;

        JsonNode oldBody = findRequestBody(oldNode);
        JsonNode newBody = findRequestBody(newNode);
        if (oldBody == null || newBody == null) return results;

        JsonNode oldSchema = resolveSchema(oldBody);
        JsonNode newSchema = resolveSchema(newBody);
        if (oldSchema == null || newSchema == null) return results;

        // Compare required properties
        Set<String> oldRequired = requiredProperties(oldSchema);
        Set<String> newRequired = requiredProperties(newSchema);
        JsonNode oldProps = oldSchema.get("properties");
        JsonNode newProps = newSchema.get("properties");

        if (newProps != null) {
            Iterator<String> fieldNames = newProps.fieldNames();
            while (fieldNames.hasNext()) {
                String propName = fieldNames.next();
                boolean wasPresent = oldProps != null && oldProps.has(propName);
                boolean nowRequired = newRequired.contains(propName);
                boolean wasRequired = oldRequired.contains(propName);

                if (!wasPresent) {
                    // New property
                    results.add(DetectedChange.builder()
                            .changeType(nowRequired ? "REQUIRED_PROPERTY_ADDED" : "OPTIONAL_PROPERTY_ADDED")
                            .severity(nowRequired ? ChangeSeverity.HIGH : ChangeSeverity.INFO)
                            .direction("REQUEST")
                            .httpMethod(httpMethod)
                            .endpointPath(endpointPath)
                            .jsonPointer(jsonPointer + "/requestBody/properties/" + propName)
                            .description(nowRequired
                                    ? "New required property '" + propName + "' added — BREAKING"
                                    : "New optional property '" + propName + "' added — NON-BREAKING")
                            .isBreaking(nowRequired)
                            .build());
                } else if (!wasRequired && nowRequired) {
                    results.add(DetectedChange.builder()
                            .changeType("PROPERTY_MADE_REQUIRED")
                            .severity(ChangeSeverity.HIGH)
                            .direction("REQUEST")
                            .httpMethod(httpMethod)
                            .endpointPath(endpointPath)
                            .jsonPointer(jsonPointer + "/requestBody/properties/" + propName)
                            .description("Property '" + propName + "' made required — BREAKING")
                            .isBreaking(true)
                            .build());
                }

                // Check enum value removal
                if (wasPresent && oldProps != null) {
                    results.addAll(checkEnumRemoval(httpMethod, endpointPath,
                            oldProps.get(propName), newProps.get(propName),
                            jsonPointer + "/requestBody/properties/" + propName));
                }
            }
        }

        return results;
    }

    private List<DetectedChange> checkEnumRemoval(String httpMethod, String endpointPath,
                                                   JsonNode oldProp, JsonNode newProp,
                                                   String pointer) {
        List<DetectedChange> results = new ArrayList<>();
        if (oldProp == null || newProp == null) return results;

        JsonNode oldEnum = oldProp.get("enum");
        JsonNode newEnum = newProp.get("enum");
        if (oldEnum == null || newEnum == null) return results;

        Set<String> oldValues = new HashSet<>();
        oldEnum.forEach(v -> oldValues.add(v.asText()));
        Set<String> newValues = new HashSet<>();
        newEnum.forEach(v -> newValues.add(v.asText()));

        for (String oldVal : oldValues) {
            if (!newValues.contains(oldVal)) {
                results.add(DetectedChange.builder()
                        .changeType("ENUM_VALUE_REMOVED")
                        .severity(ChangeSeverity.HIGH)
                        .direction("REQUEST")
                        .httpMethod(httpMethod)
                        .endpointPath(endpointPath)
                        .jsonPointer(pointer)
                        .description("Enum value '" + oldVal + "' removed — BREAKING")
                        .isBreaking(true)
                        .build());
            }
        }

        return results;
    }

    // --- Helper methods ---

    private Set<String> paramNames(JsonNode params) {
        Set<String> names = new HashSet<>();
        if (params == null || !params.isArray()) return names;
        for (JsonNode p : params) {
            if (p.has("name")) names.add(p.get("name").asText());
        }
        return names;
    }

    private Set<Map.Entry<String, Boolean>> paramSet(JsonNode params) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        if (params == null || !params.isArray()) return map.entrySet();
        for (JsonNode p : params) {
            if (p.has("name")) {
                map.put(p.get("name").asText(),
                        p.has("required") && p.get("required").asBoolean(false));
            }
        }
        return map.entrySet();
    }

    private JsonNode findRequestBody(JsonNode node) {
        return node.has("requestBody") ? node.get("requestBody") : null;
    }

    private JsonNode resolveSchema(JsonNode body) {
        JsonNode content = body.get("content");
        if (content == null) return body.get("schema");
        JsonNode jsonContent = content.get("application/json");
        if (jsonContent != null) return jsonContent.get("schema");
        // Try first content type
        Iterator<String> types = content.fieldNames();
        if (types.hasNext()) {
            return content.get(types.next()).get("schema");
        }
        return null;
    }

    private Set<String> requiredProperties(JsonNode schema) {
        Set<String> required = new HashSet<>();
        if (schema == null) return required;
        JsonNode req = schema.get("required");
        if (req != null && req.isArray()) {
            req.forEach(r -> required.add(r.asText()));
        }
        return required;
    }
}
