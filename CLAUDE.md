# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A URL shortener: Spring Boot 4 backend (Java 21) + a separate Angular 17 frontend, PostgreSQL,
Redis, and Kafka. They can run separately for development or together through the root Compose
file. See `Readme.md` for the full feature list and API reference,
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
./mvnw test                                      # 53 unit tests only; no Testcontainers
./mvnw failsafe:integration-test failsafe:verify # 24 integration-test executions (after test-compile)
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

**Complete container stack** (from repo root):
```bash
cp .env.example .env
docker compose up --build -d  # DB, Redis, Kafka, backend :8080, frontend :4200
docker compose ps
docker compose down
```

The k6 package under `performance/` provides a 10-second smoke profile and an 11-minute profile
that reaches 1,000 concurrent virtual users. Follow `performance/README.md`; never aim the full
profile at the public deployment without explicit approval.

For EC2, layer `docker-compose.prod.yml` over the base file. It contains runtime memory limits,
heap tuning, and localhost-only internal port bindings; do not duplicate those settings in the
local-development base file. The live deployment is `https://short.vinodmaneti.com`: system nginx
owns public ports 80/443, redirects HTTP to HTTPS, terminates the Certbot-managed certificate, and
proxies to the frontend container at `127.0.0.1:4200`. The production `.env` therefore uses
`FRONTEND_PORT=4200` and `APP_BASE_URL=https://short.vinodmaneti.com`.

The backend must be running for the frontend to do anything beyond render its static shell — it
depends on the API for every action (shorten, analytics, redirect, update, delete).

The nginx production image proxies only the exact `/actuator/health` endpoint. Other actuator
paths return `404` publicly. Spring itself exposes `health`, `metrics`, and `prometheus` on backend
port 8080; production binds that port to localhost, and health details remain disabled.

**CI/CD:** `.github/workflows/ci-deploy.yml` runs backend/frontend validation in parallel, then
builds both Docker images. Pushes to `main` deploy through GitHub OIDC and AWS SSM; no SSH key is
stored in GitHub. The deploy job deliberately hard-resets the EC2 checkout to `origin/main`, so
never rely on tracked edits made directly on the server. The ignored production `.env` survives
and is validated before deployment.

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

**Production Hibernate, test-only Flyway.** Production schema changes are applied by Hibernate
`ddl-auto: update`; the runtime image has no Flyway migration step. Flyway and its PostgreSQL module
are test-scoped, however, and `DatabaseMigrationIT` applies V1–V4 from
`src/main/resources/db/migration/` to a fresh PostgreSQL Testcontainer and verifies the resulting
constraints. Keep entities and those migration scripts aligned.

**Rate limiting is per-node, not distributed.** `RateLimitFilter` uses an in-memory
`ConcurrentHashMap<String, Bucket>` keyed by client IP, only on `POST` requests to `/api/*`. It does
not share state across multiple application instances — a real multi-instance deployment would need
`bucket4j-redis` instead.

**The controller class is `urlController`** (lowercase first letter) — this is the actual, real
class name in `com.example.URLShortener.controllers`, not a typo to "fix." It's the single
controller for every endpoint (`POST /api/v1/shorten`, `GET /{code}`,
`PUT /api/v1/shorten/{code}`, `DELETE /api/v1/shorten/{code}`,
`GET /api/v1/analytics/{code}`). The former versioned redirect and shorter update/delete paths
remain compatibility aliases, but are not the canonical assessment contract.

**`app.base-url`** (`application.yaml`) is injected independently into both `UrlService` and
`AnalyticsService` via `@Value` to build the full `shortUrl` field in their respective responses —
if you change how that URL is constructed, update both.

**Testing has unit and integration layers.** Surefire runs 53 isolated unit tests. Failsafe runs 24
integration-test executions across `UrlApiIT`, `DatabaseMigrationIT`, `RedisCacheIT`, and
`SecurityIT` plus `MetricsIT`, using the full Spring context, MockMvc, PostgreSQL 15 Testcontainers,
and a real Redis 7 container. Kafka remains mocked at this boundary. `./mvnw verify` runs both layers and enforces an
80% JaCoCo line-coverage minimum (currently 91.2%); see `docs/testing.md` for the exact evidence and
the separate k6 performance package.

**Frontend is one component.** There's no routing module and no lazy-loaded feature modules — the
entire UI (`app.component.ts/html/css`) is a single component that switches between "shorten" and
"analytics" views via a plain `view: 'shorten'|'analytics'` string property, not the Angular Router.
`services/api.service.ts` is the only HTTP boundary; `models/models.ts` holds the TypeScript
mirrors of the backend DTOs — keep them in sync when changing a DTO field name or shape.
