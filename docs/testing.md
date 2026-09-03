# Testing

## Strategy

The backend has two complementary test layers:

- **53 unit tests** isolate repositories and external clients with Mockito for fast feedback.
- **9 integration tests** use `@SpringBootTest`, MockMvc, Flyway, and a disposable PostgreSQL 15
  Testcontainer. They send real JSON through Spring MVC and verify persisted records, migrations,
  constraints, redirects, validation errors, expiration, update/delete behavior, and analytics.

Redis and Kafka remain mocked in this integration layer so these tests have one deliberate boundary:
Spring MVC through PostgreSQL. Real-Redis cache tests and Kafka broker integration are separate work
rather than being mislabeled as part of the database/API suite.

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
Integration tests run: 9, Failures: 0, Errors: 0, Skipped: 0
Total tests: 62
Line coverage: 90.2% (296 of 328 included lines)
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

## Coverage gate

JaCoCo enforces an **80% minimum line-coverage ratio**, bundle-wide, on every `./mvnw verify` —
excluding the `dto`, `models`, `config`, and `exceptions` packages (`pom.xml`). This gate has
actually failed and forced test additions twice in this project's history: once when
`GeoIpService` was first introduced without tests (coverage dropped to 79%), and again when it was
rewritten for the dual-provider/caching redesign. Both times, the fix was writing real tests for
the new class, not lowering the threshold.

## What this suite intentionally does not cover

- **No load/performance testing.** Redirect latency figures mentioned elsewhere in this project's
  history (e.g. "~4ms vs ~18ms") are architectural reasoning about what Kafka removes from the hot
  path, not measurements taken from a benchmark run against this deployment. Treat them as relative
  reasoning, not a service-level number.
- **No frontend test suite beyond a minimal Karma/Jasmine spec** (`app.component.spec.ts`, 4
  tests: component creation, heading render, URL-scheme validation, and explicit short-link opening). Not counted in
  the JaCoCo coverage figure above, and not comprehensive.
- **No chaos/failure-injection testing** (e.g. killing the Kafka broker mid-traffic and confirming
  redirects keep working). The graceful-degradation behavior is implemented and code-reviewable
  (see `architecture.md`), but has not been exercised under an actual broker outage.
