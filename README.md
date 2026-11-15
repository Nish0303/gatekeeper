# GateKeeper — API Gateway with JWT Auth, Rate Limiting & Request Proxying

A standalone API gateway that sits in front of backend services and handles the three things almost every production API needs before a request should be allowed to reach real business logic: **authentication, rate limiting, and observability**. Built to understand how tools like Kong, AWS API Gateway, and Cloudflare solve this problem at a smaller, from-scratch scale.

## What it does

Every incoming request passes through a single filter chain:

```
Request → JWT validation → API key lookup → Rate limit check → Proxy to backend → Log → Response
```

- **JWT validation** — rejects any request without a valid, unexpired bearer token (401 if missing/invalid).
- **Per-client rate limiting** — each API key gets its own limit, enforced with a **token-bucket algorithm** implemented as an atomic Lua script in Redis (429 if exceeded).
- **Request proxying** — valid requests are forwarded to the backend service with method, path, query string, headers and body intact; the backend's status code and body are returned to the caller.
- **Logging & dashboard** — every request (path, status code, latency) is persisted to Postgres and viewable per-client on a lightweight web dashboard.

## Results

| Test | Result |
|---|---|
| Load test (`load_test.py`): 50 concurrent clients for 20 s, single machine | **228 req/s, zero errors** (4,500+ requests), p50 211 ms, p99 360 ms |
| Steady-state gateway latency (dashboard, low load) | ~8–14 ms per request |
| Rate limit, 5 requests/min key | Requests 1–5 → `200`, request 6 → `429` |
| Token-bucket script under a race: 200 simultaneous requests, limit 50 | Exactly **50 allowed, 150 rejected**, no over-admission |
| Refill (5/min key) | 1 token back after 12 s, 2 after 24 s, as expected for 5/60 tokens per second |

## Stack

| Layer | Choice |
|---|---|
| Language / framework | Java 17, Spring Boot 3 |
| Rate limit store | Redis (token-bucket via Lua script) |
| Request log store | PostgreSQL |
| Auth | JWT (HS256, `jjwt`) |
| Dashboard | Static HTML/CSS/vanilla JS (no build step) |
| Containerization | Docker + Docker Compose |
| Dummy backend | `httpbin` (for realistic proxy testing) |

## Architecture

```
                    ┌─────────────┐
   client  ───────► │  GateKeeper  │
                    │ (Spring Boot)│
                    └──────┬───────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   ┌─────────┐       ┌───────────┐      ┌────────────┐
   │  Redis  │       │ Postgres  │      │  httpbin   │
   │ (rate   │       │ (request  │      │ (dummy     │
   │ limits) │       │  logs)    │      │  backend)  │
   └─────────┘       └───────────┘      └────────────┘
```

All four services (gateway + the three above) run together via a single `docker compose up --build`.

## Why token bucket instead of a fixed-window counter

The first working version used a simpler fixed-window counter (`INCR` + `EXPIRE` per 60-second window). It works, but has a well-known flaw: a client can send its full limit right at the *end* of one window and again right at the *start* of the next, getting up to 2x the intended limit in a short burst.

The token-bucket approach fixes this: each API key has a bucket holding up to N tokens (N = its per-minute limit), which refill continuously at N/60 tokens per second rather than resetting all at once. Refill is computed **lazily** on each request (`elapsed_time × refill_rate`) rather than via a background job, which avoids needing a scheduler entirely.

Because the read-check-decrement sequence has to be atomic (two concurrent requests must not both read "1 token left" and both get allowed), the whole operation runs as a single **Lua script executed inside Redis**, which is single-threaded per script and eliminates the race condition.

## Database schema

```sql
api_keys(
  id, key_hash, owner, rate_limit_per_min
)

request_logs(
  id, api_key_id, path, status_code, latency_ms, timestamp
)
```

API keys are stored as SHA-256 hashes only — the raw key is never persisted.

## API

| Endpoint | Description |
|---|---|
| `POST /auth/token?subject=<name>` | Mints a test JWT (stands in for a real auth server) |
| `ANY /<path>` | Proxied to the backend if JWT + rate limit pass, else `401` / `429` |
| `GET /api/logs?apiKeyId=<id>` | Returns request history for a given API key |
| `GET /api/keys` | Lists API key IDs and owners (never exposes hashes) |
| `GET /dashboard.html` | Usage dashboard UI |

## Running it

```bash
docker compose up --build
```

This starts Redis, Postgres, httpbin, and the gateway itself, networked together.

Then, register a test API key (one-time, direct SQL — no admin UI yet):

```bash
docker compose exec postgres psql -U gatekeeper -d gatekeeper
```
```sql
INSERT INTO api_keys (key_hash, owner, rate_limit_per_min)
VALUES (encode(sha256('my-test-key'::bytea), 'hex'), 'test-user', 5);
```

Get a token and call the gateway:

```bash
curl -X POST "localhost:8080/auth/token?subject=test-user"

curl localhost:8080/get \
  -H "Authorization: Bearer <token>" \
  -H "X-API-Key: my-test-key"
```

View usage at **http://localhost:8080/dashboard.html**.

Send the `curl` request 6 times within a minute to see the rate limiter return `429` on the sixth.

## Load test

Register a key with a limit high enough not to throttle the test:

```sql
INSERT INTO api_keys (key_hash, owner, rate_limit_per_min)
VALUES (encode(sha256('load-test-key'::bytea), 'hex'), 'load-test', 1000000);
```

Then run:

```bash
pip install requests
python load_test.py
```

The script mints its own JWT, runs 50 concurrent clients for 20 seconds against `/get` (proxied to httpbin), and prints throughput, status codes and p50/p99 latency.

## Configuration

Defaults in `application.yml` are for local development. Override them with environment variables:

| Variable | Purpose |
|---|---|
| `GATEKEEPER_JWT_SECRET` | HMAC secret for signing/verifying JWTs (32+ bytes). **Always set this outside local dev.** |
| `GATEKEEPER_BACKEND_URL` | Backend service to proxy to |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL connection |
| `SPRING_DATA_REDIS_HOST` | Redis host |

The database credentials in `docker-compose.yml` and `application.yml` are local development defaults only.

## What's not included (known scope cuts)

Built in 24 hours as a learning project, so a few things were deliberately left out rather than half-built:

- No endpoint to create/rotate API keys (inserted via SQL for now)
- No check that a JWT's subject actually owns the API key being used alongside it
- No multi-route backend config — proxies to a single configured backend URL
- Auth token minting is a stub (`/auth/token`); a real system would have a separate auth service issuing JWTs
- The dashboard and `/api/logs`, `/api/keys` endpoints are unauthenticated
- Backend response headers (e.g. `Content-Type`) are not copied back to the caller, only status code and body

## What this demonstrates

- Atomic rate limiting under concurrency (Lua + Redis), not just a naive counter
- A servlet filter as a clean composition point for cross-cutting concerns (auth → limit → proxy → log)
- Externalized config via environment variables so the same jar runs unmodified locally (Maven) and in Docker (container networking)
- Multi-stage Docker builds to keep the runtime image slim
