# Architecture

Describes the system as it actually exists today — not a target/scaled-up design. For why specific
choices were made (Base62 vs. hashing, manual Redis caching, Kafka decoupling, etc.), see
[`design-decisions.md`](design-decisions.md). For scope and assumptions, see
[`requirements.md`](requirements.md).

## System diagram

```mermaid
graph TD
    Client((Browser / Angular SPA))
    HostNginx[EC2 system nginx<br/>TLS termination]
    Frontend[Container nginx<br/>Angular SPA]
    API[Spring Boot API — single node]
    Cache[(Redis)]
    DB[(PostgreSQL)]
    Kafka[[Kafka topic: url-click-events]]
    Consumer[ClickEventConsumer]
    GeoIP[GeoIpService]
    CronJob[CleanupService — hourly @Scheduled]

    Client -->|HTTPS short.vinodmaneti.com| HostNginx
    HostNginx -->|HTTP 127.0.0.1:4200| Frontend
    Frontend -->|HTTP /api/v1/* and /{shortCode}| API
    API <--> Cache
    API -->|create / update / delete / duplicate-check| DB
    API -->|redirect cache miss / mutations| DB

    API -.->|fire-and-forget publish, ~1ms| Kafka
    Kafka -.->|@KafkaListener| Consumer
    Consumer -->|resolve country| GeoIP
    GeoIP <--> Cache
    Consumer -->|persist click_events| DB

    CronJob -.->|hard-delete expired urls + click_events| DB
    CronJob -.->|evict short:/long: Redis keys| Cache
```

This is genuinely everything that runs: one Spring Boot process, one Postgres instance, one Redis
instance, one Kafka broker (KRaft, no ZooKeeper), and a separate Angular SPA. On EC2, system nginx
terminates HTTPS for `short.vinodmaneti.com` and forwards to the containerized frontend on
`127.0.0.1:4200`; frontend nginx then proxies API and health requests to the backend over the shared
Docker network. The root Compose file builds and runs all five services. There is no load balancer, CDN,
read replica, or Redis cluster in this deployment — if
you're looking for how this would need to change to run at a much larger scale, that discussion
lives in `design-decisions.md`, clearly separated from what's described here.

`docker-compose.prod.yml` is a deployment overlay rather than a second stack definition. It adds
EC2 memory constraints and localhost-only host bindings while inheriting service topology, health
checks, networks, volumes, builds, and environment wiring from `docker-compose.yml`. The host's
system nginx is deliberately outside Compose so it can own ports 80/443 and let Certbot manage TLS.

## Components

1. **Spring Boot API** (`urlController`, single controller) — create, update, delete, and analytics
   use `/api/v1/*`; redirects use the public bare `/{shortCode}` path required by the assessment.
   Browser-prefetch/link-preview requests still redirect but do not publish click events. All
   durable state lives in Postgres; transient cache data lives in Redis.
2. **PostgreSQL** — source of truth for `urls` and `click_events` (schema below). ACID guarantees
   are what prevent two concurrent requests from creating duplicate custom aliases or duplicate
   active mappings for the same long URL.
3. **Redis** — manual caching via `StringRedisTemplate` directly in `UrlService` and
   `GeoIpService`. **Not** Spring's `@Cacheable`/`@CachePut`/`@CacheEvict` abstraction, despite
   `spring-boot-starter-cache` and `spring.cache.type: redis` both being configured — nothing in
   the codebase actually uses those annotations. Three key families, all with explicit eviction on
   mutation plus TTL expiry:
   - `short:{code}` → `longUrl`, 5 min TTL
   - `long:{longUrl}` → `code`, 5 min TTL
   - `geoip:{ip}` → country name, 24h TTL
   Each cache operation is attempted up to three times with short exponential backoff. If Redis
   remains unavailable, URL reads continue through PostgreSQL and cache writes/evictions become
   best-effort; GeoIP resolution continues without its cached fast path.
4. **Kafka** (`url-click-events` topic, 3 partitions, single broker) — decouples click recording
   from the redirect response. The controller publishes a `ClickEventMessage` (as a plain JSON
   string — see "Why plain strings" below) fire-and-forget and returns the `302` immediately; a
   producer uses `acks=all`, idempotence, and three native delivery retries. Publishing remains
   asynchronous, so broker failures never block or fail the redirect response.
5. **ClickEventConsumer** (`@KafkaListener`) — resolves the click's country via `GeoIpService` and
   persists the enriched event to `click_events`, entirely off the redirect hot path. Processing
   failures are retried with bounded exponential backoff. Invalid JSON is discarded without retry;
   after transient retries are exhausted, the default recoverer logs and skips the record because
   no dead-letter topic is configured.
6. **GeoIpService** — IP → country resolution. Retries transient failures up to three times per
   provider with exponential backoff, trying `ipwho.is` first and then `ip-api.com`, and
   Redis-caches the result per IP for 24h. Private/loopback/link-local addresses and total lookup
   failure both resolve to `"Unknown"` without ever blocking click recording. (`ipapi.co` was
   evaluated and rejected — it returns a Cloudflare bot-challenge page to any server-side request
   regardless of User-Agent, confirmed by direct testing, not assumption.)
7. **CleanupService** (`@Scheduled`, hourly) — hard-deletes expired `urls` rows and their
   `click_events` (cascade), and evicts the corresponding Redis keys. The `DELETE /api/v1/shorten/{code}`
   endpoint reuses this exact same delete-and-evict sequence for user-initiated deletion.
8. **Angular SPA** (`frontend/`) — a single component (no router, no lazy modules) that toggles
   between a shorten view and an analytics/manage view. Talks to the API over relative `/api/v1/*`
   URLs, proxied to the backend by the dev server locally (see `SETUP.md`/`DEPLOYMENT.md` for what
   that means in production).

**Warm-cache redirects do not query Postgres.** Every URL cache entry uses the smaller of five
minutes and the link's remaining lifetime as its TTL. A cache entry therefore cannot outlive its
link expiration, allowing `resolveLongUrl` to return a hit without a database check. Updates,
deletes, and cleanup evict both cache directions. A cache miss still requires PostgreSQL, so Redis
is a bounded acceleration and temporary degradation path rather than durable database failover.

**Why plain JSON strings over Kafka, not typed objects**: `KafkaConfig` uses
`StringSerializer`/`StringDeserializer` throughout to avoid a Jackson 2 vs. Jackson 3 classpath
conflict under Spring Boot 4. The app's own Jackson 3 `ObjectMapper` handles (de)serialization
manually in both the producer and the consumer.

## Data model

Production schema changes are applied via Hibernate's `ddl-auto: update`. Flyway is test-scoped so
the integration suite applies and verifies every migration against an empty PostgreSQL database
without attempting to baseline the already-populated production database.

### `urls`

| Column | Type | Notes |
| :--- | :--- | :--- |
| `id` | INTEGER (IDENTITY) | Base62-encoded to produce the short code |
| `short_url` | VARCHAR(8), UNIQUE | Holds the bare short **code** despite the column name — the API layer exposes both `shortUrl` (full URL) and `shortCode` (bare value) to avoid this ambiguity |
| `long_url` | VARCHAR(2048), NOT NULL | Destination URL |
| `created_at` / `updated_at` | TIMESTAMP | JPA auditing timestamps |
| `expires_at` | TIMESTAMP, nullable | Past this, redirects return `410 Gone` and the row is marked inactive |
| `active` | BOOLEAN, NOT NULL | `false` once expired or explicitly deactivated; excluded from active-lookup queries |

### `click_events`

| Column | Type | Notes |
| :--- | :--- | :--- |
| `id` | BIGSERIAL | — |
| `short_url` | VARCHAR(8), FK → `urls.short_url`, `ON DELETE CASCADE` | — |
| `clicked_at` | TIMESTAMP, NOT NULL | — |
| `ip_address` | VARCHAR(45), nullable | `X-Forwarded-For` if present, else `getRemoteAddr()` |
| `user_agent` | TEXT, nullable | Raw header value |
| `country` | VARCHAR(64), nullable | Resolved by `GeoIpService`; `"Unknown"` for private IPs or failed lookups |

## Codebase layout

```text
src/main/java/com/example/URLShortener/
├── UrlShortenerApplication.java     # entry point (@EnableKafka, @EnableScheduling)
├── config/
│   ├── KafkaConfig.java             # producer/consumer factories + topic definition
│   ├── RateLimitFilter.java         # in-memory Bucket4j filter, POST only, on /api/*
│   ├── FilterConfig.java            # registers RateLimitFilter
│   └── OpenApiConfig.java           # springdoc bean (title/description/version)
├── controllers/
│   └── urlController.java           # every endpoint — no other controllers exist
├── exceptions/
│   └── GlobalExceptionHandler.java  # centralized exception-to-HTTP mapping
├── dto/                             # ApiError, URLRequest, URLUpdateRequest,
│                                     # URLResponse, AnalyticsResponse, ClickEventMessage
├── models/                          # URL, ClickEvent (JPA entities)
├── repository/                      # UrlRepository, ClickEventRepository
└── services/
    ├── UrlService.java              # create/resolve/update/delete + manual Redis caching
    ├── AnalyticsService.java        # aggregation: totals, unique visitors, country breakdown
    ├── GeoIpService.java            # IP → country, dual provider, Redis-cached
    ├── ClickEventConsumer.java      # @KafkaListener
    ├── CleanupService.java          # @Scheduled expired-URL purge
    └── Base62Encoder.java           # stateless int ↔ Base62 string encoder
```

`GlobalExceptionHandler` is a `@RestControllerAdvice` that maps the three domain exceptions
(`AliasAlreadyExistsException`, `UrlNotFoundException`, `UrlExpiredException`) to
`409`/`404`/`410`. It also returns the shared `ApiError` JSON shape for validation failures,
malformed requests, constraint violations, and unexpected `500` errors. Unexpected internal
exception messages are logged but not exposed to clients. Controllers now contain success-path
logic only. There is no dedicated `RedisConfig`; connection settings use Spring Boot
auto-configuration from `application.yaml`.

## Base62 encoding

```java
private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
```

Flow: insert the new `URL` row (without a code yet) to claim an auto-generated `id` → encode that
`id` to Base62 → update the row's `short_url` column with the encoded value. Since the `IDENTITY`
column is atomic and monotonically increasing, this is a pure bijective function — collisions are
impossible by construction, not by low probability. A custom alias skips this step entirely and is
used as-is after a uniqueness check.

## Testing

See [`testing.md`](testing.md) for the actual strategy and current verified numbers — not
restated here to avoid the two documents drifting out of sync with each other.
