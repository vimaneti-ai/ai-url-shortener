# Requirements & Scope

## Functional requirements

The canonical HTTP contract is:

```text
POST   /api/v1/shorten                 request field: url
GET    /{shortCode}                    302 redirect
GET    /api/v1/analytics/{shortCode}   response field: clicks
PUT    /api/v1/shorten/{shortCode}
DELETE /api/v1/shorten/{shortCode}
```

The create/update response may additionally return `shortCode` and `longUrl` for UI convenience;
those response fields do not change the assessment's required create-input field, which is `url`.

- **Shorten a URL** — given a long URL, generate a short code. Shortening the same long URL twice
  returns the existing active code rather than minting a duplicate.
- **Custom aliases** — optionally choose the short code instead of auto-generating one
  (1–8 alphanumeric characters, checked for uniqueness).
- **Redirect** — visiting a short URL redirects to its destination.
- **Expiration** — a link can have an optional expiry; past it, the link stops resolving
  (`410 Gone`) but its row and click history are retained until deleted.
- **Update** — a link's destination and/or expiry can be changed after creation. Moving the expiry
  into the future (or clearing it) reactivates a previously expired link.
- **Delete** — a link and its entire click history can be permanently removed.
- **Analytics** — total clicks, unique visitors (by distinct IP), a per-country breakdown (via IP
  geolocation), and a recent-clicks table (time, IP, country, user agent) per short code.

## Non-functional goals (and what's actually been verified about them)

- **Low redirect latency**: the architecture uses Redis-first lookups and moves click recording off
  the redirect path via Kafka. A reproducible k6 profile now exercises 1,000 concurrent virtual
  users with explicit correctness, failure-rate, p95, and p99 thresholds. The first documented
  local run preserved 100% correctness but failed the latency thresholds, so no passing latency or
  production-capacity claim is made — see `testing.md` and `../performance/README.md`.
- **Availability under partial failure**: a **Kafka outage** degrades gracefully — publish
  failures are caught and logged, redirects are unaffected, and only click analytics pause. This
  behavior is implemented and code-reviewable, but has not been exercised via an actual broker
  outage (a chaos-style test). Two things Kafka does **not** protect against, confirmed by reading
  the actual exception handling rather than assumed: a **Postgres outage** — warm-cache redirects
  continue only until their expiry-bounded Redis TTL elapses, while cache misses fail because
  there is no durable database failover — and a
  **Postgres outage specifically during consumption** — Kafka retries transient listener failures
  with exponential backoff, but permanently failed records are skipped after retry exhaustion
  because no dead-letter topic is configured. A **Redis outage** is retried three times with bounded
  backoff; URL operations then fall back to PostgreSQL and GeoIP caching becomes best-effort.
- **Error handling**: a global `@RestControllerAdvice` maps domain, validation, malformed-request,
  and unexpected failures to a consistent JSON response. Unexpected internal error details are
  logged server-side and replaced with a safe client message.
- **Security validation**: destination URLs are parsed as URIs and restricted to absolute HTTP or
  HTTPS URLs with a host and no user-info component. Dedicated PostgreSQL-backed integration tests
  cover SQL-like input, unsafe schemes, malformed URLs, XSS payloads, expired links, and `429` rate
  limiting.
- **Unpredictable short codes**: not actually true today — Base62-encoded auto-increment IDs are
  sequential and enumerable (see `design-decisions.md`). Custom aliases are the only way to get an
  unpredictable code.

No specific uptime or latency SLA (e.g. "99.99%" or "<10ms") is claimed for this deployment. Those
numbers appeared in an earlier version of this project's documentation without any benchmark or
production traffic behind them and have been removed rather than carried forward unverified.

## Explicit non-goals / out of scope

- **No authentication or accounts.** Every endpoint is open. See `PRIVACY.md` for what that means
  for who can see click analytics.
- **No multi-instance / distributed deployment support today.** Rate limiting is in-memory and
  per-node (`RateLimitFilter`); running more than one application instance would give each client a
  fresh rate-limit bucket per instance rather than a shared one.
- **No read replicas, Redis cluster, CDN, or load balancer.** Single Postgres instance, single
  Redis instance, single Kafka broker, single application node. What changing this would require is
  a separate design discussion, not part of the current implementation.
- **No self-domain / recursive-redirect protection.** A short URL can be pointed at another short
  URL on the same instance; nothing currently detects or blocks that.
- **No production migration runner.** EC2 schema changes still use Hibernate `ddl-auto: update`.
  Test-scoped Flyway applies and verifies V1–V4 against disposable PostgreSQL instances but is not
  included as an EC2 deployment step; see `design-decisions.md`.

## Assumptions

- Single-node deployment is acceptable for the current use case; the codebase's clean
  controller/service/repository separation is what would make scaling out a targeted change later
  rather than a rewrite, but that work has not been done.
- Visitors' IP addresses may be sent to third-party GeoIP providers (`ipwho.is`, `ip-api.com`) for
  country resolution — see `PRIVACY.md` for the full disclosure.
- The frontend and backend are deployed such that the frontend's relative `/api/*` calls actually
  reach the backend (same origin, or a reverse proxy) — see `DEPLOYMENT.md` for what's required to
  make that true, since it isn't automatic.

## Current deployment

The implemented single-node deployment is live at
[https://short.vinodmaneti.com](https://short.vinodmaneti.com). IONOS DNS maps the subdomain to an
AWS Elastic IP; EC2 system nginx redirects HTTP to HTTPS, terminates the Let's Encrypt certificate,
and proxies to the localhost-bound frontend container. This satisfies the same-origin assumption
above without publicly exposing the backend or infrastructure ports.

Pushes to `main` pass backend, frontend, and Docker validation before deploying through GitHub
OIDC and AWS Systems Manager. No EC2 SSH private key or long-lived AWS access key is stored in
GitHub; see `DEPLOYMENT.md` for the exact workflow and IAM boundaries.

## Assessment verification snapshot

The four previously missing engineering-test artifacts are now represented as separate changes:

1. REST/database integration tests through Spring Boot, MockMvc, PostgreSQL Testcontainers, and
   test-scoped Flyway.
2. Real-Redis cache tests proving hit/miss statistics, hit ratio, TTL bounds, database-query
   avoidance, and mutation eviction.
3. A dedicated security integration suite for SQL-like input, unsafe URLs, XSS, expired links, and
   rate limiting.
4. A k6 workload that reaches 1,000 concurrent virtual users, with a smoke profile, thresholds,
   production safety guard, machine-readable output, and report template.

The 1,000-user artifact and first dated run are complete. That run produced zero functional
failures but exceeded the configured latency limits, making performance tuning and an instrumented
rerun the next step rather than changing the result to “pass.” The broader assessment items still
pending outside these four tasks are an exposed metrics endpoint/dashboard (production
currently exposes only restricted health) and a finalized 10-minute demo script.
