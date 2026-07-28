package com.enterprise.apidrift.engine.rules;

import com.enterprise.apidrift.dto.DetectedChange;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A single compatibility rule that evaluates a diff between old and new schema nodes.
 */
public interface BreakingRule {

    /** Human-readable name of this rule. */
    String name();

    /** Whether this rule applies to request-side, response-side, or webhook changes. */
    String direction();

    /**
     * Evaluate the rule and return any detected breaking or non-breaking changes.
     *
     * @param httpMethod the HTTP method of the endpoint context
     * @param endpointPath the endpoint path
     * @param oldNode the previous schema node, may be null
     * @param newNode the current schema node, may be null
     * @param jsonPointer the JSON Pointer path to this node
     * @return list of detected changes (empty if no change)
     */
    List<DetectedChange> evaluate(String httpMethod, String endpointPath,
                                  JsonNode oldNode, JsonNode newNode, String jsonPointer);
}
