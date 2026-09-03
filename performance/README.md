# Performance testing

The k6 test models the two public read paths with a 90/10 traffic split:

- 900 virtual users repeatedly request `GET /{shortCode}` without following the destination.
- 100 virtual users repeatedly request `GET /api/v1/analytics/{shortCode}`.

The `load` profile ramps to and holds **1,000 concurrent virtual users for five minutes**. It is an
11-minute capacity test, not a claim that the current EC2 instance supports 1,000 users. A run only
passes when more than 99% of checks succeed, endpoint failure rates stay below 1%, redirect p95 is
below 500 ms, and analytics p95 is below 750 ms. The script also records p99 thresholds.

## 1. Create a dedicated test link

Start the stack, then create a link whose analytics can safely be changed by the test:

```bash
docker compose up -d --build
curl -sS -X POST http://localhost:4200/api/v1/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","customAlias":"loadtest"}'
```

If `loadtest` already exists, use its code or create another alias. Do not use a real user's link.

## 2. Run the 10-second smoke profile

This uses the official k6 Docker image, so k6 does not need to be installed:

```bash
mkdir -p performance/results
docker run --rm \
  -e PROFILE=smoke \
  -e BASE_URL=http://host.docker.internal:4200 \
  -e SHORT_CODE=loadtest \
  -e RECORD_ANALYTICS=false \
  -v "$PWD/performance:/scripts" \
  -w /scripts \
  grafana/k6:0.54.0 run url-shortener-load.js
```

`RECORD_ANALYTICS=false` sends `Purpose: prefetch`, so redirects are resolved normally but click
events are not published. This is useful for a non-mutating smoke check.

## 3. Run the 1,000-user profile

Run this against a controlled environment, not the public EC2 deployment:

```bash
docker run --rm \
  -e PROFILE=load \
  -e BASE_URL=http://host.docker.internal:4200 \
  -e SHORT_CODE=loadtest \
  -e RECORD_ANALYTICS=true \
  -v "$PWD/performance:/scripts" \
  -w /scripts \
  grafana/k6:0.54.0 run url-shortener-load.js
```

With `RECORD_ANALYTICS=true`, redirects exercise Kafka and click persistence as well as Redis lookup.
This intentionally creates many click rows. Monitor Docker/host CPU and memory during the run and
delete the dedicated link afterward if the data is no longer needed.

The script refuses a `PROFILE=load` run against `https://short.vinodmaneti.com` unless
`ALLOW_PRODUCTION=true` is also supplied. That override is deliberately conspicuous: 1,000 virtual
users can exhaust a small EC2 instance and affect public availability.

## Results

Every run writes `performance/results/summary.json`. Generated results are ignored by Git; copy
`REPORT_TEMPLATE.md` to a dated Markdown report and record the environment, commit, command,
threshold outcome, and measured percentiles when preserving assessment evidence.

The smoke profile was validated locally on 2026-09-03: 22 iterations, 100% checks passed, zero
redirect or analytics failures, redirect p95 15.99 ms, and analytics p95 23.43 ms. This confirms the
script works against the local Compose boundary; it does not demonstrate 1,000-user capacity.

The full profile was also executed locally on 2026-09-03. It reached 1,000 VUs, completed 204,952
iterations with 100% correct checks and zero endpoint failures, but failed the latency thresholds
(redirect p95 3,049.83 ms; analytics p95 3,434.45 ms). See
[`performance-report-2026-09-03.md`](performance-report-2026-09-03.md). This is useful capacity-test
evidence, but it is not a passing latency result.

The test does not follow `302` responses, so destination-site latency is not mixed into application
latency and the target site does not receive synthetic traffic.
