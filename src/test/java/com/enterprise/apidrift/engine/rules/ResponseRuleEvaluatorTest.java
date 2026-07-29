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
 * Unit tests for ResponseRuleEvaluator covering all FR-3.2 rules.
 */
class ResponseRuleEvaluatorTest {

    private ResponseRuleEvaluator evaluator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        evaluator = new ResponseRuleEvaluator();
        mapper = new ObjectMapper();
    }

    // ── Status code changes ─────────────────────────────────────

    @Test
    @DisplayName("Removing a response status code → BREAKING (HIGH)")
    void responseCodeRemovedIsBreaking() throws Exception {
        JsonNode oldOp = buildOpWithResponse("200", """
                { "type": "object", "properties": { "id": {"type": "integer"} } }
                """);
        JsonNode newOp = mapper.readTree("""
                { "responses": {} }
                """);  // responses key exists but empty — all codes removed

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/paths//users/get");

        assertThat(changes).hasSize(1);
        DetectedChange c = changes.get(0);
        assertThat(c.isBreaking()).isTrue();
        assertThat(c.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
        assertThat(c.getChangeType()).isEqualTo("RESPONSE_CODE_REMOVED");
        assertThat(c.getDirection()).isEqualTo("RESPONSE");
    }

    @Test
    @DisplayName("Adding a response status code → NON-BREAKING (INFO)")
    void responseCodeAddedIsNonBreaking() throws Exception {
        JsonNode oldOp = buildOpWithResponse("200", """
                { "type": "object", "properties": { "id": {"type": "integer"} } }
                """);
        JsonNode newOp = buildOpWithResponses("""
                { "200": {
                    "description": "OK",
                    "content": { "application/json": { "schema": {
                        "type": "object", "properties": { "id": {"type": "integer"} } } } }
                },
                  "201": {
                    "description": "Created",
                    "content": { "application/json": { "schema": {
                        "type": "object", "properties": { "id": {"type": "integer"} } } } }
                }}
                """);

        List<DetectedChange> changes = evaluator.evaluate("POST", "/users", oldOp, newOp, "#/paths//users/post");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("RESPONSE_CODE_ADDED");
        var added = changes.stream()
                .filter(c -> "RESPONSE_CODE_ADDED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(added.isBreaking()).isFalse();
        assertThat(added.getSeverity()).isEqualTo(ChangeSeverity.INFO);
    }

    // ── Property removal ────────────────────────────────────────

    @Test
    @DisplayName("Removing response property → BREAKING (HIGH)")
    void responsePropertyRemovedIsBreaking() throws Exception {
        JsonNode oldOp = buildOpWithResponse("200", """
                { "type": "object", "properties": {
                    "id": {"type": "integer"},
                    "email": {"type": "string"}
                }}
                """);
        JsonNode newOp = buildOpWithResponse("200", """
                { "type": "object", "properties": {
                    "id": {"type": "integer"}
                }}
                """);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/paths//users/get");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("RESPONSE_PROPERTY_REMOVED");
        var removed = changes.stream()
                .filter(c -> "RESPONSE_PROPERTY_REMOVED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(removed.isBreaking()).isTrue();
        assertThat(removed.getSeverity()).isEqualTo(ChangeSeverity.HIGH);
        assertThat(removed.getDescription()).contains("email");
    }

    // ── Property addition ───────────────────────────────────────

    @Test
    @DisplayName("Adding response property → NON-BREAKING (INFO)")
    void responsePropertyAddedIsNonBreaking() throws Exception {
        JsonNode oldOp = buildOpWithResponse("200", """
                { "type": "object", "properties": { "id": {"type": "integer"} } }
                """);
        JsonNode newOp = buildOpWithResponse("200", """
                { "type": "object", "properties": {
                    "id": {"type": "integer"},
                    "name": {"type": "string"}
                }}
                """);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/paths//users/get");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("RESPONSE_PROPERTY_ADDED");
        var added = changes.stream()
                .filter(c -> "RESPONSE_PROPERTY_ADDED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(added.isBreaking()).isFalse();
        assertThat(added.getSeverity()).isEqualTo(ChangeSeverity.INFO);
    }

    // ── Type change ─────────────────────────────────────────────

    @Test
    @DisplayName("Changing property type → BREAKING (CRITICAL)")
    void typeChangeIsBreaking() throws Exception {
        JsonNode oldOp = buildOpWithResponse("200", """
                { "type": "object", "properties": { "count": {"type": "integer"} } }
                """);
        JsonNode newOp = buildOpWithResponse("200", """
                { "type": "object", "properties": { "count": {"type": "string"} } }
                """);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/stats", oldOp, newOp, "#/paths//stats/get");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("PROPERTY_TYPE_CHANGED");
        var typeChange = changes.stream()
                .filter(c -> "PROPERTY_TYPE_CHANGED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(typeChange.isBreaking()).isTrue();
        assertThat(typeChange.getSeverity()).isEqualTo(ChangeSeverity.CRITICAL);
        assertThat(typeChange.getDescription()).contains("integer", "string");
    }

    // ── Enum in response ────────────────────────────────────────

    @Test
    @DisplayName("Adding enum value to response property → BREAKING (MEDIUM)")
    void responseEnumValueAddedIsBreaking() throws Exception {
        JsonNode oldOp = buildOpWithResponse("200", """
                { "type": "object",
                  "properties": { "role": {"type": "string", "enum": ["USER", "ADMIN"]} }
                }
                """);
        JsonNode newOp = buildOpWithResponse("200", """
                { "type": "object",
                  "properties": { "role": {"type": "string", "enum": ["USER", "ADMIN", "SUPERADMIN"]} }
                }
                """);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/paths//users/get");

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("RESPONSE_ENUM_VALUE_ADDED");
        var enumAdded = changes.stream()
                .filter(c -> "RESPONSE_ENUM_VALUE_ADDED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(enumAdded.isBreaking()).isTrue();
        assertThat(enumAdded.getSeverity()).isEqualTo(ChangeSeverity.MEDIUM);
        assertThat(enumAdded.getDescription()).contains("SUPERADMIN");
    }

    // ── No changes ──────────────────────────────────────────────

    @Test
    @DisplayName("Identical response specs produce no changes")
    void identicalResponsesNoChanges() throws Exception {
        JsonNode oldOp = buildOpWithResponse("200", """
                { "type": "object", "properties": { "id": {"type": "integer"} } }
                """);

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, oldOp, "#/paths//users/get");
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
    @DisplayName("No responses in either spec → no changes")
    void noResponsesNoChanges() throws Exception {
        JsonNode oldOp = mapper.readTree("{}");
        JsonNode newOp = mapper.readTree("{}");

        List<DetectedChange> changes = evaluator.evaluate("GET", "/users", oldOp, newOp, "#/x");
        assertThat(changes).isEmpty();
    }

    @Test
    @DisplayName("Name and direction metadata are correct")
    void metadataCorrect() {
        assertThat(evaluator.name()).isEqualTo("response-rule-evaluator");
        assertThat(evaluator.direction()).isEqualTo("RESPONSE");
    }

    // ── Helpers ─────────────────────────────────────────────────

    private JsonNode buildOpWithResponse(String statusCode, String schemaJson) throws Exception {
        String json = """
                { "responses": {
                    "%s": {
                        "description": "OK",
                        "content": { "application/json": { "schema": %s } }
                    }
                }}
                """.formatted(statusCode, schemaJson);
        return mapper.readTree(json);
    }

    private JsonNode buildOpWithResponses(String responsesJson) throws Exception {
        return mapper.readTree("{ \"responses\": " + responsesJson + " }");
    }
}
