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

- **Low redirect latency**: the architecture is built around this — Redis-first lookups, and click
  recording moved off the redirect path entirely via Kafka. **Not independently load-tested or
  benchmarked in this deployment.** Any specific millisecond figures mentioned elsewhere in this
  project's docs describe architectural reasoning ("removing a synchronous DB write should reduce
  latency"), not a measured result — see `testing.md` for what has and hasn't been verified.
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
- **No migration tool.** Schema changes apply via Hibernate `ddl-auto: update`; see
  `design-decisions.md` for the trade-off.

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
