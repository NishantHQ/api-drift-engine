#!/usr/bin/env python3
"""
API Drift Engine — Stress Test Script
=====================================
Sends concurrent requests across read-only endpoints at increasing
concurrency levels, then reports latency percentiles, error rates, and
throughput.

Uses `curl` subprocess (macOS keychain SSL) instead of urllib.

Usage:
    python3 scripts/stress-test.py [--base-url URL] [--concurrency N,N,N]
"""

import argparse
import concurrent.futures
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from typing import Optional

BASE_URL = "https://api-drift-engine.onrender.com"
DEFAULT_CONCURRENCY = [1, 5, 10, 25, 50]

# Read-only endpoints (safe to hammer)
READ_ENDPOINTS = [
    ("GET", "/actuator/health"),
    ("GET", "/api/v1/dashboard"),
    ("GET", "/api/v1/vendors"),
    ("GET", "/api/v1/telemetry/dependencies"),
]


@dataclass
class Result:
    endpoint: str
    status: int
    latency_ms: float
    error: Optional[str] = None


@dataclass
class TierReport:
    concurrency: int
    total: int = 0
    success: int = 0
    errors: int = 0
    latencies: list = field(default_factory=list)
    error_codes: dict = field(default_factory=dict)

    @property
    def error_rate_pct(self) -> float:
        return (self.errors / self.total * 100) if self.total else 0

    @property
    def p50_ms(self) -> float:
        return _percentile(self.latencies, 50)

    @property
    def p90_ms(self) -> float:
        return _percentile(self.latencies, 90)

    @property
    def p99_ms(self) -> float:
        return _percentile(self.latencies, 99)

    @property
    def avg_ms(self) -> float:
        return sum(self.latencies) / len(self.latencies) if self.latencies else 0

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
        total_time = sum(self.latencies) / 1000  # seconds
        return self.total / total_time if total_time > 0 else 0


def _percentile(data: list, p: float) -> float:
    if not data:
        return 0
    s = sorted(data)
    k = (p / 100) * (len(s) - 1)
    f = int(k)
    c = k - f
    if f + 1 < len(s):
        return s[f] + c * (s[f + 1] - s[f])
    return s[f]


def make_curl(method: str, path: str, base_url: str, timeout: int = 30) -> Result:
    """Single request via curl subprocess — uses macOS keychain for SSL."""
    url = f"{base_url}{path}"
    start = time.monotonic()
    try:
        proc = subprocess.run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code} %{time_total}",
             "-X", method,
             "--connect-timeout", str(timeout),
             "--max-time", str(timeout),
             url],
            capture_output=True, text=True, timeout=timeout + 5
        )
        latency = (time.monotonic() - start) * 1000
        output = proc.stdout.strip()

        # curl -w format: "HTTP_CODE TIME_TOTAL"
        parts = output.split()
        if len(parts) >= 2:
            status = int(parts[0])
            # Use curl's measured time (more accurate — includes SSL handshake)
            curl_latency_ms = float(parts[1]) * 1000
            return Result(endpoint=f"{method} {path}", status=status, latency_ms=curl_latency_ms)
        else:
            return Result(endpoint=f"{method} {path}", status=proc.returncode,
                          latency_ms=latency, error=f"unexpected curl output: {output}")
    except subprocess.TimeoutExpired:
        latency = (time.monotonic() - start) * 1000
        return Result(endpoint=f"{method} {path}", status=0, latency_ms=latency, error="timeout")
    except Exception as e:
        latency = (time.monotonic() - start) * 1000
        return Result(endpoint=f"{method} {path}", status=0, latency_ms=latency, error=str(e))


def run_tier(concurrency: int, requests_per_endpoint: int, base_url: str) -> TierReport:
    """Run one concurrency tier."""
    report = TierReport(concurrency=concurrency)

    work = []
    for _ in range(requests_per_endpoint):
        for method, path in READ_ENDPOINTS:
            work.append((method, path))

    report.total = len(work)
    print(f"  → Sending {report.total} requests with concurrency={concurrency} ...",
          end=" ", flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {
            executor.submit(make_curl, method, path, base_url): f"{method} {path}"
            for method, path in work
        }
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            report.latencies.append(result.latency_ms)
            if result.error or result.status >= 500:
                report.errors += 1
                key = str(result.status) if result.status else "timeout"
                report.error_codes[key] = report.error_codes.get(key, 0) + 1
            else:
                report.success += 1

    print(f"done ({report.success} OK, {report.errors} ERR)")
    return report


def warmup(base_url: str) -> bool:
    """Ping health endpoint until it responds (Render cold start)."""
    print("🔥 Warmup: waiting for service to wake up (Render free tier cold start)...")
    for attempt in range(1, 13):
        print(f"  attempt {attempt}/12 ...", end=" ", flush=True)
        result = make_curl("GET", "/actuator/health", base_url, timeout=15)
        if result.status == 200:
            print(f"OK ({result.latency_ms:.0f}ms)")
            return True
        print(f"status={result.status} err={result.error}")
        if attempt < 12:
            time.sleep(5)
    return False


def parse_concurrency(s: str):
    """Parse comma-separated or range notation like '1,5,10,25' or '1-10x5'"""
    parts = [x.strip() for x in s.split(",")]
    result = []
    for p in parts:
        if "-" in p and "x" in p:
            # e.g. "1-100x5" → 1, 5, 10, ..., 100
            rng, step = p.split("x")
            lo, hi = rng.split("-")
            result.extend(range(int(lo), int(hi) + 1, int(step)))
        elif "-" in p:
            lo, hi = p.split("-")
            result.extend(range(int(lo), int(hi) + 1))
        else:
            result.append(int(p))
    return result


def main():
    parser = argparse.ArgumentParser(description="Stress test the API Drift Engine")
    parser.add_argument("--base-url", default=BASE_URL)
    parser.add_argument("--concurrency", default="1,5,10,25,50",
                        help="Comma-separated concurrency levels (e.g. 1,5,10,25,50)")
    parser.add_argument("--requests", type=int, default=20,
                        help="Requests per endpoint per tier")
    parser.add_argument("--no-warmup", action="store_true")
    args = parser.parse_args()

    concurrency_levels = parse_concurrency(args.concurrency)
    base_url = args.base_url.rstrip("/")

    print("=" * 62)
    print("  API Drift Engine — Stress Test")
    print(f"  Target:  {base_url}")
    print(f"  Tiers:   {concurrency_levels} concurrent workers")
    print(f"  Workload: {len(READ_ENDPOINTS)} endpoints × {args.requests} req each = "
          f"{len(READ_ENDPOINTS) * args.requests} req/tier")
    print("=" * 62)

    if not args.no_warmup:
        if not warmup(base_url):
            print("\n❌ Service did not respond after 60s. Is it deployed?")
            sys.exit(1)
    else:
        print("⏭️  Skipping warmup")
    print()

    # ── Run tiers ────────────────────────────────────────────────
    reports: list[TierReport] = []
    for concurrency in concurrency_levels:
        print(f"── Tier: {concurrency} concurrent workers ──")
        report = run_tier(concurrency, args.requests, base_url)
        reports.append(report)
        if concurrency != concurrency_levels[-1]:
            time.sleep(2)
        print()

    # ── Summary table ────────────────────────────────────────────
    print("=" * 100)
    print(f"{'Conc':>5} {'Total':>6} {'OK':>6} {'Err%':>7} "
          f"{'Avg':>8} {'p50':>8} {'p90':>8} {'p99':>8} "
          f"{'Min':>8} {'Max':>8} {'RPS':>8}")
    print("-" * 100)
    for r in reports:
        print(f"{r.concurrency:>5} {r.total:>6} {r.success:>6} {r.error_rate_pct:>6.1f}% "
              f"{r.avg_ms:>7.0f}ms {r.p50_ms:>7.0f}ms {r.p90_ms:>7.0f}ms {r.p99_ms:>7.0f}ms "
              f"{r.min_ms:>7.0f}ms {r.max_ms:>7.0f}ms {r.rps:>7.1f}")
    print("=" * 100)

    # ── Error codes ──────────────────────────────────────────────
    all_errors: dict[str, int] = {}
    for r in reports:
        for code, count in r.error_codes.items():
            all_errors[code] = all_errors.get(code, 0) + count
    if all_errors:
        print("\n⚠️  Error breakdown:")
        for code, count in sorted(all_errors.items()):
            print(f"    HTTP {code}: {count}")

    # ── Latency distribution of the highest tier ─────────────────
    if reports and reports[-1].latencies:
        print(f"\n📊 Latency distribution at highest concurrency ({reports[-1].concurrency}):")
        lat = sorted(reports[-1].latencies)
        buckets = [100, 250, 500, 1000, 2000, 5000, float("inf")]
        b_labels = ["<100ms", "100-250ms", "250-500ms", "500ms-1s", "1-2s", "2-5s", ">5s"]
        counts = [0] * len(buckets)
        for l in lat:
            for i, b in enumerate(buckets):
                if l < b:
                    counts[i] += 1
                    break
        max_count = max(counts) if counts else 1
        for label, count in zip(b_labels, counts):
            bar = "█" * int(count / max_count * 40)
            print(f"    {label:>10}: {bar} {count}")

    # ── Verdict ──────────────────────────────────────────────────
    total_ok = sum(r.success for r in reports)
    total_all = sum(r.total for r in reports)
    overall_err = (1 - total_ok / total_all) * 100 if total_all else 0

    print()
    if overall_err == 0:
        print("✅ All requests succeeded — service handled the load cleanly.")
    elif overall_err < 5:
        print(f"⚠️  {overall_err:.1f}% error rate — mostly stable, some blips.")
    elif overall_err < 20:
        print(f"⚠️  {overall_err:.1f}% error rate — showing stress signs.")
    else:
        print(f"❌ {overall_err:.1f}% error rate — service is struggling under load.")

    if len(reports) >= 2:
        print(f"\n📈 Trend (concurrency {concurrency_levels[0]} → {concurrency_levels[-1]}):")
        print(f"    RPS:         {reports[0].rps:.1f} → {reports[-1].rps:.1f}")
        print(f"    p90 latency: {reports[0].p90_ms:.0f}ms → {reports[-1].p90_ms:.0f}ms")

    # ── Save JSON report ─────────────────────────────────────────
    json_report = {
        "target": base_url,
        "tiers": [
            {
                "concurrency": r.concurrency,
                "total": r.total,
                "success": r.success,
                "errors": r.errors,
                "error_rate_pct": round(r.error_rate_pct, 2),
                "avg_ms": round(r.avg_ms, 1),
                "p50_ms": round(r.p50_ms, 1),
                "p90_ms": round(r.p90_ms, 1),
                "p99_ms": round(r.p99_ms, 1),
                "min_ms": round(r.min_ms, 1),
                "max_ms": round(r.max_ms, 1),
                "rps": round(r.rps, 1),
            }
            for r in reports
        ],
    }
    report_path = "scripts/stress-test-results.json"
    with open(report_path, "w") as f:
        json.dump(json_report, f, indent=2)
    print(f"\n📄 JSON report saved to {report_path}")


if __name__ == "__main__":
    main()
