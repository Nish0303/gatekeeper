"""
Load test for GateKeeper.

Fetches a fresh JWT from the gateway, then hammers a proxied endpoint with
concurrent clients for a fixed duration and reports throughput, status codes
and latency percentiles.

Before running, register an API key with a high limit so the rate limiter
doesn't reject the test traffic (see README, "Load test").

Usage:
    pip install requests
    python load_test.py
"""
import concurrent.futures
import threading
import time

import requests

BASE_URL = "http://localhost:8080"
TARGET = f"{BASE_URL}/get"
API_KEY = "load-test-key"
DURATION_SECONDS = 20
CONCURRENCY = 50

# Mint a fresh token (tokens expire after 1 hour, so never hardcode one)
token = requests.post(f"{BASE_URL}/auth/token", params={"subject": "load-test"}, timeout=5).text.strip()

headers = {
    "Authorization": f"Bearer {token}",
    "X-API-Key": API_KEY,
}

results = []
results_lock = threading.Lock()
stop_time = time.time() + DURATION_SECONDS


def hit():
    session = requests.Session()
    while time.time() < stop_time:
        start = time.perf_counter()
        try:
            r = session.get(TARGET, headers=headers, timeout=5)
            latency_ms = (time.perf_counter() - start) * 1000
            entry = (r.status_code, latency_ms)
        except Exception:
            entry = ("error", 0)
        with results_lock:
            results.append(entry)


with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as ex:
    futures = [ex.submit(hit) for _ in range(CONCURRENCY)]
    concurrent.futures.wait(futures)

total = len(results)
latencies = sorted(l for s, l in results if s == 200)
status_counts = {}
for s, _ in results:
    status_counts[s] = status_counts.get(s, 0) + 1

print(f"\nTotal requests: {total}")
print(f"Duration:       {DURATION_SECONDS}s, {CONCURRENCY} concurrent clients")
print(f"Requests/sec:   {total / DURATION_SECONDS:.1f}")
print(f"Status codes:   {status_counts}")
if latencies:
    print(f"Latency avg:    {sum(latencies) / len(latencies):.1f} ms")
    print(f"Latency p50:    {latencies[int(len(latencies) * 0.5)]:.1f} ms")
    print(f"Latency p99:    {latencies[int(len(latencies) * 0.99)]:.1f} ms")
