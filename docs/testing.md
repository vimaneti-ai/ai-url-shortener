# Testing

## Strategy

The backend has two complementary test layers:

- **53 unit tests** isolate repositories and external clients with Mockito for fast feedback.
- **21 integration tests** use `@SpringBootTest`, MockMvc, Flyway, and disposable PostgreSQL 15 and
  Redis 7 Testcontainers. Nine API/database tests verify persisted records, migrations, constraints,
  redirects, validation errors, expiration, update/delete behavior, and analytics. Four cache tests
  exercise real Redis operations, server hit/miss statistics, TTL, and eviction. Eight security
  executions cover SQL-injection handling, invalid URL forms, XSS payloads, expired links, and
  rate limiting.

Kafka remains mocked because broker integration is outside these API/database and cache test
boundaries. Redis is mocked by the API/database tests and real in `RedisCacheIT`.

## Production smoke checks

The deployed boundary was manually verified on 2026-09-03:

```bash
curl -I http://short.vinodmaneti.com
curl -I https://short.vinodmaneti.com
curl https://short.vinodmaneti.com/actuator/health
sudo certbot renew --dry-run  # run on EC2
```

The checks confirmed HTTP-to-HTTPS redirection, a successful HTTPS frontend response, the restricted
health response `{"status":"UP"}`, and successful simulated certificate renewal. These are deployment
smoke checks, not a replacement for automated browser, load, or failure-injection tests. The
automated API/database integration layer is described above.

## Performance test

[`performance/url-shortener-load.js`](../performance/url-shortener-load.js) is a reproducible k6
test with a safe 10-second smoke profile and an 11-minute load profile. The load profile ramps to
and holds 1,000 concurrent virtual users for five minutes: 900 exercise redirects and 100 query
analytics. Redirect responses are not followed, keeping third-party destination latency and traffic
out of the measurement.

The pass/fail thresholds cover correctness, endpoint failure rates, and p95/p99 latency. A full run
can include Kafka publication and click persistence (`RECORD_ANALYTICS=true`) or exercise the
Redis-first resolution path without recording clicks (`RECORD_ANALYTICS=false`). See
[`performance/README.md`](../performance/README.md) for exact commands, production safeguards, and
the evidence template and the dated full-run report.

The two-user local smoke profile was executed on 2026-09-03 with analytics recording disabled. It
completed 22 iterations with 100% of checks passing, zero endpoint failures, redirect p95/p99 of
15.99/16.42 ms, and analytics p95/p99 of 23.43/23.90 ms. These numbers validate the script and local
request paths only; they are not a production capacity result.

The local full profile was then run at 1,000 VUs for commit `0f391d9`. It completed 204,952
iterations with 100% checks passing and zero functional failures. It **failed** the configured
latency thresholds: redirect p95 was 3,049.83 ms and analytics p95 was 3,434.45 ms. The environment,
p99 values, limitations, and interpretation are recorded in
[`performance/performance-report-2026-09-03.md`](../performance/performance-report-2026-09-03.md).

The frontend Dockerfile also runs `nginx -t` while building the runtime image. Because Compose DNS
does not exist during an isolated image build, the check temporarily substitutes loopback for the
`backend` service name and restores the real configuration immediately afterward. This makes
malformed proxy configuration fail in CI rather than first appearing as a production `502 Bad
Gateway` after deployment.

## Run it

```bash
./mvnw verify
```

Docker Desktop (or another compatible Docker engine) must be running. `verify` runs unit tests with
Surefire, integration tests (`*IT`) with Failsafe, and then the JaCoCo coverage gate. `./mvnw test`
runs only the 53 unit tests and does not start PostgreSQL.

## Current results

Regenerated immediately before writing this document — not carried over from an earlier point in
development:

```
Unit tests run: 53, Failures: 0, Errors: 0, Skipped: 0
Integration tests run: 21, Failures: 0, Errors: 0, Skipped: 0
Total tests: 74
Line coverage: 91.2% (320 of 351 included lines)
```

| Test class | Tests | Covers |
| --- | :---: | --- |
| `UrlControllerTest` | 15 | Every endpoint, Kafka publishing, graceful degradation, prefetch exclusion, and wire-contract field/path assertions |
| `UrlServiceTest` | 15 | Base62 encoding, manual caching, Redis retry/DB fallback, duplicate detection, expiration/reactivation, update, delete |
| `GeoIpServiceTest` | 10 | Private/loopback short-circuit, Redis cache hit, provider retry and fallback, both-providers-fail, malformed payload — all via a mocked `HttpClient` |
| `ClickEventConsumerTest` | 4 | Persistence, null user-agent handling, poison-message protection, and propagation of transient persistence failures to Kafka retry handling |
| `AnalyticsServiceTest` | 3 | Click totals, unique-visitor counting, country-breakdown aggregation |
| `CleanupServiceTest` | 2 | Scheduled purge of expired URLs and their click history |
| `GlobalExceptionHandlerTest` | 4 | Domain status mappings and safe unexpected-error responses |
| `UrlApiIT` | 7 | Full Spring MVC request handling and PostgreSQL persistence across create, redirect, validation, expiration, update/delete, and analytics |
| `DatabaseMigrationIT` | 2 | All four Flyway migrations plus PostgreSQL uniqueness and foreign-key enforcement |
| `RedisCacheIT` | 4 | Real Redis miss/hit counters and ratio, no additional database query on a hit, expiry-bounded TTL, and update/delete eviction |
| `SecurityIT` | 8 | Four invalid/unsafe URL cases plus SQL-injection handling, XSS rejection, expired-link behavior, and `429` rate limiting |

### Security evidence

`SecurityIT` sends malicious and invalid inputs through the real Spring MVC validation and
PostgreSQL persistence boundary. It proves SQL-like destination text is parameterized data while a
malicious alias is rejected, disallows malformed and non-HTTP(S) destinations, prevents XSS
payloads from being stored, returns `410` without publishing analytics for expired links, and
returns `429` with `Retry-After` after the configured per-client limit.

### Redis cache evidence

`RedisCacheIT` resets Redis server statistics, resolves the same code twice, and verifies one
`keyspace_misses` event followed by one `keyspace_hits` event: a controlled **50% hit ratio**. It
also uses Hibernate statistics to prove that the second resolution does not execute another
PostgreSQL query. This is a deterministic functional test of the hit-ratio calculation, not a claim
that production traffic will always have a 50% hit ratio.

## Coverage gate

JaCoCo enforces an **80% minimum line-coverage ratio**, bundle-wide, on every `./mvnw verify` —
excluding the `dto`, `models`, `config`, and `exceptions` packages (`pom.xml`). This gate has
actually failed and forced test additions twice in this project's history: once when
`GeoIpService` was first introduced without tests (coverage dropped to 79%), and again when it was
rewritten for the dual-provider/caching redesign. Both times, the fix was writing real tests for
the new class, not lowering the threshold.

## What this suite intentionally does not cover

- **No passing 1,000-user latency result.** A dated local run exists and preserved correctness, but
  exceeded all four p95/p99 latency thresholds. It is not evidence that the current deployment
  meets those latency objectives.
- **No frontend test suite beyond a minimal Karma/Jasmine spec** (`app.component.spec.ts`, 4
  tests: component creation, heading render, URL-scheme validation, and explicit short-link opening). Not counted in
  the JaCoCo coverage figure above, and not comprehensive.
- **No chaos/failure-injection testing** (e.g. killing the Kafka broker mid-traffic and confirming
  redirects keep working). The graceful-degradation behavior is implemented and code-reviewable
  (see `architecture.md`), but has not been exercised under an actual broker outage.
