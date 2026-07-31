#!/usr/bin/env python3
"""
API Drift Engine — Write Stress Test: 1,000 Vendor Creations
=============================================================
Creates 1,000 vendors at escalating concurrency tiers, measuring:
  - Success / error rates (409 duplicates, 5xx, timeouts)
  - Latency percentiles (p50, p90, p99)
  - Throughput (creations/sec)
  - DB state verification via dashboard

Each vendor gets a unique name via UUID suffix — no 409 collisions.

Usage:
    API_DRIFT_ADMIN_PASSWORD="..." python3 scripts/stress-create-vendors.py [--count 1000] [--cleanup]
"""

import argparse
import concurrent.futures
import json
import os
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

BASE_URL = "https://api-drift-engine.onrender.com"
ADMIN_USER = "admin"
ADMIN_PASS = os.environ.get("API_DRIFT_ADMIN_PASSWORD", "")

# Concurrency tiers — escalate from gentle to aggressive
TIERS = [5, 10, 25, 50, 100]

# Each vendor is lightweight — just a name + specUrl + cron
# Using a real OpenAPI spec URL so the orchestrator can fetch it if triggered
SPEC_URL = "https://petstore3.swagger.io/api/v3/openapi.json"
CRON = "0 0 * * 0"  # weekly, won't actually fire during test


# ── Helpers ───────────────────────────────────────────────────────
def curl(method: str, path: str, body: Optional[dict] = None,
         timeout: int = 60) -> tuple[int, str]:
    url = f"{BASE_URL}{path}"
    cmd = ["curl", "-s", "-w", "CURL_STATUS:%{http_code}", "-X", method,
           "--connect-timeout", str(timeout), "--max-time", str(timeout)]
    if ADMIN_PASS:
        cmd += ["-u", f"{ADMIN_USER}:{ADMIN_PASS}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    cmd.append(url)
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 5)
        output = proc.stdout
        if "CURL_STATUS:" in output:
            body_part, status_str = output.rsplit("CURL_STATUS:", 1)
            return int(status_str.strip()) if status_str.strip().isdigit() else 0, body_part.strip()
        return 0, output
    except subprocess.TimeoutExpired:
        return 0, "timeout"
    except Exception as e:
        return 0, str(e)


@dataclass
class TierResult:
    tier: int = 0
    concurrency: int = 0
    total: int = 0
    success: int = 0
    errors: int = 0
    error_409: int = 0
    error_5xx: int = 0
    error_timeout: int = 0
    error_other: int = 0
    latencies: list = field(default_factory=list)
    vendor_ids: list = field(default_factory=list)

    @property
    def error_rate_pct(self) -> float:
        return (self.errors / self.total * 100) if self.total else 0

    @property
    def avg_ms(self) -> float:
        return sum(self.latencies) / len(self.latencies) if self.latencies else 0

    @property
    def p50_ms(self) -> float:
        return _pct(self.latencies, 50)

    @property
    def p90_ms(self) -> float:
        return _pct(self.latencies, 90)

    @property
    def p99_ms(self) -> float:
        return _pct(self.latencies, 99)

    @property
    def min_ms(self) -> float:
        return min(self.latencies) if self.latencies else 0

    @property
    def max_ms(self) -> float:
        return max(self.latencies) if self.latencies else 0

    @property
    def rps(self) -> float:
        if not self.latencies:
            return 0
        wall = sum(self.latencies) / 1000
        return self.total / wall if wall > 0 else 0

    @property
    def creations_per_sec(self) -> float:
        if not self.latencies:
            return 0
        wall = sum(self.latencies) / 1000
        return self.success / wall if wall > 0 else 0


def _pct(data: list, p: float) -> float:
    if not data:
        return 0
    s = sorted(data)
    k = (p / 100) * (len(s) - 1)
    f = int(k)
    c = k - f
    return s[f] + c * (s[f + 1] - s[f]) if f + 1 < len(s) else s[f]


def create_one_vendor(prefix: str, idx: int) -> dict:
    """Create a single vendor, return result dict."""
    name = f"{prefix}-{idx:05d}-{uuid.uuid4().hex[:6]}"
    start = time.monotonic()
    status, body = curl("POST", "/api/v1/vendors", {
        "vendorName": name,
        "specUrl": SPEC_URL,
        "cronExpression": CRON,
        "isActive": True
    })
    latency = (time.monotonic() - start) * 1000
    vendor_id = None
    if 200 <= status < 300:
        try:
            vendor_id = json.loads(body).get("id")
        except json.JSONDecodeError:
            pass
    return {
        "name": name, "status": status, "latency_ms": latency,
        "vendor_id": vendor_id, "error": "" if status < 500 else body[:200]
    }


# ── Main ──────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="Stress test: create 1000 vendors")
    parser.add_argument("--count", type=int, default=1000, help="Total vendors to create")
    parser.add_argument("--cleanup", action="store_true", help="Delete all stress-test vendors after run")
    parser.add_argument("--tiers", default="5,10,25,50,100",
                        help="Comma-separated concurrency levels")
    args = parser.parse_args()

    if not ADMIN_PASS:
        print("❌ Set API_DRIFT_ADMIN_PASSWORD environment variable")
        sys.exit(1)

    tiers = [int(x.strip()) for x in args.tiers.split(",")]
    total_count = args.count
    prefix = f"stress-{uuid.uuid4().hex[:8]}"

    print("=" * 70)
    print("  API Drift Engine — Write Stress Test")
    print(f"  Target:     {BASE_URL}")
    print(f"  Goal:       {total_count} vendors")
    print(f"  Tiers:      {tiers}")
    print(f"  Prefix:     {prefix}-*")
    print("=" * 70)

    # ── Pre-flight ─────────────────────────────────────────────
    print("\n── Pre-flight check ──")
    status, body = curl("GET", "/actuator/health")
    print(f"  Health: HTTP {status} — {body[:80]}")
    status, body = curl("GET", "/api/v1/dashboard")
    vendors_before = 0
    if status == 200:
        vendors_before = json.loads(body).get("totalVendors", 0)
    print(f"  Vendors before: {vendors_before}")

    # ── Run tiers ──────────────────────────────────────────────
    all_results: list[TierResult] = []
    all_vendor_ids: list[int] = []
    created_so_far = 0

    for tier_num, concurrency in enumerate(tiers):
        remaining = total_count - created_so_far
        if remaining <= 0:
            break

        batch_size = min(
            remaining,
            # Scale batch per tier: earlier tiers do less, later tiers do the bulk
            remaining // (len(tiers) - tier_num) if tier_num < len(tiers) - 1 else remaining
        )
        # Round up to ensure we hit the target
        if tier_num == len(tiers) - 1:
            batch_size = remaining
        batch_size = max(batch_size, 1)

        print(f"\n── Tier {tier_num + 1}/{len(tiers)}: {batch_size} vendors @ {concurrency} concurrent ──")
        tier_result = TierResult(tier=tier_num + 1, concurrency=concurrency, total=batch_size)
        print(f"  → Creating {batch_size} vendors with prefix '{prefix}' ... ", end=" ", flush=True)

        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [
                executor.submit(create_one_vendor, prefix, created_so_far + i + 1)
                for i in range(batch_size)
            ]
            for future in concurrent.futures.as_completed(futures):
                r = future.result()
                tier_result.latencies.append(r["latency_ms"])
                if 200 <= r["status"] < 300:
                    tier_result.success += 1
                    if r["vendor_id"]:
                        tier_result.vendor_ids.append(r["vendor_id"])
                else:
                    tier_result.errors += 1
                    if r["status"] == 409:
                        tier_result.error_409 += 1
                    elif r["status"] >= 500:
                        tier_result.error_5xx += 1
                    elif r["status"] == 0:
                        tier_result.error_timeout += 1
                    else:
                        tier_result.error_other += 1

        created_so_far += batch_size
        all_vendor_ids.extend(tier_result.vendor_ids)
        all_results.append(tier_result)

        print(f"done ({tier_result.success} OK, {tier_result.errors} ERR)")
        print(f"     avg={tier_result.avg_ms:.0f}ms  p50={tier_result.p50_ms:.0f}ms  "
              f"p90={tier_result.p90_ms:.0f}ms  p99={tier_result.p99_ms:.0f}ms  "
              f"creations/s={tier_result.creations_per_sec:.1f}")

        # Cooldown between tiers
        if tier_num < len(tiers) - 1 and created_so_far < total_count:
            time.sleep(1)

    # ── Verify ─────────────────────────────────────────────────
    print(f"\n── Verification ──")
    time.sleep(2)  # let DB settle
    status, body = curl("GET", "/api/v1/dashboard")
    vendors_after = json.loads(body)["totalVendors"] if status == 200 else -1
    print(f"  Dashboard totalVendors: {vendors_after}")
    expected = vendors_before + created_so_far
    if vendors_after >= expected * 0.95:  # allow 5% margin for ghosts from prior runs
        print(f"  ✓ Count verified (~{vendors_after} ≥ {int(expected * 0.95)})")
    else:
        print(f"  ⚠️ Expected ~{expected}, got {vendors_after}")

    # ── Summary ─────────────────────────────────────────────────
    print(f"\n{'=' * 84}")
    print(f"{'Tier':>4} {'Conc':>5} {'Total':>6} {'OK':>6} {'Err%':>7} "
          f"{'Avg':>8} {'p50':>8} {'p90':>8} {'p99':>8} {'Cre/s':>8} {'409':>6} {'5xx':>6}")
    print("-" * 84)
    total_ok = 0
    total_err = 0
    all_latencies = []
    for r in all_results:
        total_ok += r.success
        total_err += r.errors
        all_latencies.extend(r.latencies)
        print(f"{r.tier:>4} {r.concurrency:>5} {r.total:>6} {r.success:>6} {r.error_rate_pct:>6.1f}% "
              f"{r.avg_ms:>7.0f}ms {r.p50_ms:>7.0f}ms {r.p90_ms:>7.0f}ms {r.p99_ms:>7.0f}ms "
              f"{r.creations_per_sec:>7.1f} {r.error_409:>5} {r.error_5xx:>5}")
    print("=" * 84)
    print(f"  TOTAL: {total_ok} created, {total_err} errors "
          f"({total_err / (total_ok + total_err) * 100:.1f}% error rate)")
    print(f"  Overall avg={sum(all_latencies) / len(all_latencies):.0f}ms  "
          f"p50={_pct(all_latencies, 50):.0f}ms  "
          f"p90={_pct(all_latencies, 90):.0f}ms  "
          f"p99={_pct(all_latencies, 99):.0f}ms")

    # ── Latency histogram ──────────────────────────────────────
    if all_latencies:
        print(f"\n  Latency distribution (all {len(all_latencies)} requests):")
        buckets = [250, 500, 1000, 2000, 5000, 10000, float("inf")]
        labels = ["<250ms", "250-500ms", "500ms-1s", "1-2s", "2-5s", "5-10s", ">10s"]
        counts = [0] * len(buckets)
        for l in all_latencies:
            for i, b in enumerate(buckets):
                if l < b:
                    counts[i] += 1
                    break
        max_c = max(counts) if counts else 1
        for label, cnt in zip(labels, counts):
            bar = "█" * int(cnt / max_c * 35)
            print(f"    {label:>10}: {bar} {cnt}")

    # ── Error breakdown ────────────────────────────────────────
    total_409 = sum(r.error_409 for r in all_results)
    total_5xx = sum(r.error_5xx for r in all_results)
    total_timeout = sum(r.error_timeout for r in all_results)
    if total_409 or total_5xx or total_timeout:
        print(f"\n  Error breakdown:")
        if total_409:
            print(f"    409 Conflict: {total_409}")
        if total_5xx:
            print(f"    5xx Server:   {total_5xx}")
        if total_timeout:
            print(f"    Timeout:      {total_timeout}")

    # ── Cleanup ─────────────────────────────────────────────────
    if args.cleanup:
        print(f"\n── Cleanup: deleting {len(all_vendor_ids)} vendors ... ──")
        deleted = 0
        failed_delete = 0
        # Delete in parallel, but gently
        with concurrent.futures.ThreadPoolExecutor(max_workers=25) as executor:
            futures = {
                executor.submit(curl, "DELETE", f"/api/v1/vendors/{vid}"): vid
                for vid in all_vendor_ids
            }
            for future in concurrent.futures.as_completed(futures):
                status, _ = future.result()
                if 200 <= status < 300:
                    deleted += 1
                else:
                    failed_delete += 1
        print(f"  Deleted: {deleted}, Failed: {failed_delete}")

        # Verify
        time.sleep(1)
        status, body = curl("GET", "/api/v1/dashboard")
        vendors_final = json.loads(body)["totalVendors"] if status == 200 else -1
        print(f"  Dashboard totalVendors: {vendors_final} (was {vendors_before})")
    else:
        print(f"\n  💡 To clean up: re-run with --cleanup")
        print(f"     Or manually: DELETE /api/v1/vendors/{{id}} for each vendor")
        print(f"     Vendor prefix: '{prefix}-*'")
        print(f"     Count: {len(all_vendor_ids)}")

    # Save report
    report = {
        "target": BASE_URL,
        "prefix": prefix,
        "vendors_before": vendors_before,
        "vendors_after": vendors_after,
        "created": total_ok,
        "errors": total_err,
        "avg_ms": round(sum(all_latencies) / len(all_latencies), 1) if all_latencies else 0,
        "p50_ms": round(_pct(all_latencies, 50), 1),
        "p90_ms": round(_pct(all_latencies, 90), 1),
        "p99_ms": round(_pct(all_latencies, 99), 1),
        "tiers": [
            {"concurrency": r.concurrency, "total": r.total, "ok": r.success, "err": r.errors,
             "avg_ms": round(r.avg_ms, 1), "p50_ms": round(r.p50_ms, 1),
             "p90_ms": round(r.p90_ms, 1), "p99_ms": round(r.p99_ms, 1),
             "creations_per_sec": round(r.creations_per_sec, 1)}
            for r in all_results
        ],
    }
    report_path = "scripts/stress-create-vendors-results.json"
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2)
    print(f"\n📄 Report saved to {report_path}")

    return 0 if total_err == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
