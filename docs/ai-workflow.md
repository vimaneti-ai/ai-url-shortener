# AI-Assisted Development Process

This project was built iteratively with Claude, one requirement at a time. Every change in this repo followed the same loop, not just "ask AI, paste code":

**Requirement → Investigate → Options (when genuinely ambiguous) → Implement → Test → Verify live → Document**

The "investigate" and "verify live" steps did the most work. Several of the walkthroughs below exist *because* something that looked done on paper turned out not to work when actually exercised — a stubbed dependency, a stale cache assumption, a status code that was never checked. AI-generated code that hasn't been run against the real system is a draft, not a result.

Below are three real walkthroughs from this repo's history, picked to represent three different kinds of work: building something from nothing, safely reshaping something that already existed and was already tested, and handling a requirement that changed shape partway through.

### 1. Greenfield — OpenAPI/Swagger documentation

**Requirement**
> "OpenAPI/Swagger docs — not yet present."

**Investigation & options**
Nothing to build on here, but also a real compatibility risk to check before writing any code: this project runs `spring-boot-starter-parent` 4.0.2 (Spring Framework 7-era), and `springdoc-openapi`'s latest published release (2.8.6) targets Spring Boot 3. A major-version Spring bump is exactly the kind of thing that breaks MVC auto-configuration libraries. Rather than guess, the dependency was added and tested directly against a running instance before writing any documentation claiming it worked.

**Review & edits**
- Added `springdoc-openapi-starter-webmvc-ui:2.8.6` to `pom.xml`
- Added `OpenApiConfig` (title/description/version bean) — `src/main/java/.../config/OpenApiConfig.java`
- Annotated every `urlController` method with `@Operation` / `@ApiResponses`. This wasn't cosmetic: springdoc can't infer real HTTP status codes from a bare `ResponseEntity<T>` return type, so the *unannotated* auto-generated spec claimed every endpoint returns `200 OK` — including the ones that actually return `201`, `204`, `404`, `409`, or `410`. Left as-is, the "documentation" would have been actively wrong.

**Tests**
`./mvnw verify` — passing, coverage gate held (config classes are already excluded from the JaCoCo rule by project convention, so this added no test burden).

**Verify live, not just read the diff**
- Started the app, confirmed no context-startup errors
- Pulled `/v3/api-docs` and diffed the status codes per path against what the annotations declared — `PUT → [200,400,404]`, `DELETE → [204,404]`, `POST /shorten → [201,400,409]`, `GET /{code} → [302,404,410]` — all correct
- Took a full-page Playwright screenshot of `/swagger-ui/index.html` to confirm it actually renders (colors, schemas, descriptions) rather than trusting a 200 status code alone

**Docs**
README: new Features bullet, a pointer to `/swagger-ui/index.html` and `/v3/api-docs` under the REST API section, and a project memory recording that 2.8.6-on-Boot-4 is confirmed working (so it isn't re-litigated or upgraded "just in case" later).

---

### 2. Brownfield — aligning API paths and field names to an external spec

**Requirement**
> "API path convention — assessment wants `/api/v1/shorten`, `/api/v1/analytics/{code}` with versioned paths and specific field names (`shortUrl`, `expiresAt`, `uniqueVisitors`, `countries` breakdown). Your existing repo used slightly different naming — worth aligning to match exactly what's asked."

This is the harder failure mode than greenfield: the existing `/api/urls` surface was already working, already had passing tests, and the frontend already depended on its exact shape. Every rename risked silently breaking something adjacent.

**Investigation & options**
Reading the existing controller and DTOs surfaced two decisions that weren't spelled out in the requirement, so they went to the user as explicit choices rather than silent defaults:

1. `countries` breakdown requires resolving IP → country, which the app didn't do at all yet. Two options were presented: *stub it as `"Unknown"`* (matches the contract shape with zero new risk) vs. *call a real free GeoIP API* (actually functional, but a new external dependency). → chose the real API.
2. The redirect endpoint (`GET /api/urls/{code}`) wasn't mentioned in the spec at all. Presented as: *leave it alone* vs. *move it to `/api/v1/{code}` too*, since leaving it split would mean the `shortUrl` field the API itself returns wouldn't actually resolve under the new convention. → chose to move it, for internal consistency.

**Review & edits**
- Renamed `expirationTime` → `expiresAt` across `URLRequest`, `URLResponse`, and the Angular models/service/component that send them
- Re-mapped the controller: `POST /api/v1/shorten`, `GET /api/v1/analytics/{code}`, `GET /api/v1/{code}`
- Added `uniqueVisitors` (distinct IP count) and `countries` (`Map<String,Long>`) to `AnalyticsResponse`, computed in `AnalyticsService`
- Built `GeoIpService` from scratch to back the `countries` field (see the ambiguous-requirement walkthrough below for how this later had to be redone)
- Fixed a real naming inconsistency surfaced along the way: `AnalyticsResponse.shortUrl` used to hold just the bare code, while `URLResponse.shortUrl` held the full URL — same field name, two different meanings. Split into `shortUrl` (full URL) + `shortCode` (bare code) consistently on both DTOs.

**Tests**
Updated all existing test files whose constructors/mocks broke from the DTO and signature changes. Adding the untested `GeoIpService` dropped bundle coverage to 79% against JaCoCo's 80% gate — caught by running `mvn verify`, not by inspection — so a `GeoIpServiceTest` covering its network-free branches was added to bring it back over the line.

**Verify live**
Started Postgres/Redis/Kafka via Docker, ran the backend and frontend together, and drove the full flow with `curl`: create → confirmed the new field names in the JSON response → redirect → confirmed a `302` → analytics → confirmed `uniqueVisitors`/`countries` populate correctly. Then repeated the same flow through the actual Angular UI with Playwright screenshots, because a passing `curl` test doesn't prove the frontend's `ShortenRequest`/`AnalyticsResponse` interfaces were updated to match.

**Docs**
Rewrote every request/response example in the README's REST API section to the new shape, and updated path references in the design docs so they didn't quietly disagree with the code.

---

### 3. Ambiguous / evolving requirement — country-level analytics

This one is really two requirements a few turns apart, and the second one invalidated an assumption baked into the first.

**Requirement (first pass, inside the brownfield task above)**
The `countries` field needed *something* real. The two-option question above ("stub vs. real API") was the ambiguity-resolution point — the user picked the real free API, so `GeoIpService` was built against `ipapi.co` with a graceful `"Unknown"` fallback for private IPs and lookup failures. It shipped, tests passed, `mvn verify` was green.

**Requirement (second pass)**
> "Country-level analytics — your current click_events table stores IP/user-agent but not resolved geography — this is the 'geo-lookup' feature we discussed earlier, now it's actually required, not optional."

That phrase — *required, not optional* — was the signal to stop trusting the earlier "it shipped, tests are green" and re-verify against reality, because every test and every manual check up to that point had only ever exercised loopback/private IPs (which correctly return `"Unknown"` without calling out to the network at all). **Nothing had ever proven the real API path actually worked.**

**Investigation**
Ran a direct `curl` against `ipapi.co` with a real public IP. Result: `403`, a Cloudflare bot-challenge page — not a rate limit, not a fluke. It returned the same challenge page with a browser User-Agent too. The feature that "shipped" would have silently returned `"Unknown"` for every real visitor in production, and the existing test suite had no way of catching this because it never made a real network call. This is exactly the gap between "code that compiles and passes mocked tests" and "code that works."

**Options considered (technical, not user-facing — resolved directly since it's an implementation detail, not a scope decision)**
Tested two alternative free providers live before picking one: `ip-api.com` (works, but HTTP-only on the free tier) and `ipwho.is` (works, HTTPS, clean JSON). Chose `ipwho.is` as primary with `ip-api.com` as a fallback, rather than a single point of failure again.

**Review & edits**
- Rewrote `GeoIpService`: dual-provider lookup with fallback, proper JSON parsing via the app's existing `ObjectMapper` (the old version did fragile string-matching on the response body), and a Redis cache (`geoip:{ip}`, 24h TTL) so repeat visitors don't trigger a new external call on every click
- No controller or DTO changes needed — the contract from the brownfield pass was already correct, only the implementation underneath it was broken

**Tests**
Rewrote `GeoIpServiceTest` with a mocked `HttpClient` (injected via `ReflectionTestUtils`, no real network calls in the suite) covering cache hits, primary-provider success, fallback-to-secondary, both-providers-fail, malformed payloads, and recovery after a transient provider failure. The class now has 10 tests.

**Verify live**
Mocks proving the *fallback logic* works aren't the same as proving the *feature* works, so: spoofed real public IPs via the `X-Forwarded-For` header on an actual redirect request (`8.8.8.8` → `"United States"`, `1.1.1.1` → `"Australia"`) and confirmed the resolved countries in the live analytics response. Then checked Redis directly with `redis-cli` to confirm the results were actually cached with the correct ~24h TTL, not just working by coincidence.

**Docs**
Rewrote the README's country-resolution note to describe the actual dual-provider + caching mechanism, and — because this exact failure mode (an API that looks fine until you hit it with real traffic) is easy to reintroduce by accident — saved a persistent project memory explicitly recording that `ipapi.co` is a dead end here, so a future session doesn't spend time reinventing this investigation.

---

### Postscript: the documentation set you're reading was itself caught by this loop

An earlier version of `docs/` (a `High-Level-Design.md`/`Low-Level-Design.md`/`Interview-QnA.md`
split) accumulated exactly the kind of drift this whole process is meant to catch: unsupported
production claims (99.99% uptime, sub-10ms latency, quoted with no benchmark behind them), a
`Redis cache hits never touch the database` claim that was checked against the actual
`UrlService.resolveLongUrl` code and found false (it queries Postgres on every redirect, cache hit
or not, to check expiry), a fabricated self-domain-redirect-blocking claim with no corresponding
code anywhere, and two screenshots left over from a UI that had already been deleted. All of it was
caught the same way as everything else here — by checking specific claims against the actual
code and running system, not by re-reading the prose more carefully — and this doc set
(`architecture.md`, `design-decisions.md`, `testing.md`, `ai-workflow.md`, `requirements.md`)
replaced it.

---

Across all of this (and the smaller iterations in between — PUT/DELETE endpoints, duplicate-URL detection, the frontend redesign), the throughline is the same: treat "the AI wrote code for it" and "it works" as two separate claims, and only assert the second one after actually running it.
