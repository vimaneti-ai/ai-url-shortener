# Setup

Everything needed to get this running locally — as two native processes talking to
Dockerized infrastructure (the fast, recommended path for actual development), or fully
containerized (slower to iterate on, closer to how it'd actually be deployed). See `DEPLOYMENT.md`
for deploying this beyond your own machine.

## Prerequisites

- Java 21
- Maven 3+ (or just use the bundled `./mvnw` / `mvnw.cmd` — no separate Maven install needed)
- Node.js 18+ and npm
- Docker & Docker Compose

## Clone and install

```bash
git clone <this-repo-url>
cd ai-url-shortener

cd frontend && npm install && cd ..
```

There's no install step for the backend beyond having a JDK — Maven resolves dependencies on
first build/run.

## Option A — Local development (recommended)

Infrastructure runs in Docker; both apps run natively so you get fast rebuilds, debugger
attachment, and Angular's live-reload dev server.

**1. Start Postgres, Redis, and Kafka:**
```bash
docker compose up -d postgres redis kafka
```
This starts Postgres on `:5431`, Redis on `:6379`, and Kafka (KRaft mode) on `:9092`. First run
pulls the images, so it'll take a minute; subsequent runs are fast.

**2. Start the backend** (new terminal):
```bash
./mvnw spring-boot:run
```
Starts on `:8080`. Hibernate creates/updates the schema automatically (`ddl-auto: update`) — no
separate migration command to run. Confirm it's actually up before moving on:
```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ...}
```

**3. Start the frontend** (new terminal):
```bash
cd frontend
npx ng serve
```
Starts on `:4200` and proxies `/api/*` and `/actuator/*` to `:8080` (see
`frontend/proxy.conf.json`) — that's why the backend needs to already be running.

**4. Open it**: [http://localhost:4200](http://localhost:4200)

**5. Stop everything**:
```bash
# Ctrl+C the two `./mvnw` / `ng serve` terminals, then:
docker compose down
```

## Option B — Fully via Docker

Compose builds and runs all five services on one network. Create your local environment file once:

```bash
cp .env.example .env
# Edit .env and replace POSTGRES_PASSWORD=change-me
docker compose up --build -d
```

Open [http://localhost:4200](http://localhost:4200). The backend uses the service names
`postgres`, `redis`, and `kafka`; nginx forwards `/api/*` and `/actuator/*` to `backend:8080`.
Health-aware `depends_on` conditions prevent dependent services from starting prematurely.

Check status or logs with `docker compose ps` and `docker compose logs -f`. Stop all containers
with `docker compose down`; add `-v` only when you intentionally want to delete PostgreSQL data.

The base Compose file intentionally has no EC2 memory caps and exposes development ports normally.
For EC2, use the tracked `docker-compose.prod.yml` override described in `DEPLOYMENT.md`.

## Troubleshooting

**"Port already in use" on 8080 or 4200** — something (often a previous run of this same app) is
already listening there:
```bash
lsof -ti :8080 | xargs kill -9   # or :4200
```

**Backend health check returns `DOWN`** — check `docker compose ps` for all five
`url_shortener_*` containers; if they're not `Up`, `docker compose up -d` again. A `DOWN`
aggregate status means the application cannot reach one of its required dependencies; inspect
`docker compose logs backend` for the failing connection.

Only the basic `/actuator/health` response is proxied through the containerized frontend. Other
actuator paths intentionally return `404` and health component details are disabled.

**Frontend loads but every request fails** — confirm the backend is actually running and
reachable at `:8080` before starting `ng serve`; the dev-server proxy doesn't retry or queue
requests while the backend is down.
