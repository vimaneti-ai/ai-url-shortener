# Deployment

This covers running both apps outside local development, and honestly documents what each existing
config file actually does versus what it looks like it does at a glance.

> **Live deployment:** [https://short.vinodmaneti.com](https://short.vinodmaneti.com) on a
> single AWS EC2 instance. DNS points to Elastic IP `16.59.235.190`; system nginx terminates TLS
> with a Let's Encrypt certificate managed by Certbot.

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
  -e APP_BASE_URL=https://your-domain.example \
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

**Production schema changes use Hibernate** `ddl-auto: update`; deployment has no separate Flyway
step. Flyway is test-scoped and applies the V1–V4 scripts to a clean PostgreSQL Testcontainer in
`DatabaseMigrationIT`, but it does not migrate the existing EC2 database. Hibernate `update` does
not reliably remove obsolete schema objects, so review production schema changes rather than
assuming the test migration history was executed during deployment.

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

On the current AWS host, system nginx is the public reverse proxy. It terminates TLS for
`https://short.vinodmaneti.com`, redirects HTTP to HTTPS, and forwards requests to the containerized
frontend on `127.0.0.1:4200`. Do not publicly expose the frontend container, Postgres, Redis, Kafka,
or backend ports.

The frontend proxies only the exact `/actuator/health` path and returns `404` for other
`/actuator/*` paths. Spring Boot also exposes `metrics` and `prometheus` internally, while health
details and component names remain disabled. Do not expose backend port `8080` through an EC2
security group or public load balancer, because nginx is the intended public boundary.

Inspect internal production metrics through an EC2 shell or SSM Run Command:

```bash
curl -s http://localhost:8080/actuator/metrics | jq
curl -s http://localhost:8080/actuator/metrics/http.server.requests | jq
curl -s http://localhost:8080/actuator/prometheus | head -40
```

From outside EC2, `/actuator/metrics` and `/actuator/prometheus` should return `404`; this is a
deliberate security boundary, not a missing endpoint.

## Running on a memory-constrained EC2 instance

Keep local-development defaults in `docker-compose.yml`; do not copy production tuning into the
base file. On EC2, set `FRONTEND_PORT=4200`, a strong `POSTGRES_PASSWORD`, and the following public
URL in the ignored `.env`, then layer the tracked production override on top:

```env
APP_BASE_URL=https://short.vinodmaneti.com
```

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
```

The override caps the seven services at approximately 2.05 GB total and binds Postgres, Redis,
Kafka, the backend, the containerized frontend, Prometheus, and Grafana to `127.0.0.1`. System
nginx is the only public application listener. The EC2 security group should independently allow
only `22` from an administrator IP and `80`/`443` publicly.

The DNS `A` record for `short.vinodmaneti.com` points to Elastic IP `16.59.235.190`. Certbot manages
the Let's Encrypt certificate and nginx redirect; validate renewal after installation with:

```bash
sudo certbot renew --dry-run
```

### EC2 nginx and HTTPS

Install nginx on the host and forward public traffic to the localhost-only frontend container:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name short.vinodmaneti.com;

    location / {
        proxy_pass http://127.0.0.1:4200;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Save that server block as `/etc/nginx/sites-available/url-shortener`, enable it in
`/etc/nginx/sites-enabled/`, and remove the enabled default site. Then validate and reload:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

After DNS resolves to the Elastic IP and ports 80/443 are allowed by the EC2 security group,
install Certbot and provision the certificate:

```bash
sudo snap install core
sudo snap refresh core
sudo snap install --classic certbot
sudo ln -sf /snap/bin/certbot /usr/local/bin/certbot
sudo certbot --nginx -d short.vinodmaneti.com
```

Confirm HTTP redirects and the proxied health endpoint remains available over HTTPS:

```bash
curl -I http://short.vinodmaneti.com
curl https://short.vinodmaneti.com/actuator/health
```

## Monitoring: Prometheus + Grafana

`docker compose up -d` already starts Prometheus and Grafana alongside the other five services —
they're part of the same `docker-compose.yml`, not a separate stack. Config lives in `monitoring/`:

- `monitoring/prometheus.yml` — scrapes `backend:8080/actuator/prometheus` every 15s over the
  internal Docker network. No public exposure change is needed for this to work.
- `monitoring/grafana/provisioning/` — auto-provisions the Prometheus datasource and a starter
  dashboard (`url-shortener.json`: JVM heap, CPU, HTTP request rate by path, DB connections) on
  boot, so there's nothing to click through manually after a fresh deploy.

**In production**, both bind to `127.0.0.1` via `docker-compose.prod.yml` (same pattern as every
other service) and Grafana is additionally configured with:

```yaml
GF_SERVER_ROOT_URL: https://short.vinodmaneti.com/grafana/
GF_SERVER_SERVE_FROM_SUB_PATH: "true"
```

so it generates correct links when served under a subpath. The public route itself —
`location /grafana/ { proxy_pass http://127.0.0.1:3000; ... }` in the system nginx config — is
**not tracked in this repo** (it lives only in `/etc/nginx/sites-available/url-shortener` on the
EC2 host, the same untracked file the base HTTPS server block lives in). If the host is ever
rebuilt, that block has to be re-added by hand; it isn't recreated by the deploy pipeline.

One easy mistake to avoid if you ever touch that nginx block: `proxy_pass` must have **no trailing
slash** after the port (`http://127.0.0.1:3000;`, not `.../3000/;`). With the trailing slash, nginx
strips the `/grafana/` prefix before forwarding, but Grafana (with `SERVE_FROM_SUB_PATH=true`)
expects to receive the full path — the mismatch causes Grafana to redirect `/grafana/` back to
itself in an infinite loop. Confirmed by hitting exactly this bug on the first deploy.

**The admin password** comes from a GitHub Actions repository secret (`GRAFANA_ADMIN_PASSWORD`),
injected into the EC2 `.env` file at deploy time — see "GitHub Actions CI/CD" below for exactly how
that avoids the raw password ever sitting inside a quote-sensitive shell string. Set that secret
before your first deploy after adding this; without it, Grafana falls back to the compose file's
`change-me` default, which you do not want live.

## GitHub Actions CI/CD

`.github/workflows/ci-deploy.yml` validates pull requests and pushes with four ordered jobs:

1. Backend tests and the JaCoCo gate run in parallel with frontend tests and the production build.
2. Both Docker images and the merged production Compose configuration are validated after tests pass.
3. A push to `main` assumes a repository-scoped AWS role through GitHub OIDC.
4. AWS Systems Manager Run Command updates the EC2 checkout, recreates the Compose stack, and waits
   for every container health check to pass.
5. The workflow polls the public HTTPS health endpoint and fails unless it returns an `UP` status.

The deploy job uses the GitHub `production` environment with these environment variables:

```text
AWS_ROLE_ARN=arn:aws:iam::227498831542:role/GitHubActionsUrlShortenerDeployRole
AWS_REGION=us-east-2
EC2_INSTANCE_ID=i-0ac29993035d7d5ee
```

No EC2 private key or long-lived AWS access key is stored in GitHub. The OIDC role may only send
`AWS-RunShellScript` to this instance and read that command's result. EC2 itself uses an instance
role with `AmazonSSMManagedInstanceCore` so the SSM agent can receive commands.

The deploy step also reads one repository **secret**, `GRAFANA_ADMIN_PASSWORD`, and writes/updates
it in the EC2 `.env` before bringing the stack up (upserting the line rather than assuming it's
already there). The value is base64-encoded on the runner before it's embedded in the remote
script and decoded again only inside the final single-quoted `bash -lc '...'` on the EC2 side — it
never sits in the SSM JSON payload or an intermediate shell string in its raw form, so a password
containing a `'` or `$` can't break the quoting at any layer. See `design-decisions.md` for why
this matters and how it was verified.

Before deployment, the workflow checks that the ignored EC2 `.env` exists and contains exactly:

```env
FRONTEND_PORT=4200
APP_BASE_URL=https://short.vinodmaneti.com
```

It then fetches `main` and runs `git reset --hard origin/main`. Tracked EC2 edits are intentionally
discarded so the server matches GitHub exactly; the ignored `.env` and Docker volumes remain. Make
all source-controlled changes locally and push them rather than editing tracked files on EC2.

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
