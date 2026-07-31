---
title: "How We Built an OpenAPI Drift Detector in Java 21"
date: 2026-07-31
author: "API Drift Engine Team"
tags: ["java", "spring-boot", "openapi", "architecture"]
---

## The Challenge

Track every external API your organization depends on, detect breaking changes the moment they're published, and route alerts to the right teams — all without false positives, duplicate notifications, or alert fatigue.

Here's the architecture that makes it work.

## The Pipeline

The engine runs a 10-step pipeline for each registered vendor:

```
Poll → Fetch → Hash → Parse → Diff → Correlate → Fingerprint → Alert
```

### Step 1–2: Fetch with SSRF Protection

Before anything else, we need to safely download the vendor's OpenAPI spec. This is where most "just curl it" solutions fail. An SSRF vulnerability in a spec-fetching service could let an attacker point it at internal infrastructure.

Our solution:

- **Egress validation**: Before any HTTP request, resolve the target hostname and validate it's not loopback (`127.0.0.0/8`), link-local (`169.254.0.0/16`), or RFC 1918 private space (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
- **Circuit breaker**: 3 retries with exponential backoff (1s, 2s, 4s); after 3 consecutive failures, the circuit opens for 30 minutes to avoid hammering a degraded vendor
- **Payload cap**: 20 MB in-memory limit via WebClient configuration (some specs, like Stripe's, are 4MB+)

### Step 3: Content Hashing

Before parsing, we compute a SHA-256 hash of the raw spec. If the hash matches the previous snapshot, we halt — no changes means no work. This simple optimization prevents unnecessary parsing and diffing for every poll cycle.

### Step 4–5: Parsing and Normalization

We use `swagger-parser` 2.x to handle both OpenAPI 3.0 and 3.1 formats, dereference `$ref` pointers, and produce a normalized JSON tree. The normalized form is what we store as a `SpecSnapshot` — an immutable record with the raw spec in a PostgreSQL JSONB column.

### Step 6: Directional Compatibility Diff

This is the heart of the engine. We compare the old and new normalized specs and apply **breaking change rules** across three dimensions:

**Request-Side Rules:**
- Adding a mandatory query/header/path param → **BREAKING**
- Adding optional param → NON-BREAKING
- Adding required property to request body → **BREAKING**
- Removing enum value from request property → **BREAKING**

**Response-Side Rules:**
- Removing property from response payload → **BREAKING**
- Changing property data type (string → int) → **BREAKING**
- Adding new enum value to response → **BREAKING**
- Adding new property → NON-BREAKING

**Webhook Rules:**
- Modifying webhook payload schemas → **BREAKING**
- Removing webhook event types → **BREAKING**

Each rule is implemented as a separate evaluator class (`RequestRuleEvaluator`, `ResponseRuleEvaluator`, `WebhookRuleEvaluator`), making the rule set easy to extend.

### Step 7: Telemetry Correlation

Not all breaking changes matter equally. A breaking change to an endpoint your services don't use is noise. A breaking change to `/v1/charges` when your `payment-service` calls it daily is a critical incident.

The `UsageCorrelationService` queries a telemetry registry — a database of service-to-endpoint dependencies — and adjusts severity:

- **Consumed by an active service** → escalated to **CRITICAL**
- **Not consumed** → downgraded to **LOW**

Teams register their dependencies via a simple API:
```json
{
  "vendorId": 1,
  "endpointPath": "/v1/charges",
  "httpMethod": "POST",
  "serviceName": "payment-service"
}
```

### Step 8: Fingerprint Deduplication

This is where most alerting systems fail. Run the same diff twice, get the same breaking change, fire the same alert. After a week, your Slack channel is a graveyard of duplicate notifications.

Our solution: deterministic SHA-256 fingerprints.

```
SHA256(VendorID + ":" + EndpointPath + ":" + HTTPMethod + ":" + ChangeType + ":" + JSONPointer)
```

Each fingerprint goes through a state machine: **NEW → ACTIVE → RESOLVED**. Alerts fire only on state transitions. A change that persists across 10 daily runs generates exactly one alert — the first time it appears. A change that's resolved and reappears later generates a new alert.

## Technology Choices

| Layer | Choice | Why |
|-------|--------|-----|
| Runtime | Java 21 | Virtual threads, pattern matching, long-term support |
| Framework | Spring Boot 3.3 | Mature ecosystem, WebClient for non-blocking HTTP, Quartz for scheduling |
| Database | PostgreSQL 15 + JSONB | Specs are naturally JSON documents; JSONB enables future querying on spec contents |
| Parsing | swagger-parser 2.1.18 | Handles both OAS 3.0 and 3.1, robust `$ref` dereferencing |
| Diffing | openapi-diff-core 2.1.7 | Battle-tested diff engine, used by SwaggerHub and Apicurio |
| Encryption | AES-256-GCM | Vendor API tokens encrypted at rest, key managed via env var |
| CI/CD | GitHub Actions + Render | Build, test, SonarCloud scan, push Docker to GHCR, auto-deploy |

## What We Learned

1. **Batch alerts by default.** Per-change Slack messages are overwhelming. Single messages per vendor per run are manageable.
2. **Spec hashing is worth it.** Many vendors update specs without meaningful changes (whitespace, formatting). Hashing saves unnecessary pipeline execution.
3. **SSRF prevention requires defense in depth.** DNS rebinding attacks can bypass simple hostname checks. We validate both hostname AND resolved IP.
4. **Test your rule evaluators heavily.** The `RequestRuleEvaluator` has 95% test coverage because breaking change classification is subtle — a bug here means either false alarms or missed breakages.
5. **Fingerprint dedup is the unsung hero.** Without it, "API drift monitoring" becomes "API drift spam." The state machine is what makes this a tool people actually want to use.

## Try It Yourself

The project is [open source on GitHub](https://github.com/NishantHQ/api-drift-engine) under the MIT license. Deploy it on Render's free tier in one click, register your first vendor, and never be surprised by a vendor API change again.
