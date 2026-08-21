# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot 3.3 / Java 21 service that polls external vendors' OpenAPI specs, diffs them against the last known snapshot, classifies breaking changes, correlates them with internal-service telemetry, deduplicates alerts, and dispatches notifications to Jira/Slack/PagerDuty.

## Commands

Use `./mvnw` (Maven wrapper) — do not assume a system Maven install.

```bash
# Build (compiles + runs tests + packages)
./mvnw clean package

# Build skipping tests
./mvnw package -DskipTests

# Run all tests
./mvnw test

# Run a single test class / single test method
./mvnw test -Dtest=RequestRuleEvaluatorTest
./mvnw test -Dtest=RequestRuleEvaluatorTest#someTestMethod

# Run tests with JaCoCo coverage report
./mvnw test jacoco:report        # report at target/site/jacoco/index.html

# Run locally with H2 (no PostgreSQL needed; seeds mock telemetry data)
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2

# Run against PostgreSQL (default profile)
./mvnw spring-boot:run

# Run packaged jar
java -jar target/api-drift-engine-1.0.0.jar --spring.profiles.active=h2
```

There is no lint/format step — formatting is conventional (Lombok-based POJOs, 4-space indent). CI runs `compile`, `test`, `package -DskipTests`, then a SonarCloud scan (`verify sonar:sonar`).

## Spring profiles

| Profile | Data source | Flyway | Quartz | Notes |
|---------|-------------|--------|--------|-------|
| (default) | PostgreSQL | on, `baseline-on-migrate: true` | JDBC job store | requires `DB_USERNAME`/`DB_PASSWORD` |
| `h2` | H2 in-memory | **off** (`ddl-auto: create-drop`) | memory | dev only; `telemetry.seed-mock-data: true` |
| `prod` | PostgreSQL | on | memory (single instance) | requires `ADMIN_PASSWORD`; `ddl-auto: validate` |
| `test` | H2 in-memory | off | memory | used by `@ActiveProfiles("test")` in tests |

Config lives in `src/main/resources/application{,-h2,-prod}.yml` and `src/test/resources/application-test.yml`. Secrets are injected via env vars (see `.env.example`).

## Architecture

The service is a layered Spring app under `com.enterprise.apidrift`:

`controller` → `service` → `engine` → `repository` → `entity`, with `dto` for request/response payloads and `config` for wiring.

### The ingestion pipeline

`IngestionOrchestrator.runPipeline(VendorConfig)` is the heart of the system. Its 11 steps (also documented in `README.md`):

1. `EgressFetchService` fetches the remote spec (SSRF-guarded, retry + circuit breaker).
2. SHA-256 the raw spec; if unchanged vs. latest `SpecSnapshot`, halt as `NO_CHANGE_DETECTED`.
3. `OpenApiNormalizationService` parses/normalizes (swagger-parser, OpenAPI 3.0/3.1).
4. Persist the new snapshot.
5. `DirectionalCompatibilityEvaluator` produces a `List<DetectedChange>` (old vs. new).
6. `UsageCorrelationService` adjusts severity from the telemetry registry.
7. `FingerprintService` deduplicates against prior `change_fingerprints`.
8. `AlertDispatcherService` (async) dispatches only alertable changes.

`SpecPollingJob` (Quartz) calls `runForAllActiveVendors()` on a schedule; `DiffController` exposes the same path via HTTP for manual triggers.

### Breaking-change rules

`engine/rules/BreakingRule` is the interface; three evaluators implement it:
- `RequestRuleEvaluator` (FR-3.1) — request-side params/body changes
- `ResponseRuleEvaluator` (FR-3.2) — response payload property changes
- `WebhookRuleEvaluator` (FR-3.3) — webhook schema/event changes

`DirectionalCompatibilityEvaluator` also detects added/removed endpoints and HTTP methods itself. Full classification table is in `README.md`.

### Fingerprint dedup & state machine

Each change gets `SHA256(vendorId:endpointPath:httpMethod:changeType:jsonPointer)`. State flows `NEW → ACTIVE → RESOLVED` (plus an implicit `RESOLVED → ACTIVE` re-alert on re-detection). Alerts fire only on NEW or reactivated fingerprints; a fingerprint still present but inactive (auto-resolved because it stopped appearing) is silently revived. Manual resolution metadata lives on `change_fingerprints` (`resolved_by`, `resolution_notes`, `resolved_at`). See `FingerprintService.deduplicateAndFilter`.

### Severity adjustment

`engine/telemetry/UsageCorrelationService` + `TelemetryRegistry` (DB-backed via `service_dependencies`): a change consumed by an internal service within 30 days is escalated to CRITICAL; unconsumed changes are downgraded to LOW/INFO.

## Database & schema changes

Schema is managed by **Flyway migrations** in `src/main/resources/db/migration/` (`V1`–`V5`). In `prod` and the default profile, Hibernate runs `ddl-auto: validate`, so **entities do not create or alter tables — every schema change must be a new `Vn__*.sql` migration**. The H2 profile disables Flyway and uses `create-drop` instead, so it will not exercise migrations.

Tables: `vendor_configs`, `spec_snapshots` (JSONB raw spec), `diff_audit_runs`, `change_fingerprints`, `service_dependencies`, `audit_log`. JPA entities live in `entity/`, repositories in `repository/`.

## Auth & security

- Basic auth on `/api/v1/**`; username `admin`, password from `ADMIN_PASSWORD` (defaults to `admin` in dev/test, required in prod). See `SecurityConfig`.
- `/actuator/health` and `/actuator/info` are unauthenticated.
- Stateless, CSRF disabled. `RateLimitFilter` caps `/api/**` at 60 req/min per IP (in-memory).
- Outbound fetches are SSRF-guarded (`EgressFetchService` + `EgressProxyProperties`: blocked loopback/link-local/RFC-1918 subnets, timeouts, payload cap). Vendor auth tokens are AES-256-GCM encrypted at rest (`EncryptionService`).

## Conventions & gotchas

- **Lombok** is used heavily (`@Slf4j`, `@RequiredArgsConstructor`, `@Builder`, `@Data`). JaCoCo excludes `dto/`, `entity/`, `config/`, and the application class.
- `IngestionOrchestrator` uses a `@Lazy` self-injection (`self`) so `runPipeline`'s `@Transactional` applies when called from `runForAllActiveVendors` (same-class proxy bypass).
- Alert dispatch is `@Async("alertExecutor")`; the executor is defined in `AsyncConfig`.
- Outbound HTTP goes through Spring WebFlux `WebClient` (`WebClientConfig`), with Prometheus metrics (`diff.runs.total`, `diff.runs.duration`, `diff.changes.detected`, `alerts.dispatched.total`).
- Endpoints beyond the README's table: `ChangeFeedController` (`/api/v1/changes`, `/api/v1/changes/stats` — cross-vendor paginated feed) and an audit log (`audit_log` table, `AuditLogService`) recording admin actions. `VendorConfig` supports free-form `tags` (`TagListConverter`).
- Structured JSON logging via logstash-logback-encoder (`logback-spring.xml`).
