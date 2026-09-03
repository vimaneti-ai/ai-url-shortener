# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A URL shortener: Spring Boot 4 backend (Java 21) + a separate Angular 17 frontend, PostgreSQL,
Redis, and Kafka. Two independent apps that must be run separately — there is no monorepo build
tying them together. See `Readme.md` for the full feature list and API reference,
`docs/architecture.md` for architecture/codebase-layout detail beyond what's below,
`docs/design-decisions.md` for why things are built the way they are, `SETUP.md` for the fuller
local-setup guide (including running fully via Docker, with a verified Kafka networking gotcha
you'll want to know about before touching that path), and
`DEPLOYMENT.md` for deploying beyond your own machine.

## Commands

**Backend** (from repo root):
```bash
./mvnw spring-boot:run                          # run the API on :8080
./mvnw verify                                    # full test suite + JaCoCo coverage gate (80% min)
./mvnw test                                      # tests only, no coverage check
./mvnw test -Dtest=UrlServiceTest                # run one test class
./mvnw test -Dtest=UrlServiceTest#methodName     # run one test method
```

**Frontend** (from `frontend/`):
```bash
npm install
npx ng serve      # dev server on :4200, proxies /api/* and /actuator/* to :8080 (proxy.conf.json)
npx ng build
npx ng test       # Karma/Jasmine
```

**Infrastructure** (from repo root):
```bash
docker-compose up -d      # Postgres :5431, Redis :6379, Kafka (KRaft) :9092
docker-compose down
```

The backend must be running for the frontend to do anything beyond render its static shell — it
depends on the API for every action (shorten, analytics, redirect, update, delete).

There is no linter configured on either side (no Checkstyle/Spotless in `pom.xml`, no ESLint in
`frontend/`).

## Architecture — things that span multiple files

**Redirect hot path vs. async analytics pipeline.** `urlController.getLongURLByShortURL` resolves
the URL and returns the `302` *before* analytics are recorded. Click recording is fully decoupled:
the controller publishes a `ClickEventMessage` (serialized to a plain JSON string, not a typed
object — see below) to the `url-click-events` Kafka topic fire-and-forget, wrapped in its own
`try/catch` so an immediate Kafka failure never fails the redirect. The producer also uses
`acks=all`, idempotence, and three native delivery retries. `ClickEventConsumer`
(`@KafkaListener`) picks it up separately, resolves the country via `GeoIpService`, and persists to
`click_events`. Transient consumer failures escape to an exponential-backoff error handler;
malformed JSON is discarded without retry, and exhausted records are logged and skipped because
there is no dead-letter topic. When touching click recording, you're touching at least four files:
`urlController` → `KafkaConfig` → `ClickEventConsumer` → `GeoIpService`.

**Why messages are plain JSON strings, not typed Kafka objects**: `KafkaConfig` uses
`StringSerializer`/`StringDeserializer` throughout specifically to avoid a Jackson 2 vs. Jackson 3
classpath conflict under Spring Boot 4. The app's own Jackson 3 `ObjectMapper`
(`tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson...`) does the (de)serialization
manually in both the controller and the consumer.

**Caching is manual, not `@Cacheable`.** `spring-boot-starter-cache` and `spring.cache.type: redis`
are both configured, but nothing uses `@Cacheable`/`@CachePut`/`@CacheEvict`. `UrlService` and
`GeoIpService` talk to Redis directly via `StringRedisTemplate` with three key families:
`short:{code}` and `long:{longUrl}` (5 min TTL, in `UrlService`), and `geoip:{ip}` (24h TTL, in
`GeoIpService`). Don't assume the Spring Cache abstraction is in play anywhere.

Redis operations use three bounded attempts with exponential backoff and then degrade gracefully:
URL reads fall through to PostgreSQL, while cache writes and GeoIP caching become best-effort.

**Duplicate URL detection is DB-authoritative, cache-accelerated.** `UrlService.createShortUrl`
returns an existing active short code instead of minting a new one for a `longUrl` that's already
mapped. It checks the Redis `long:` key first (fast path), but falls back to
`UrlRepository.findByLongUrlAndActiveTrue` if that's missing or stale — so duplicates are still
caught after the cache entry expires or Redis restarts. Don't "simplify" this to a cache-only check.

**GeoIP: `ipapi.co` is dead, don't reintroduce it.** It returns a Cloudflare bot-challenge page
(`403`) to any server-side request, regardless of User-Agent — confirmed by direct testing, not
theoretical. `GeoIpService` uses `ipwho.is` as primary with `ip-api.com` as fallback, both wrapped
in the same private `get()` helper. Transient network, `429`, and `5xx` failures are retried up to
three times per provider with exponential backoff. Results are Redis-cached per IP for 24h. Private/loopback/link-local
addresses (always the case in local dev — every local click resolves to `"Unknown"` by design, not
by bug) and total lookup failure both resolve to `"Unknown"`.

**API errors are centralized.** `GlobalExceptionHandler` is a `@RestControllerAdvice` that maps
the three `UrlService` domain exceptions to `409`/`404`/`410` and handles bean validation,
malformed requests, constraint violations, and unexpected failures through the shared `ApiError`
response. Keep controllers focused on success paths and add new HTTP mappings to the advice.

**No Flyway.** `src/main/resources/db/migration/*.sql` exists but nothing executes it — there is no
Flyway or Liquibase dependency. Schema changes are applied via Hibernate `ddl-auto: update`. If you
change an entity, you don't need a migration file, but consider adding one to the folder anyway as
human-readable history (matches the existing V1–V4 convention).

**Rate limiting is per-node, not distributed.** `RateLimitFilter` uses an in-memory
`ConcurrentHashMap<String, Bucket>` keyed by client IP, only on `POST` requests to `/api/*`. It does
not share state across multiple application instances — a real multi-instance deployment would need
`bucket4j-redis` instead.

**The controller class is `urlController`** (lowercase first letter) — this is the actual, real
class name in `com.example.URLShortener.controllers`, not a typo to "fix." It's the single
controller for every endpoint (`POST /api/v1/shorten`, `GET /api/v1/{code}`,
`PUT /api/v1/{code}`, `DELETE /api/v1/{code}`, `GET /api/v1/analytics/{code}`).

**`app.base-url`** (`application.yaml`) is injected independently into both `UrlService` and
`AnalyticsService` via `@Value` to build the full `shortUrl` field in their respective responses —
if you change how that URL is constructed, update both.

**Testing is pure-unit, not integration.** All 6 test classes mock their dependencies
(`Mockito.mock(...)`) — there's no `@SpringBootTest` or `MockMvc` anywhere, so tests run in
milliseconds with no Spring context, database, or broker required. JaCoCo enforces 80% minimum line
coverage bundle-wide (`dto`, `models`, `config`, `exceptions` packages are excluded from the count)
— a new service class with no tests will fail `./mvnw verify` on the coverage gate even if all
tests pass, as happened twice this project's history (`GeoIpService`, both times it was rewritten).

**Frontend is one component.** There's no routing module and no lazy-loaded feature modules — the
entire UI (`app.component.ts/html/css`) is a single component that switches between "shorten" and
"analytics" views via a plain `view: 'shorten'|'analytics'` string property, not the Angular Router.
`services/api.service.ts` is the only HTTP boundary; `models/models.ts` holds the TypeScript
mirrors of the backend DTOs — keep them in sync when changing a DTO field name or shape.
