package com.enterprise.apidrift.engine;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.engine.rules.RequestRuleEvaluator;
import com.enterprise.apidrift.engine.rules.ResponseRuleEvaluator;
import com.enterprise.apidrift.engine.rules.WebhookRuleEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DirectionalCompatibilityEvaluator — endpoint-level diffing.
 */
class DirectionalCompatibilityEvaluatorTest {

    private DirectionalCompatibilityEvaluator evaluator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        evaluator = new DirectionalCompatibilityEvaluator(
                new RequestRuleEvaluator(),
                new ResponseRuleEvaluator(),
                new WebhookRuleEvaluator());
        mapper = new ObjectMapper();
    }

    // ── Endpoint removed ────────────────────────────────────────

    @Test
    @DisplayName("Removing an endpoint → BREAKING (CRITICAL)")
    void endpointRemovedIsCritical() throws Exception {
        JsonNode oldSpec = specWithPaths("""
                { "/users": { "get": { "responses": { "200": { "description": "OK" } } } } }
                """);
        JsonNode newSpec = specWithPaths("{}");

        List<DetectedChange> changes = evaluator.evaluate(oldSpec, newSpec);

        assertThat(changes).hasSize(1);
        DetectedChange c = changes.get(0);
        assertThat(c.isBreaking()).isTrue();
        assertThat(c.getChangeType()).isEqualTo("ENDPOINT_REMOVED");
        assertThat(c.getEndpointPath()).isEqualTo("/users");
        assertThat(c.getSeverity()).isEqualTo(com.enterprise.apidrift.entity.ChangeSeverity.CRITICAL);
    }

    // ── Endpoint added ──────────────────────────────────────────

    @Test
    @DisplayName("Adding an endpoint → NON-BREAKING (INFO)")
    void endpointAddedIsNonBreaking() throws Exception {
        JsonNode oldSpec = specWithPaths("{}");
        JsonNode newSpec = specWithPaths("""
                { "/orders": { "get": { "responses": { "200": { "description": "OK" } } } } }
                """);

        List<DetectedChange> changes = evaluator.evaluate(oldSpec, newSpec);

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("ENDPOINT_ADDED");
        var added = changes.stream()
                .filter(c -> "ENDPOINT_ADDED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(added.isBreaking()).isFalse();
        assertThat(added.getSeverity()).isEqualTo(com.enterprise.apidrift.entity.ChangeSeverity.INFO);
    }

    // ── Method removed from shared endpoint ─────────────────────

    @Test
    @DisplayName("Removing HTTP method from shared endpoint → BREAKING (CRITICAL)")
    void methodRemovedFromSharedEndpointIsCritical() throws Exception {
        JsonNode oldSpec = specWithPaths("""
                { "/items": {
                    "get": { "responses": { "200": { "description": "OK" } } },
                    "post": { "responses": { "201": { "description": "Created" } } }
                }}
                """);
        JsonNode newSpec = specWithPaths("""
                { "/items": {
                    "get": { "responses": { "200": { "description": "OK" } } }
                }}
                """);

        List<DetectedChange> changes = evaluator.evaluate(oldSpec, newSpec);

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("METHOD_REMOVED");
        var removed = changes.stream()
                .filter(c -> "METHOD_REMOVED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(removed.isBreaking()).isTrue();
        assertThat(removed.getHttpMethod()).isEqualTo("POST");
        assertThat(removed.getSeverity()).isEqualTo(com.enterprise.apidrift.entity.ChangeSeverity.CRITICAL);
    }

    // ── Method added to shared endpoint ─────────────────────────

    @Test
    @DisplayName("Adding HTTP method to shared endpoint → NON-BREAKING (INFO)")
    void methodAddedToSharedEndpointIsNonBreaking() throws Exception {
        JsonNode oldSpec = specWithPaths("""
                { "/items": {
                    "get": { "responses": { "200": { "description": "OK" } } }
                }}
                """);
        JsonNode newSpec = specWithPaths("""
                { "/items": {
                    "get": { "responses": { "200": { "description": "OK" } } },
                    "post": { "responses": { "201": { "description": "Created" } } }
                }}
                """);

        List<DetectedChange> changes = evaluator.evaluate(oldSpec, newSpec);

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("METHOD_ADDED");
        var added = changes.stream()
                .filter(c -> "METHOD_ADDED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(added.isBreaking()).isFalse();
        assertThat(added.getHttpMethod()).isEqualTo("POST");
    }

    // ── Changes within shared endpoint ──────────────────────────

    @Test
    @DisplayName("Shared endpoint with parameter changes is evaluated")
    void sharedEndpointWithParameterChanges() throws Exception {
        JsonNode oldSpec = specWithPaths("""
                { "/users": {
                    "get": { "parameters": [
                        {"name": "id", "in": "path", "required": true}
                    ], "responses": { "200": { "description": "OK" } } }
                }}
                """);
        JsonNode newSpec = specWithPaths("""
                { "/users": {
                    "get": { "parameters": [
                        {"name": "id", "in": "path", "required": true},
                        {"name": "token", "in": "header", "required": true}
                    ], "responses": { "200": { "description": "OK" } } }
                }}
                """);

        List<DetectedChange> changes = evaluator.evaluate(oldSpec, newSpec);

        assertThat(changes).extracting(DetectedChange::getChangeType)
                .contains("MANDATORY_PARAM_ADDED");
        var breaking = changes.stream()
                .filter(c -> "MANDATORY_PARAM_ADDED".equals(c.getChangeType()))
                .findFirst().orElseThrow();
        assertThat(breaking.isBreaking()).isTrue();
    }

    // ── Sorting ─────────────────────────────────────────────────

    @Test
    @DisplayName("Changes are sorted — breaking first, then by severity")
    void breakingChangesSortedFirst() throws Exception {
        // Old spec has endpoints that are removed (breaking) and new spec has added ones (non-breaking)
        JsonNode oldSpec = specWithPaths("""
                { "/removed": {
                    "get": { "responses": { "200": { "description": "OK" } } }
                }}
                """);
        JsonNode newSpec = specWithPaths("""
                { "/added": {
                    "get": { "responses": { "200": { "description": "OK" } } }
                }}
                """);

        List<DetectedChange> changes = evaluator.evaluate(oldSpec, newSpec);

        // First should be breaking (removed), then non-breaking (added)
        assertThat(changes.get(0).isBreaking()).isTrue();
        assertThat(changes.get(1).isBreaking()).isFalse();
    }

    // ── No specs ────────────────────────────────────────────────

    @Test
    @DisplayName("Identical specs produce no changes")
    void identicalSpecsNoChanges() throws Exception {
        JsonNode spec = specWithPaths("""
                { "/users": { "get": { "responses": { "200": { "description": "OK" } } } } }
                """);

        List<DetectedChange> changes = evaluator.evaluate(spec, spec);

        assertThat(changes).isEmpty();
    }

    @Test
    @DisplayName("Both specs with no paths produce no changes")
    void noPathsNoChanges() throws Exception {
        JsonNode oldSpec = mapper.readTree("""
                { "openapi": "3.1.0", "info": {"title": "API"} }
                """);
        JsonNode newSpec = mapper.readTree("""
                { "openapi": "3.1.0", "info": {"title": "API v2"} }
                """);

        List<DetectedChange> changes = evaluator.evaluate(oldSpec, newSpec);

        // Only webhook evaluation may produce changes; should be minimal
        List<DetectedChange> nonWebhook = changes.stream()
                .filter(c -> !"WEBHOOK".equals(c.getDirection()))
                .toList();
        assertThat(nonWebhook).isEmpty();
    }

    // ── Multiple endpoints ──────────────────────────────────────

    @Test
    @DisplayName("Multiple endpoint changes are all detected")
    void multipleEndpointChangesDetected() throws Exception {
        // Old: /a, /b, /shared(GET)
        // New: /b, /c, /shared(PUT)
        JsonNode oldSpec = mapper.readTree("""
                { "openapi": "3.1.0", "info": {"title": "API"},
                  "paths": {
                    "/a": { "get": { "responses": { "200": { "description": "OK" } } } },
                    "/b": { "get": { "responses": { "200": { "description": "OK" } } } },
                    "/shared": { "get": { "responses": { "200": { "description": "OK" } } } }
                  }
                }
                """);
        JsonNode newSpec = mapper.readTree("""
                { "openapi": "3.1.0", "info": {"title": "API"},
                  "paths": {
                    "/b": { "get": { "responses": { "200": { "description": "OK" } } } },
                    "/c": { "get": { "responses": { "200": { "description": "OK" } } } },
                    "/shared": { "put": { "responses": { "200": { "description": "OK" } } } }
                  }
                }
                """);

        List<DetectedChange> changes = evaluator.evaluate(oldSpec, newSpec);

        List<String> types = changes.stream()
                .map(DetectedChange::getChangeType)
                .toList();

        assertThat(types).contains("ENDPOINT_REMOVED");  // /a removed
        assertThat(types).contains("ENDPOINT_ADDED");    // /c added
        assertThat(types).contains("METHOD_REMOVED");     // GET from /shared
        assertThat(types).contains("METHOD_ADDED");       // PUT to /shared
    }

    // ── Helper ──────────────────────────────────────────────────

    private JsonNode specWithPaths(String pathsJson) throws Exception {
        return mapper.readTree("""
                { "openapi": "3.1.0", "info": {"title": "Test API"}, "paths": %s }
                """.formatted(pathsJson));
    }
}
