package com.enterprise.apidrift.engine;

import com.enterprise.apidrift.entity.SpecSnapshot;
import com.enterprise.apidrift.repository.SpecSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Parses OpenAPI 3.0/3.1 specs (JSON/YAML), dereferences $ref components,
 * and normalizes schema variations into a canonical AST representation.
 *
 * Normalizations performed:
 *  - 3.0 nullable:true → 3.1 type: ["string", "null"]
 *  - exclusiveMinimum boolean → numeric value
 *  - Circular $ref loop detection
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiNormalizationService {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * Parses and normalizes a raw OpenAPI spec string (JSON or YAML).
     *
     * @param rawSpec the raw spec content
     * @return a normalized JsonNode representation
     */
    public JsonNode parseAndNormalize(String rawSpec) {
        try {
            // Determine if YAML or JSON
            String trimmed = rawSpec.trim();
            boolean isYaml = trimmed.startsWith("openapi:") || trimmed.startsWith("swagger:")
                    || (!trimmed.startsWith("{") && trimmed.contains("\n"));

            ObjectMapper mapper = isYaml ? yamlMapper : jsonMapper;
            JsonNode root = mapper.readTree(trimmed);

            // Detect OpenAPI version
            String version = detectVersion(root);

            // Normalize schema variations first (on the raw tree, before nodes
            // are shared by dereferencing, since normalization mutates in place).
            root = normalizeSchemas(root, version);

            // Dereference $ref pointers inline (memoized → shared DAG, read-only).
            root = dereferenceRefs(root, root);

            log.info("Parsed and normalized OpenAPI {} spec", version);
            return root;
        } catch (Exception e) {
            log.error("Failed to parse OpenAPI spec: {}", e.getMessage(), e);
            throw new RuntimeException("Spec parsing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Also parse using swagger-parser for validation.
     */
    public OpenAPI parseWithSwaggerParser(String rawSpec) {
        SwaggerParseResult result = new OpenAPIParser().readContents(rawSpec, null, null);
        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            log.warn("Swagger parser messages: {}", result.getMessages());
        }
        return result.getOpenAPI();
    }

    // --- Private helpers ---

    private String detectVersion(JsonNode root) {
        if (root.has("openapi")) {
            return root.get("openapi").asText();
        }
        if (root.has("swagger")) {
            return root.get("swagger").asText(); // Swagger 2.x
        }
        return "unknown";
    }

    /**
     * Recursively dereferences $ref pointers into a shared DAG.
     *
     * <p>{@code memo} caches each unique $ref path's resolved node so it is
     * expanded once and reused everywhere it is referenced — memory stays
     * ~O(unique schemas) instead of O(reference sites), which is what OOMs on
     * large specs like Stripe/GitHub.
     *
     * <p>A per-path {@code resolvingRefs} set detects circular $refs and leaves
     * them unresolved instead of inlining them. The depth counter is a secondary
     * safety net for pathological acyclic chains.
     */
    private JsonNode dereferenceRefs(JsonNode node, JsonNode root) {
        return dereferenceRefs(node, root, 0, new HashSet<>(), new HashMap<>());
    }

    private JsonNode dereferenceRefs(JsonNode node, JsonNode root, int depth,
                                     Set<String> resolvingRefs, Map<String, JsonNode> memo) {
        if (depth > 50) {
            log.warn("Max dereference depth reached; possible circular $ref");
            return node;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.has("$ref") && obj.size() == 1) {
                String refPath = obj.get("$ref").asText();
                if (memo.containsKey(refPath)) {
                    return memo.get(refPath);
                }
                if (resolvingRefs.contains(refPath)) {
                    // Circular $ref — leave it unresolved rather than recursing forever.
                    return node;
                }
                JsonNode resolved = resolveRef(refPath, root);
                if (resolved != null && !resolved.equals(node)) {
                    resolvingRefs.add(refPath);
                    try {
                        JsonNode deref = dereferenceRefs(resolved, root, depth + 1, resolvingRefs, memo);
                        memo.put(refPath, deref);
                        return deref;
                    } finally {
                        resolvingRefs.remove(refPath);
                    }
                }
            }
            ObjectNode result = jsonMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), dereferenceRefs(field.getValue(), root, depth, resolvingRefs, memo));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode result = jsonMapper.createArrayNode();
            for (JsonNode item : arr) {
                result.add(dereferenceRefs(item, root, depth, resolvingRefs, memo));
            }
            return result;
        }
        return node;
    }

    /**
     * Resolves a JSON $ref pointer like "#/components/schemas/Foo".
     */
    private JsonNode resolveRef(String refPath, JsonNode root) {
        if (refPath == null || !refPath.startsWith("#/")) {
            return null;
        }
        String[] segments = refPath.substring(2).split("/");
        JsonNode current = root;
        for (String segment : segments) {
            segment = segment.replace("~1", "/").replace("~0", "~");
            current = current.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Normalizes OpenAPI 3.0 schema quirks into a 3.1-compatible canonical form.
     */
    private JsonNode normalizeSchemas(JsonNode node, String version) {
        if (!node.isObject()) return node;

        ObjectNode obj = (ObjectNode) node;
        boolean is30x = version.startsWith("3.0");

        // Walk all "schema" objects recursively
        if (obj.has("schema")) {
            obj.set("schema", normalizeSchemaNode(obj.get("schema"), is30x));
        }
        if (obj.has("schemas")) {
            ObjectNode schemas = (ObjectNode) obj.get("schemas");
            Iterator<Map.Entry<String, JsonNode>> it = schemas.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                schemas.set(entry.getKey(), normalizeSchemaNode(entry.getValue(), is30x));
            }
        }

        // Recurse into all children
        Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value.isObject() || value.isArray()) {
                obj.set(field.getKey(), normalizeSchemasRecurse(value, is30x));
            }
        }

        return obj;
    }

    private JsonNode normalizeSchemasRecurse(JsonNode node, boolean is30x) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.has("type") || obj.has("properties") || obj.has("nullable")) {
                return normalizeSchemaNode(node, is30x);
            }
            return normalizeSchemas(node, is30x ? "3.0" : "3.1");
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode result = jsonMapper.createArrayNode();
            for (JsonNode item : arr) {
                result.add(normalizeSchemasRecurse(item, is30x));
            }
            return result;
        }
        return node;
    }

    /**
     * Normalizes a single schema node.
     * - 3.0 nullable:true → 3.1 type: ["string", "null"]
     * - exclusiveMinimum boolean → number
     * - exclusiveMaximum boolean → number
     */
    private JsonNode normalizeSchemaNode(JsonNode node, boolean is30x) {
        if (!node.isObject()) return node;
        ObjectNode schema = ((ObjectNode) node).deepCopy();

        // Normalize nullable: true → multi-type array for 3.0.x specs
        if (is30x && schema.has("nullable") && schema.get("nullable").asBoolean(false)) {
            String existingType = schema.has("type") ? schema.get("type").asText() : null;
            if (existingType != null && !existingType.equals("null")) {
                ArrayNode typeArray = jsonMapper.createArrayNode();
                typeArray.add(existingType);
                typeArray.add("null");
                schema.set("type", typeArray);
            }
            schema.remove("nullable");
        }

        // Normalize exclusiveMinimum: boolean → numeric
        if (schema.has("exclusiveMinimum") && schema.get("exclusiveMinimum").isBoolean()) {
            boolean isExclusive = schema.get("exclusiveMinimum").asBoolean();
            if (isExclusive && schema.has("minimum")) {
                schema.set("exclusiveMinimum", schema.get("minimum"));
            }
            if (!isExclusive) {
                schema.remove("exclusiveMinimum");
            }
        }

        // Normalize exclusiveMaximum: boolean → numeric
        if (schema.has("exclusiveMaximum") && schema.get("exclusiveMaximum").isBoolean()) {
            boolean isExclusive = schema.get("exclusiveMaximum").asBoolean();
            if (isExclusive && schema.has("maximum")) {
                schema.set("exclusiveMaximum", schema.get("maximum"));
            }
            if (!isExclusive) {
                schema.remove("exclusiveMaximum");
            }
        }

        return schema;
    }
}
