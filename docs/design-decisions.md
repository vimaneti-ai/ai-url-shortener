# Design Decisions

Trade-offs actually made in this codebase, and why. Not a "how would you scale this to a billion
URLs" discussion — see the note at the bottom for why that's deliberately excluded.

## Base62 over hashing

A hash-based short code (MD5/SHA truncated to 7-8 chars) risks collisions and needs a
check-and-retry loop. This codebase instead encodes the row's own auto-incrementing `id` to Base62
(`Base62Encoder`, digits+uppercase+lowercase alphabet). Since Postgres `IDENTITY` values are
atomic and monotonically increasing, every code is unique by construction — there's no collision
probability to reason about, and no retry logic needed. The trade-off: codes are sequential and
therefore guessable/enumerable (visiting adjacent codes reveals adjacent links), which the
`Expiration`/custom-alias/access-control-free design doesn't currently mitigate. A custom alias
bypasses this entirely and is used as-is after a uniqueness check.

## Manual Redis caching instead of `@Cacheable`

`spring-boot-starter-cache` and `spring.cache.type: redis` are both configured, but `UrlService`
and `GeoIpService` talk to `StringRedisTemplate` directly rather than using
`@Cacheable`/`@CachePut`/`@CacheEvict`. This is more boilerplate, but it's what makes two
non-obvious behaviors possible: (1) maintaining *two* independent key directions
(`short:{code}→longUrl` and `long:{longUrl}→code`) from a single write, which a single `@Cacheable`
annotation on one method signature can't express cleanly, and (2) treating a stale/missing cache
entry as a signal to fall through to a DB-authoritative check rather than just re-populating blindly
(see "Duplicate detection" below). The cost is that cache invalidation is manual and easy to forget
when adding a new mutation — every place that changes a URL's data has to remember to call
`evictCache` itself; there's no annotation to enforce it. Redis operations use three bounded
attempts with exponential backoff and then degrade to the database or a no-op cache write, so a
temporary cache outage does not become an API outage.

## Duplicate URL detection: cache-accelerated, not cache-only

Shortening the same `longUrl` twice returns the existing active code. The naive version of this
(check Redis, done) breaks the moment the 5-minute cache entry expires or Redis restarts — the
same long URL would silently get a second, different short code. `UrlService.findActiveDuplicate`
checks Redis first as a fast path, but falls back to `UrlRepository.findByLongUrlAndActiveTrue`
whenever the cache is empty or points at a URL that's since been deleted. The database is the
actual source of truth; Redis is purely a latency optimization in front of it. This was a real bug
in an earlier version of this exact codebase, caught by manually evicting the Redis key and
re-testing rather than trusting that the logic was correct — see `ai-workflow.md`.

## Kafka to decouple click recording from the redirect response

A synchronous `INSERT` into `click_events` on every redirect (this codebase actually still has
that version sitting unused as `AnalyticsService.recordClick()`, kept as a reference point) adds
DB write latency to every single redirect, and couples redirect availability to database
availability. Publishing a `ClickEventMessage` to Kafka fire-and-forget and letting a separate
`@KafkaListener` (`ClickEventConsumer`) persist it removes both: the redirect only pays for a
Kafka publish (~1ms, and even a publish *failure* is caught and logged without affecting the
redirect), and the actual DB write — plus the `GeoIpService` network call — happens entirely off
that path. Producer delivery uses `acks=all`, idempotence, and three Kafka-native retries. Consumer
processing failures are retried with bounded exponential backoff; invalid payloads are not retried,
and exhausted records are logged and skipped because this project does not currently configure a
dead-letter topic.

## Dual-provider GeoIP with a cache in front

The original choice for IP → country resolution, `ipapi.co`, turned out to return a Cloudflare
bot-challenge page (`403`) to any server-side request regardless of User-Agent — confirmed by
direct testing against it, not a hypothetical failure mode. `GeoIpService` now tries `ipwho.is`
first with `ip-api.com` as a fallback, and caches results in Redis per IP for 24h so a second click
from the same visitor doesn't trigger a second external call. Each provider gets up to three
attempts for network errors, rate limiting, and server errors; other client errors fall through
immediately. The lesson embedded in this decision:
a mocked-only test suite had exercised nothing but the private-IP short-circuit path, so the
original provider's failure went undetected until "required, not optional" prompted testing it
against a real public IP.

## In-memory rate limiting, not Redis-backed (yet)

`RateLimitFilter` uses a `ConcurrentHashMap<String, Bucket>` per application instance rather than
`bucket4j-redis`. This is simpler and has zero extra infrastructure dependency, but it only
enforces the limit per-node — running more than one application instance would let a client get a
full fresh bucket on each instance it happens to hit. Acceptable for a single-node deployment;
would need to move to a Redis-backed bucket before running more than one.

## Hard delete, not soft delete, for `DELETE /api/v1/shorten/{code}`

Deleting a link removes the row and its click history entirely (`ClickEventRepository`
`.deleteByShortUrl` + `UrlRepository.delete`), rather than just flipping `active = false`. This
mirrors the exact sequence `CleanupService` already uses for expired links, so there's one delete
pathway, not two different semantics for "gone because expired" vs. "gone because deleted." The
trade-off: there's no undo and no retained audit trail after a delete — `PRIVACY.md` documents this
as the practical implication for anyone relying on click history.

## No Flyway/Liquibase — `ddl-auto: update`

Schema changes apply automatically via Hibernate rather than versioned migration scripts. Faster
to iterate on for a project this size, at the cost of the usual `ddl-auto: update` risk: it adds
and alters columns but never drops ones removed from an entity, so a genuine schema diff has to be
verified by hand rather than trusted from the entity change alone. The `.sql` files under
`db/migration/` are kept as human-readable history of what changed and when, even though nothing
executes them.

---

**Why there's no "scaling this to a billion URLs" section here**: that's a different kind of
document — a hypothetical systems-design exercise — from this one, which documents decisions
actually made in this actual codebase at its actual current scale (single node, single Postgres,
single Redis, single Kafka broker). Mixing "why we built it this way" with "how I'd redesign it for
100x the traffic" made an earlier version of this documentation set read as if load balancers and
read replicas already existed. They don't.
