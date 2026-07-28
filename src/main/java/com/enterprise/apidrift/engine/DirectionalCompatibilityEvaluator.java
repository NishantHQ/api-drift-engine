package com.enterprise.apidrift.engine;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.engine.rules.BreakingRule;
import com.enterprise.apidrift.engine.rules.RequestRuleEvaluator;
import com.enterprise.apidrift.engine.rules.ResponseRuleEvaluator;
import com.enterprise.apidrift.engine.rules.WebhookRuleEvaluator;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Executes directional compatibility evaluation by comparing old and new
 * OpenAPI specs and applying breaking/non-breaking rules from all evaluators.
 *
 * Evaluates: Request-side, Response-side, and Webhook changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectionalCompatibilityEvaluator {

    private final RequestRuleEvaluator requestRuleEvaluator;
    private final ResponseRuleEvaluator responseRuleEvaluator;
    private final WebhookRuleEvaluator webhookRuleEvaluator;

    /**
     * Compare two normalized spec trees and return all detected changes.
     *
     * @param oldSpec normalized old spec
     * @param newSpec normalized new spec
     * @return list of all detected changes
     */
    public List<DetectedChange> evaluate(JsonNode oldSpec, JsonNode newSpec) {
        List<DetectedChange> allChanges = new ArrayList<>();

        // Get all paths (endpoints) from old and new specs
        Map<String, JsonNode> oldPaths = extractPaths(oldSpec);
        Map<String, JsonNode> newPaths = extractPaths(newSpec);

        // Detect removed endpoints
        for (String path : oldPaths.keySet()) {
            if (!newPaths.containsKey(path)) {
                allChanges.addAll(detectAllMethodsRemoved(oldPaths.get(path), path));
            }
        }

        // Detect new endpoints (non-breaking)
        for (String path : newPaths.keySet()) {
            if (!oldPaths.containsKey(path)) {
                allChanges.addAll(detectAllMethodsAdded(newPaths.get(path), path));
            }
        }

        // Compare shared endpoints
        for (String path : oldPaths.keySet()) {
            if (newPaths.containsKey(path)) {
                JsonNode oldPathNode = oldPaths.get(path);
                JsonNode newPathNode = newPaths.get(path);
                allChanges.addAll(compareEndpoint(path, oldPathNode, newPathNode));
            }
        }

        // Evaluate webhooks
        allChanges.addAll(webhookRuleEvaluator.evaluate("POST", "/webhooks", oldSpec, newSpec, "#/webhooks"));

        // Sort: breaking first, then by severity
        allChanges.sort(Comparator
                .comparing(DetectedChange::isBreaking).reversed()
                .thenComparing(c -> c.getSeverity().ordinal()));

        log.info("Compatibility evaluation complete: {} total changes, {} breaking",
                allChanges.size(), allChanges.stream().filter(DetectedChange::isBreaking).count());

        return allChanges;
    }

    // --- Private helpers ---

    private Map<String, JsonNode> extractPaths(JsonNode spec) {
        Map<String, JsonNode> paths = new LinkedHashMap<>();
        JsonNode pathsNode = spec.get("paths");
        if (pathsNode == null) return paths;
        Iterator<Map.Entry<String, JsonNode>> it = pathsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            paths.put(entry.getKey(), entry.getValue());
        }
        return paths;
    }

    private List<DetectedChange> compareEndpoint(String path, JsonNode oldOp, JsonNode newOp) {
        List<DetectedChange> changes = new ArrayList<>();
        Set<String> methods = new HashSet<>(Arrays.asList(
                "get", "post", "put", "delete", "patch", "options", "head"));

        for (String method : methods) {
            JsonNode oldMethod = oldOp.get(method);
            JsonNode newMethod = newOp.get(method);

            if (oldMethod == null && newMethod != null) {
                changes.add(DetectedChange.builder()
                        .changeType("METHOD_ADDED")
                        .severity(ChangeSeverity.INFO)
                        .direction("REQUEST")
                        .httpMethod(method.toUpperCase())
                        .endpointPath(path)
                        .jsonPointer("#/paths/" + path + "/" + method)
                        .description("New method " + method.toUpperCase() + " added to " + path + " — NON-BREAKING")
                        .isBreaking(false)
                        .build());
                continue;
            }
            if (oldMethod != null && newMethod == null) {
                changes.add(DetectedChange.builder()
                        .changeType("METHOD_REMOVED")
                        .severity(ChangeSeverity.CRITICAL)
                        .direction("REQUEST")
                        .httpMethod(method.toUpperCase())
                        .endpointPath(path)
                        .jsonPointer("#/paths/" + path + "/" + method)
                        .description("Method " + method.toUpperCase() + " removed from " + path + " — BREAKING")
                        .isBreaking(true)
                        .build());
                continue;
            }
            if (oldMethod != null && newMethod != null) {
                String jsonPointer = "#/paths/" + path + "/" + method;
                changes.addAll(requestRuleEvaluator.evaluate(
                        method.toUpperCase(), path, oldMethod, newMethod, jsonPointer));
                changes.addAll(responseRuleEvaluator.evaluate(
                        method.toUpperCase(), path, oldMethod, newMethod, jsonPointer));
            }
        }

        return changes;
    }

    private List<DetectedChange> detectAllMethodsRemoved(JsonNode ops, String path) {
        List<DetectedChange> changes = new ArrayList<>();
        Iterator<String> methods = ops.fieldNames();
        while (methods.hasNext()) {
            String method = methods.next();
            changes.add(DetectedChange.builder()
                    .changeType("ENDPOINT_REMOVED")
                    .severity(ChangeSeverity.CRITICAL)
                    .direction("REQUEST")
                    .httpMethod(method.toUpperCase())
                    .endpointPath(path)
                    .jsonPointer("#/paths/" + path)
                    .description("Entire endpoint " + method.toUpperCase() + " " + path + " removed — BREAKING")
                    .isBreaking(true)
                    .build());
        }
        return changes;
    }

    private List<DetectedChange> detectAllMethodsAdded(JsonNode ops, String path) {
        List<DetectedChange> changes = new ArrayList<>();
        Iterator<String> methods = ops.fieldNames();
        while (methods.hasNext()) {
            String method = methods.next();
            changes.add(DetectedChange.builder()
                    .changeType("ENDPOINT_ADDED")
                    .severity(ChangeSeverity.INFO)
                    .direction("REQUEST")
                    .httpMethod(method.toUpperCase())
                    .endpointPath(path)
                    .jsonPointer("#/paths/" + path)
                    .description("New endpoint " + method.toUpperCase() + " " + path + " added — NON-BREAKING")
                    .isBreaking(false)
                    .build());
        }
        return changes;
    }
}
