# Deployment

This covers running both apps outside local development, and honestly documents what each existing
config file actually does versus what it looks like it does at a glance.

## What's actually containerized

- **`Dockerfile`** (repo root) — multi-stage build (Maven build stage → `eclipse-temurin:21-jre-jammy`
  runtime) producing a single runnable jar. Builds and packages the **backend only**.
- **`frontend/Dockerfile`** — multi-stage build (`node:20.12.1-alpine` → `nginx:alpine`) that builds
  the Angular app and serves the static output via nginx on port 80. It builds and serves the
  **frontend only** — nginx here does not proxy `/api/*` anywhere, so see "Deploying the frontend"
  below before assuming this image alone is a working deployment.
- **`docker-compose.yml`** — Postgres, Redis, and Kafka (KRaft mode) for **local development only**.
  It does not include either application; you run those with `./mvnw spring-boot:run` and
  `npx ng serve` alongside it.
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

## Deploying the frontend

`frontend/src/app/services/api.service.ts` calls the backend via **relative URLs**
(`/api/v1/...`). In local development, Angular's dev-server proxy (`proxy.conf.json`) forwards
those to `localhost:8080`. `frontend/Dockerfile` builds and serves the static app via nginx, but
**does not** include an nginx proxy rule for `/api/*` — as shipped, a container built from it will
serve the UI correctly and then 404 on every single API call, because nginx has nowhere to send
them. Before this image is actually usable end-to-end, either:

- add an nginx `location /api/ { proxy_pass http://<backend-host>:8080; }` block (and one for
  `/actuator/` if you want health checks reachable) to a custom `nginx.conf` and `COPY` it into the
  image, pointed at wherever the backend container/service actually runs, or
- put both containers behind a shared reverse proxy / API gateway that does the same routing
  externally, rather than inside the frontend's own nginx.

```bash
docker build -t url-shortener-frontend ./frontend
docker run -p 8080:80 url-shortener-frontend   # UI only, until an /api/ proxy rule is added
```

Building without Docker is the same `ng build` the image runs internally:

```bash
cd frontend
npm install
npx ng build --configuration production
```

Output goes to `frontend/dist/url-shortener-ui/browser/` (Angular 17's application builder nests
static output one level under `browser/`; `frontend/Dockerfile` already accounts for this).
