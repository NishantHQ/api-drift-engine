package com.enterprise.apidrift.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiNormalizationServiceTest {

    private final OpenApiNormalizationService service = new OpenApiNormalizationService();

    @Test
    void resolvesSimpleRefs() {
        String spec = """
                openapi: 3.0.0
                info: { title: T, version: 1.0.0 }
                paths: {}
                components:
                  schemas:
                    Pet:
                      type: object
                      properties:
                        name: { type: string }
                """;

        JsonNode result = service.parseAndNormalize(spec);

        assertNotNull(result);
        JsonNode pet = result.path("components").path("schemas").path("Pet");
        assertEquals("object", pet.path("type").asText());
        assertEquals("string", pet.path("properties").path("name").path("type").asText());
    }

    @Test
    void circularRefsDoNotExplode() {
        // Two self-references (parent + children.items) previously expanded
        // exponentially to ~2^50 nodes and OOM'd the JVM.
        String spec = """
                openapi: 3.0.0
                info: { title: T, version: 1.0.0 }
                paths: {}
                components:
                  schemas:
                    Node:
                      type: object
                      properties:
                        parent:
                          $ref: '#/components/schemas/Node'
                        children:
                          type: array
                          items:
                            $ref: '#/components/schemas/Node'
                """;

        JsonNode result = service.parseAndNormalize(spec);

        assertNotNull(result);
        // With cycle detection the dereferenced tree stays small and bounded;
        // without it this count would be astronomically large.
        assertTrue(countNodes(result) < 1000,
                "expected bounded dereferenced tree, got " + countNodes(result) + " nodes");
    }

    @Test
    void reusesResolvedSchemasAcrossReferences() {
        // The same schema referenced from two endpoints should be a single shared
        // node (memoized), not two inlined copies — this is what keeps large specs
        // from blowing up memory.
        String spec = """
                openapi: 3.0.0
                info: { title: T, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Pet' }
                  /b:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Pet' }
                components:
                  schemas:
                    Pet:
                      type: object
                      properties:
                        name: { type: string }
                """;

        JsonNode result = service.parseAndNormalize(spec);

        JsonNode petRefA = result.path("paths").path("/a").path("get").path("responses")
                .path("200").path("content").path("application/json").path("schema");
        JsonNode petRefB = result.path("paths").path("/b").path("get").path("responses")
                .path("200").path("content").path("application/json").path("schema");

        assertSame(petRefA, petRefB, "both $ref sites should share the same memoized node");
        assertEquals("object", petRefA.path("type").asText());
    }

    private int countNodes(JsonNode node) {
        int count = 1;
        for (JsonNode child : node) {
            count += countNodes(child);
        }
        return count;
    }
}
