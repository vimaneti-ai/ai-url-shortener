# Testing

## Strategy

All backend tests are pure unit tests — every dependency (`UrlRepository`, `ClickEventRepository`,
`StringRedisTemplate`, `HttpClient`, `KafkaTemplate`) is mocked with Mockito. There is no
`@SpringBootTest` and no `MockMvc` anywhere in this codebase: no Spring context loads, no database,
Redis, or Kafka broker is required to run the suite, and the full run completes in a few seconds.

This means these tests verify unit-level logic and contracts, not end-to-end integration behavior.
Integration-level confidence in this project came from manually running the full stack (Docker
Compose + backend + frontend) and exercising it with `curl`/Playwright during development — see
`ai-workflow.md` for specific examples of bugs that were only caught that way, not by the unit
suite.

## Run it

```bash
./mvnw verify
```

`verify` runs the tests and then the JaCoCo coverage gate; `./mvnw test` runs just the tests
without the gate.

## Current results

Regenerated immediately before writing this document — not carried over from an earlier point in
development:

```
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
Line coverage: 89.6% (294 of 328 included lines)
```

| Test class | Tests | Covers |
| --- | :---: | --- |
| `UrlControllerTest` | 12 | Every endpoint, Kafka publishing, graceful degradation, and exclusion of browser-prefetch requests from analytics |
| `UrlServiceTest` | 15 | Base62 encoding, manual caching, Redis retry/DB fallback, duplicate detection, expiration/reactivation, update, delete |
| `GeoIpServiceTest` | 10 | Private/loopback short-circuit, Redis cache hit, provider retry and fallback, both-providers-fail, malformed payload — all via a mocked `HttpClient` |
| `ClickEventConsumerTest` | 4 | Persistence, null user-agent handling, poison-message protection, and propagation of transient persistence failures to Kafka retry handling |
| `AnalyticsServiceTest` | 3 | Click totals, unique-visitor counting, country-breakdown aggregation |
| `CleanupServiceTest` | 2 | Scheduled purge of expired URLs and their click history |
| `GlobalExceptionHandlerTest` | 4 | Domain status mappings and safe unexpected-error responses |

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
