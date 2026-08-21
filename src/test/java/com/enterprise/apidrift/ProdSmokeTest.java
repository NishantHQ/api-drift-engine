package com.enterprise.apidrift;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test that boots the full application context against a real PostgreSQL
 * instance using the {@code prod} profile.
 *
 * <p>This exercises the production code path that the unit tests never touch
 * (the {@code test}/{@code h2} profiles disable Flyway and use {@code ddl-auto:
 * create-drop} on H2):
 * <ul>
 *   <li>{@code application-prod.yml} is parsed — catches YAML errors such as
 *       duplicate mapping keys that crash startup on Render.</li>
 *   <li>Flyway runs the real migrations in {@code db/migration}.</li>
 *   <li>Hibernate {@code ddl-auto=validate} checks entity/schema alignment.</li>
 * </ul>
 *
 * <p>It is disabled by default so {@code ./mvnw test} stays Postgres-free. The
 * dedicated {@code smoke-test-postgres} CI job enables it via
 * {@code -DrunIntegration=true} with a postgres:15 service container.
 */
@SpringBootTest
@ActiveProfiles("prod")
@EnabledIfSystemProperty(named = "runIntegration", matches = "true")
class ProdSmokeTest {

    @Test
    void contextLoadsWithFlywayMigratedPostgres() {
        // If the context loads, the prod YAML parsed, Flyway migrated, and
        // ddl-auto=validate passed against the real schema.
    }
}
