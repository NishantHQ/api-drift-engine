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

/**
 * Unit tests for RequestRuleEvaluator covering all FR-3.1 rules.
 */
class RequestRuleEvaluatorTest {

    private RequestRuleEvaluator evaluator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        evaluator = new RequestRuleEvaluator();
        mapper = new ObjectMapper();
    }

    // ── Parameter addition ──────────────────────────────────────

    @Test
    @DisplayName("Adding mandatory param → BREAKING (HIGH)")
    void mandatoryParamAddedIsBreaking() throws Exception {
        JsonNode oldOp = mapper.readTree("""
                { "parameters": [ {"name": "id", "in": "path", "required": true} ] }
                """);
        JsonNode newOp = mapper.readTree("""
                { "parameters": [
                    {"name": "id", "in": "path", "required": true},
                    {"name": "x-api-key", "in": "header", "required": true}
                ]}
                """);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/paths//users/get");

        assertThat(changes).hasSize(1);
        DetectedChange c = changes.get(0);
        assertThat(c.isBreaking()).isTrue();
        assertThat(c.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
        assertThat(c.getChangeType()).isEqualTo("MANDATORY_PARAM_ADDED");
        assertThat(c.getDirection()).isEqualTo("REQUEST");
    }

    @Test
    @DisplayName("Adding optional param → NON-BREAKING (INFO)")
    void optionalParamAddedIsNonBreaking() throws Exception {
        JsonNode oldOp = mapper.readTree("""
                { "parameters": [ {"name": "id", "in": "path", "required": true} ] }
                """);
        JsonNode newOp = mapper.readTree("""
                { "parameters": [
                    {"name": "id", "in": "path", "required": true},
                    {"name": "page", "in": "query", "required": false}
                ]}
                """);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/paths//users/get");

        assertThat(changes).hasSize(1);
        DetectedChange c = changes.get(0);
        assertThat(c.isBreaking()).isFalse();
        assertThat(c.getSeverity()).isEqualTo(ChangeSeverity.INFO);
        assertThat(c.getChangeType()).isEqualTo("OPTIONAL_PARAM_ADDED");
    }

    // ── Parameter removal ───────────────────────────────────────

    @Test
    @DisplayName("Removing a parameter → BREAKING (HIGH)")
    void paramRemovedIsBreaking() throws Exception {
        JsonNode oldOp = mapper.readTree("""
                { "parameters": [
                    {"name": "id", "in": "path", "required": true},
                    {"name": "filter", "in": "query", "required": false}
                ]}
                """);
        JsonNode newOp = mapper.readTree("""
                { "parameters": [ {"name": "id", "in": "path", "required": true} ] }
                """);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/paths//users/get");

        assertThat(changes).hasSize(1);
        DetectedChange c = changes.get(0);
        assertThat(c.isBreaking()).isTrue();
        assertThat(c.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
        assertThat(c.getChangeType()).isEqualTo("PARAM_REMOVED");
    }

    // ── Request body properties ─────────────────────────────────

    @Test
    @DisplayName("New required property in request body → BREAKING (HIGH)")
    void requiredPropertyAddedIsBreaking() throws Exception {
        JsonNode oldOp = buildOpWithRequestBody("""
                { "type": "object", "properties": { "name": {"type": "string"} }, "required": ["name"] }
                """);
        JsonNode newOp = buildOpWithRequestBody("""
                { "type": "object",
                  "properties": { "name": {"type": "string"}, "email": {"type": "string"} },
                  "required": ["name", "email"] }
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/users", oldOp, newOp, "#/paths//users/post");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("REQUIRED_PROPERTY_ADDED");
        var reqProp = changes.stream()
                .filter(c -> "REQUIRED_PROPERTY_ADDED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(reqProp.isBreaking()).isTrue();
        assertThat(reqProp.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
    }

    @Test
    @DisplayName("New optional property in request body → NON-BREAKING (INFO)")
    void optionalPropertyAddedIsNonBreaking() throws Exception {
        JsonNode oldOp = buildOpWithRequestBody("""
                { "type": "object", "properties": { "name": {"type": "string"} }, "required": ["name"] }
                """);
        JsonNode newOp = buildOpWithRequestBody("""
                { "type": "object",
                  "properties": { "name": {"type": "string"}, "nickname": {"type": "string"} },
                  "required": ["name"] }
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/users", oldOp, newOp, "#/paths//users/post");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("OPTIONAL_PROPERTY_ADDED");
        var optProp = changes.stream()
                .filter(c -> "OPTIONAL_PROPERTY_ADDED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(optProp.isBreaking()).isFalse();
        assertThat(optProp.getSeverity()).isEqualTo(ChangeSeverity.INFO);
    }

    @Test
    @DisplayName("Property made required → BREAKING (HIGH)")
    void propertyMadeRequiredIsBreaking() throws Exception {
        JsonNode oldOp = buildOpWithRequestBody("""
                { "type": "object",
                  "properties": { "name": {"type": "string"}, "email": {"type": "string"} },
                  "required": ["name"] }
                """);
        JsonNode newOp = buildOpWithRequestBody("""
                { "type": "object",
                  "properties": { "name": {"type": "string"}, "email": {"type": "string"} },
                  "required": ["name", "email"] }
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/users", oldOp, newOp, "#/paths//users/post");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("PROPERTY_MADE_REQUIRED");
        var madeReq = changes.stream()
                .filter(c -> "PROPERTY_MADE_REQUIRED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(madeReq.isBreaking()).isTrue();
        assertThat(madeReq.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
    }

    // ── Enum removal ────────────────────────────────────────────

    @Test
    @DisplayName("Removing enum value from request property → BREAKING (HIGH)")
    void enumValueRemovedIsBreaking() throws Exception {
        JsonNode oldOp = buildOpWithRequestBody("""
                { "type": "object",
                  "properties": { "status": {"type": "string", "enum": ["NEW", "OPEN", "CLOSED"]} },
                  "required": ["status"] }
                """);
        JsonNode newOp = buildOpWithRequestBody("""
                { "type": "object",
                  "properties": { "status": {"type": "string", "enum": ["NEW", "OPEN"]} },
                  "required": ["status"] }
                """);

        List<DetectedChange> changes = evaluator.evaluate("PUT", "/tickets", oldOp, newOp, "#/paths//tickets/put");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("ENUM_VALUE_REMOVED");
        var enumRemoval = changes.stream()
                .filter(c -> "ENUM_VALUE_REMOVED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(enumRemoval.isBreaking()).isTrue();
        assertThat(enumRemoval.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
        assertThat(enumRemoval.getDescription()).contains("CLOSED");
    }

    @Test
    @DisplayName("Adding enum value to request property → no change detected")
    void enumValueAddedNotDetectedByRequestRules() throws Exception {
        JsonNode oldOp = buildOpWithRequestBody("""
                { "type": "object",
                  "properties": { "status": {"type": "string", "enum": ["NEW", "OPEN"]} },
                  "required": ["status"] }
                """);
        JsonNode newOp = buildOpWithRequestBody("""
                { "type": "object",
                  "properties": { "status": {"type": "string", "enum": ["NEW", "OPEN", "CLOSED"]} },
                  "required": ["status"] }
                """);

        List<DetectedChange> changes = evaluator.evaluate("PUT", "/tickets", oldOp, newOp, "#/paths//tickets/put");

        // No change detected — the rule only checks OLD enum values not in NEW
        assertThat(changes).filteredOn(c -> "ENUM_VALUE_REMOVED".equals(c.getChangeType())).isEmpty();
    }

    // ── No changes ──────────────────────────────────────────────

    @Test
    @DisplayName("Identical specs produce no changes")
    void identicalSpecsNoChanges() throws Exception {
        String json = """
                { "parameters": [ {"name": "id", "in": "path", "required": true} ] }
                """;
        JsonNode node = mapper.readTree(json);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", node, node, "#/paths//users/get");

        assertThat(changes).isEmpty();
    }

    // ── Edge cases ──────────────────────────────────────────────

    @Test
    @DisplayName("Null nodes produce empty result")
    void nullNodesReturnEmpty() {
        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", null, null, "#/x");
        assertThat(changes).isEmpty();
    }

    @Test
    @DisplayName("No parameters in either spec → no changes")
    void noParametersNoChanges() throws Exception {
        JsonNode oldOp = mapper.readTree("{}");
        JsonNode newOp = mapper.readTree("{}");

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/x");
        assertThat(changes).isEmpty();
    }

    @Test
    @DisplayName("Name and direction metadata are correct")
    void metadataCorrect() {
        assertThat(evaluator.name()).isEqualTo("request-rule-evaluator");
        assertThat(evaluator.direction()).isEqualTo("REQUEST");
    }

    // ── Helper ──────────────────────────────────────────────────

    private JsonNode buildOpWithRequestBody(String schemaJson) throws Exception {
        String json = """
                { "requestBody": {
                    "content": {
                        "application/json": { "schema": %s }
                    }
                }}
                """.formatted(schemaJson);
        return mapper.readTree(json);
    }
}
