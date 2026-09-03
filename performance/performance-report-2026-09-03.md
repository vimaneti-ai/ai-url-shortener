# Performance test report — 2026-09-03

- Date: 2026-09-03
- Git commit: `0f391d9`
- Target: local Docker Compose stack on macOS
- Load generator host: Apple M3 (`Mac15,12`), 16 GiB RAM
- Docker version: 28.5.1
- k6 image: `grafana/k6:0.54.0`
- Profile: `load` (900 redirect VUs + 100 analytics VUs)
- Maximum concurrency: 1,000 virtual users
- Analytics recording: enabled
- Test duration: 661.2 seconds

## Result

**Overall threshold result: FAIL.** Functional correctness remained intact, but both endpoint
latency targets were exceeded at full load.

| Measurement | Result | Threshold | Outcome |
| --- | ---: | ---: | :---: |
| Iterations | 204,952 | — | — |
| Request rate | 309.97 requests/second | — | — |
| Checks passed | 409,904 / 409,904 (100%) | >99% | PASS |
| HTTP request failures | 0% | — | PASS |
| Redirect failures | 0% | <1% | PASS |
| Redirect latency p95 | 3,049.83 ms | <500 ms | FAIL |
| Redirect latency p99 | 3,983.36 ms | <1,000 ms | FAIL |
| Analytics failures | 0% | <1% | PASS |
| Analytics latency p95 | 3,434.45 ms | <750 ms | FAIL |
| Analytics latency p99 | 4,356.75 ms | <1,500 ms | FAIL |

Redirect latency averaged 1,216.62 ms and reached 7,940.96 ms maximum. Analytics latency averaged
1,499.85 ms and reached 11,520.33 ms maximum. The run generated 186,607 click events for the
dedicated `loadtest` link, confirming that the Kafka/click-persistence path was enabled.

## Interpretation

The run demonstrates that the application completed more than 200,000 redirect and analytics
iterations at 1,000 active virtual users without returning an incorrect response. It does **not**
demonstrate that the configured latency objectives were met. The load generator and all five
application containers shared one local Apple M3 machine, so these measurements characterize that
combined local environment rather than the production EC2 instance or an independently generated
load test.

CPU and memory utilization were not captured during this run, so the first saturated resource
cannot be identified from this evidence alone. A follow-up run should collect Docker CPU/memory,
PostgreSQL connection-pool activity, Redis latency/hit ratio, Kafka lag, and JVM metrics before
changing thresholds or tuning the application.

The raw k6 output was written to `performance/results/summary.json`; that generated file remains
ignored because it is environment-specific and can be regenerated. This dated report preserves the
reviewable assessment evidence.
