# Enterprise Outbound API Drift & Vendor Risk Engine

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/projects/spring-boot)
[![CI](https://github.com/NishantHQ/api-drift-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/NishantHQ/api-drift-engine/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)
[![Deploy](https://img.shields.io/badge/deploy-Render-46e3b7)](https://api-drift-engine.onrender.com/actuator/health)

An **active outbound vendor risk engine** that polls external vendor OpenAPI specifications, executes directional compatibility diffs, correlates breaking changes against internal service telemetry, deduplicates alerts, and automates remediation.

**Live demo:** https://api-drift-engine.onrender.com

---

## Architecture

```
[ Scheduled Cron / Webhook Trigger ]
              │
              ▼
┌──────────────────────────────┐
│   Ingestion Worker Service   │  ◄── (Egress Proxy / SSRF Guard)
└──────────────┬───────────────┘
               │  Downloads Raw Spec
               ▼
┌──────────────────────────────┐
│   SHA-256 Hashing & Cache    │──► (If Unchanged: Halt Execution)
└──────────────┬───────────────┘
               │  If Changed
               ▼
┌──────────────────────────────┐
│   OpenAPI 3.0/3.1 Parser     │
│   & Dereferencing Engine     │
└──────────────┬───────────────┘
               │  Normalized AST
               ▼
┌──────────────────────────────┐
│  Directional Compatibility   │
│     & Rule Evaluation        │
└──────────────┬───────────────┘
               │  Raw Detected Changes
               ▼
┌──────────────────────────────┐
│   Usage Correlation Engine   │  ◄── (Telemetry Registry)
└──────────────┬───────────────┘
               │  Impact-Weighted Diffs
               ▼
┌──────────────────────────────┐
│  Fingerprint Deduplication   │
│     & Alert Dispatcher       │──► [ Jira ] [ Slack ] [ PagerDuty ]
└──────────────────────────────┘
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21 |
| Framework | Spring Boot 3.3 |
| Database | PostgreSQL 15 (JSONB), H2 for dev/test |
| Migrations | Flyway |
| Scheduling | Quartz |
| HTTP Client | WebClient (WebFlux) |
| Spec Parsing | swagger-parser 2.1.18, openapi-diff-core 2.1.7 |
| Encryption | AES-256-GCM |
| Build | Maven |
| CI/CD | GitHub Actions + Render |
| Code Quality | SonarCloud + JaCoCo |

## Project Structure

```
src/main/java/com/enterprise/apidrift/
├── ApiDriftApplication.java          # Entry point
├── config/
│   ├── AsyncConfig.java              # Thread pools for async + alerts
│   ├── EgressProxyProperties.java    # SSRF blocklist, timeouts, payload caps
│   ├── QuartzConfig.java             # Cron-scheduled polling job
│   ├── SecurityConfig.java           # Basic auth on /api/v1/**
│   ├── SpecPollingJob.java           # Quartz job → IngestionOrchestrator
│   └── WebClientConfig.java          # WebClient with timeouts/limits
├── controller/
│   ├── VendorController.java         # CRUD /api/v1/vendors + health
│   ├── DiffController.java           # /api/v1/diffs/trigger, /history, /active, /resolve
│   ├── DashboardController.java      # /api/v1/dashboard
│   ├── TelemetryController.java      # /api/v1/telemetry/register, /dependencies
│   └── GlobalExceptionHandler.java   # Clean error responses
├── dto/
│   ├── AlertPayload.java             # Jira/Slack/PagerDuty payload
│   ├── DashboardResponse.java        # Dashboard aggregation response
│   ├── DetectedChange.java           # Individual diff result
│   ├── DiffTriggerResponse.java      # API response for diff runs
│   ├── ServiceDependency{Request,Response}.java
│   ├── VendorConfig{Request,Response}.java
│   └── ResolveRequest.java           # Manual resolution request
├── engine/
│   ├── EgressFetchService.java       # SSRF-guarded HTTP fetcher + retry/circuit
│   ├── OpenApiNormalizationService.java  # 3.0/3.1 parser + normalization
│   ├── DirectionalCompatibilityEvaluator.java
│   ├── FingerprintService.java       # SHA-256 dedup + state machine
│   ├── rules/
│   │   ├── BreakingRule.java         # Interface
│   │   ├── RequestRuleEvaluator.java  # FR-3.1
│   │   ├── ResponseRuleEvaluator.java # FR-3.2
│   │   └── WebhookRuleEvaluator.java  # FR-3.3
│   └── telemetry/
│       ├── TelemetryRegistry.java    # DB-backed consumer-usage registry
│       └── UsageCorrelationService.java  # Severity adjustment
├── entity/
│   ├── VendorConfig.java
│   ├── SpecSnapshot.java
│   ├── DiffAuditRun.java
│   ├── ChangeFingerprint.java
│   ├── ServiceDependency.java        # Service-to-vendor dependency mapping
│   ├── ChangeSeverity.java           # CRITICAL, HIGH, MEDIUM, LOW, INFO
│   ├── RunStatus.java                # SUCCESS, FAILURE, NO_CHANGE_DETECTED, IN_PROGRESS
│   └── VendorHealthStatus.java       # HEALTHY, DEGRADED, DOWN
├── repository/                       # Spring Data JPA repos × 5
└── service/
    ├── IngestionOrchestrator.java    # Full pipeline orchestrator
    ├── AlertDispatcherService.java   # Jira + Slack + PagerDuty (batch mode)
    ├── DashboardService.java         # Operations dashboard aggregation
    ├── EncryptionService.java        # AES-256-GCM token encryption
    └── VendorHealthService.java      # Circuit breaker for spec fetching
```

## Database Schema

| Table | Purpose |
|-------|---------|
| `vendor_configs` | Vendor registration, spec URLs, cron schedules, auth tokens |
| `spec_snapshots` | Immutable OpenAPI spec snapshots (JSONB + SHA-256 hash) |
| `diff_audit_runs` | Audit trail for each diff execution |
| `change_fingerprints` | Deduplicated change items with state tracking (NEW → ACTIVE → RESOLVED) and resolution metadata |
| `service_dependencies` | Internal service → vendor API dependency mappings for telemetry correlation |

## REST API

### Vendors

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/vendors` | List all registered vendors |
| `GET` | `/api/v1/vendors/{id}` | Get vendor by ID |
| `POST` | `/api/v1/vendors` | Register a new vendor |
| `PUT` | `/api/v1/vendors/{id}` | Update vendor configuration |
| `DELETE` | `/api/v1/vendors/{id}` | Remove a vendor |
| `GET` | `/api/v1/vendors/{id}/health` | Circuit-breaker health status |
| `POST` | `/api/v1/vendors/{id}/health/reset` | Reset circuit breaker |

### Diffs

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/diffs/trigger/{vendorId}` | Manually trigger a diff run |
| `GET` | `/api/v1/diffs/history/{vendorId}` | Get audit run history for a vendor |
| `GET` | `/api/v1/diffs/active/{vendorId}` | Get active (unresolved) breaking changes |
| `POST` | `/api/v1/diffs/resolve/{fingerprintId}` | Manually resolve a change |

### Telemetry

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/telemetry/register` | Register a service→vendor dependency |
| `GET` | `/api/v1/telemetry/dependencies` | List dependencies (filter: `?vendorId=&serviceName=`) |
| `GET` | `/api/v1/telemetry/services/{vendorId}` | Services consuming a vendor |
| `DELETE` | `/api/v1/telemetry/dependencies/{id}` | Remove a dependency |

### Dashboard

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/dashboard` | Operations summary: active changes, severity breakdown, impacted services, recent runs |

## Breaking Change Rules

### Request-Side (FR-3.1)

| Change | Classification |
|--------|---------------|
| Adding mandatory query/header/path/cookie param | **BREAKING** |
| Adding optional parameter | NON-BREAKING |
| Adding required property to request body | **BREAKING** |
| Removing/relaxing constraint on request property | NON-BREAKING |
| Removing enum value from request property | **BREAKING** |

### Response-Side (FR-3.2)

| Change | Classification |
|--------|---------------|
| Removing property from response payload | **BREAKING** |
| Adding new property to response payload | NON-BREAKING |
| Changing property data type (e.g. string → int) | **BREAKING** |
| Adding new enum value to response property | **BREAKING** |

### Webhooks (FR-3.3)

| Change | Classification |
|--------|---------------|
| Modifying webhook payload schemas | **BREAKING** |
| Removing webhook event types | **BREAKING** |

## Severity Adjustment

Detected changes are correlated against internal telemetry (gateway logs / dependency registry):

- **Actively consumed** by internal services within the last 30 days → escalated to **CRITICAL**
- **Not consumed** by any internal service → downgraded to **LOW / INFORMATIONAL**

Register dependencies via the telemetry API or the `TelemetryRegistry`.

## Fingerprint Deduplication

Each change gets a deterministic SHA-256 fingerprint:

```
SHA256(VendorID + ":" + EndpointPath + ":" + HTTPMethod + ":" + ChangeType + ":" + JSONPointer)
```

State machine: `NEW` → `ACTIVE` → `RESOLVED`. Repeat daily runs with identical breaking changes do **not** re-trigger notifications. Alerts fire only on `NEW` or `RESOLVED` transitions.

## Alert Batching

When `alerts.batch-mode=true` (default), all changes from a single diff run are aggregated into one Jira ticket and one Slack message per vendor, preventing alert fatigue. PagerDuty still fires per-CRITICAL change. Set `alerts.batch-mode=false` for per-change notifications.

## CI/CD

| Event | What happens |
|-------|-------------|
| Push to `main` | Build → Test → SonarCloud scan → Package → Docker image pushed to GHCR |
| PR to `main` | Build → Test → SonarCloud scan |

**Deploy:** Push to `main` triggers Render auto-deploy from `render.yaml`. The blueprint creates a web service (Docker build) + PostgreSQL 15, both free tier.

## Getting Started

### Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 15+ (or H2 for dev)
- Docker (optional)

### Quick Start (H2)

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

### Configuration

```bash
cp .env.example .env   # Review and fill in values
```

```bash
# Required
export DB_USERNAME=apidrift
export DB_PASSWORD=your_password
export AES_ENCRYPTION_KEY=your-32-byte-encryption-key

# Optional: alert integrations
export JIRA_BASE_URL=https://your-company.atlassian.net
export JIRA_PROJECT_KEY=APIDRIFT
export JIRA_AUTH_TOKEN=your-jira-token
export SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
export PAGERDUTY_ROUTING_KEY=your-pd-routing-key
```

### Build & Run

```bash
# Build
./mvnw clean package

# Run (PostgreSQL required)
java -jar target/api-drift-engine-1.0.0.jar

# Run with H2 (no PostgreSQL needed)
java -jar target/api-drift-engine-1.0.0.jar --spring.profiles.active=h2
```

### Register a Vendor

```bash
curl -X POST http://localhost:8080/api/v1/vendors \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic $(echo -n 'admin:admin' | base64)" \
  -d '{
    "vendorName": "Stripe",
    "specUrl": "https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json",
    "cronExpression": "0 0 */6 * * ?",
    "isActive": true
  }'
```

### Trigger a Diff Run

```bash
curl -X POST http://localhost:8080/api/v1/diffs/trigger/1 \
  -H "Authorization: Basic $(echo -n 'admin:admin' | base64)"
```

### Register a Dependency

```bash
curl -X POST http://localhost:8080/api/v1/telemetry/register \
  -H "Content-Type: application/json" \
  -H "Authorization: Basic $(echo -n 'admin:admin' | base64)" \
  -d '{
    "vendorId": 1,
    "endpointPath": "/v1/charges",
    "httpMethod": "POST",
    "jsonPointer": "/requestBody/properties/amount",
    "serviceName": "payment-service"
  }'
```

### View Dashboard

```bash
curl http://localhost:8080/api/v1/dashboard \
  -H "Authorization: Basic $(echo -n 'admin:admin' | base64)"
```

## Test Coverage

**133 tests** across 10 test classes, 61% business logic coverage (90%+ on 16 of 18 core classes):

| Component | Coverage |
|-----------|----------|
| Rule evaluators (Request, Response, Webhook) | 93-95% |
| Engine (Fingerprint, Diff evaluator, Egress SSRF) | 32-100% |
| Services (Encryption, Dashboard, Telemetry, Health) | 93-100% |
| Controllers (Vendor, Diff, Telemetry, Dashboard) | 88-99% |

Run tests with coverage:
```bash
./mvnw test jacoco:report
open target/site/jacoco/index.html
```

## Security Features

- **SSRF Prevention**: Blocks loopback (127.0.0.1, localhost), link-local (169.254.169.254), and private RFC 1918 IP ranges
- **Connection Guardrails**: Max 10s connect timeout, 30s read timeout, 20 MB payload cap
- **Encryption at Rest**: Vendor API tokens stored with AES-256-GCM encryption
- **Circuit Breaker**: 3 retries with exponential backoff, 30-min cooldown on 3+ consecutive fetch failures
- **Air-gapped Operation**: No external outbound access except through the configured egress proxy

## Deployment

The project includes a [Render Blueprint](render.yaml) for one-click free deployment:

1. Go to [dashboard.render.com/blueprints](https://dashboard.render.com/blueprints)
2. Connect this repo → Render creates web service + PostgreSQL 15
3. **$0/month** — 750 hrs web service + 1 GB PostgreSQL

See [.env.example](.env.example) for all configuration options.

## License

MIT
