# Deployment

This covers running both apps outside local development, and honestly documents what each existing
config file actually does versus what it looks like it does at a glance.

## What's actually containerized

- **`Dockerfile`** (repo root) — multi-stage build (Maven build stage → `eclipse-temurin:21-jre-jammy`
  runtime) producing a single runnable jar. Builds and packages the **backend only**.
- **`frontend/Dockerfile`** — multi-stage build (`node:20.12.1-alpine` → `nginx:alpine`) that builds
  the Angular app and serves it through nginx on port 80. `frontend/nginx.conf` proxies `/api/*`
  and `/actuator/*` to the Compose backend service and supports SPA route fallback.
- **`docker-compose.yml`** — builds and runs PostgreSQL, Redis, Kafka, the Spring Boot backend, and
  the Angular frontend together. Health checks and conditional dependencies enforce startup order.
- **`docker-compose.prod.yml`** — EC2-specific runtime overrides: memory caps, JVM/Kafka heap
  tuning, smaller Postgres/Redis footprints, and localhost-only bindings for internal services.
  It must be layered on top of the base file, not run by itself.
- **`.env.example`** — committed configuration template. Copy it to the ignored `.env` file and
  supply local/deployment-specific values before starting Compose.
- **`render.yaml`** — a Render.com service definition for the **backend** jar only, running as a
  Docker web service. It does not provision Postgres, Redis, or Kafka, and does not deploy the
  frontend at all — see below for both.

## Building and running the backend image

```bash
docker build -t url-shortener .
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/url_shortener \
  -e SPRING_DATASOURCE_USERNAME=<user> \
  -e SPRING_DATASOURCE_PASSWORD=<password> \
  -e SPRING_DATA_REDIS_HOST=<redis-host> \
  -e SPRING_DATA_REDIS_PORT=6379 \
  -e SPRING_DATA_REDIS_SSL=true \
  -e KAFKA_SERVERS=<kafka-bootstrap-host:port> \
  -e APP_BASE_URL=https://your-domain.example/api/v1 \
  -e RATE_LIMIT=20 \
  url-shortener
```

All of these map directly to `${...}` placeholders in `src/main/resources/application.yaml` — that
file is the single source of truth for what's configurable and what each variable defaults to
locally. `SPRING_DATA_REDIS_PASSWORD` is also supported (empty by default).

**Kafka has bounded retries but no alternate analytics transport.** Producers use `acks=all`,
idempotence, and three delivery retries. Consumers retry transient processing failures with
exponential backoff. If `KAFKA_SERVERS` remains unreachable, redirects and shortening still work
because publishing is asynchronous, but click analytics stop recording. Permanently failed
consumer records are logged and skipped after retry exhaustion because no dead-letter topic is
configured. There's no managed Kafka
add-on assumed here; you need an external broker (self-hosted, Confluent Cloud, Upstash Kafka,
etc.) reachable from wherever the container runs.

**Redis is optional for availability, not performance.** Cache operations are attempted three
times with bounded exponential backoff. If Redis remains unavailable, URL operations fall back to
PostgreSQL and GeoIP lookups continue without caching, increasing latency and external API usage.

**Schema migrations run automatically and destructively-safely** via Hibernate
`ddl-auto: update` — there is no separate migration step, and no Flyway/Liquibase dependency
despite the `.sql` files under `src/main/resources/db/migration/` (kept as historical documentation
only, not executed). `update` only adds/alters, it does not drop columns removed from an entity, so
review the diff yourself before assuming a schema change is fully applied.

## Deploying to Render.com

`render.yaml` declares one `web` service (`env: docker`, `plan: free`) pointed at the `main` branch.
Before it will actually work, you need to:

1. Provision Postgres, Redis, and a Kafka broker somewhere Render can reach (Render's own managed
   Postgres/Redis, or third-party services — none of this is auto-provisioned by `render.yaml`).
2. Fill in the `sync: false` environment variables in the Render dashboard: `SPRING_DATASOURCE_URL`,
   `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`,
   `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD`, `KAFKA_SERVERS`, `APP_BASE_URL`
   (`sync: false` means Render won't set a value for you — it just reserves the variable name).
3. `SPRING_DATA_REDIS_SSL` and `RATE_LIMIT` already have defaults baked into `render.yaml`
   (`true` and `20`) — override in the dashboard only if you need something different.

`APP_BASE_URL` matters beyond configuration: it's what the API embeds in every `shortUrl` field it
returns, and it's the literal prefix redirects are served under. Get it wrong and every generated
short link points somewhere that doesn't resolve.

## Running the full Compose deployment

`frontend/src/app/services/api.service.ts` uses relative `/api/v1/...` URLs. Inside Compose,
`frontend/nginx.conf` forwards them to `backend:8080`, so the browser only needs the frontend URL.

```bash
cp .env.example .env
# Set production-safe values, especially POSTGRES_PASSWORD and APP_BASE_URL.
docker compose up --build -d
docker compose ps
```

On a single AWS host, expose the frontend through the load balancer/reverse proxy and avoid
publicly exposing the Postgres, Redis, Kafka, and backend ports. The checked-in port mappings are
for local verification and should be overridden or removed for an internet-facing deployment.

The frontend proxies only the exact `/actuator/health` path and returns `404` for other
`/actuator/*` paths. Spring Boot exposes only `health`, with health details and component
names disabled. Do not expose backend port `8080` directly through an EC2 security group or public
load balancer, because nginx is the intended public boundary.

## Running on a memory-constrained EC2 instance

Keep local-development defaults in `docker-compose.yml`; do not copy production tuning into the
base file. On EC2, set `FRONTEND_PORT=80`, a strong `POSTGRES_PASSWORD`, and the public
`APP_BASE_URL` in the ignored `.env`, then layer the tracked production override on top:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
```

The override caps the five services at approximately 1.7 GB total and binds Postgres, Redis,
Kafka, and the backend to `127.0.0.1`. Only frontend nginx binds publicly. The EC2 security group
should independently allow only `22` from an administrator IP and `80`/`443` publicly.

To inspect the fully merged configuration before starting it:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml config
```

To stop the same project, include the same files:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml down
```

Building without Docker is the same `ng build` the image runs internally:

```bash
cd frontend
npm install
npx ng build --configuration production
```

Output goes to `frontend/dist/url-shortener-ui/browser/` (Angular 17's application builder nests
static output one level under `browser/`; `frontend/Dockerfile` already accounts for this).
