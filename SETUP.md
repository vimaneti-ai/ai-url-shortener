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
docker compose up -d
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

Two separate images exist today — there is no single compose file that runs the whole stack
together (see `DEPLOYMENT.md` for the honest breakdown of what each Docker file does and doesn't
do). This is three manual steps, not one command:

**1. Infrastructure** (same as Option A):
```bash
docker compose up -d
```

**2. Backend image** — join the same Docker network `docker compose` created in step 1, and use
the containers' internal hostnames rather than `localhost`/`host.docker.internal`:

```bash
docker network ls | grep url_shortener   # confirm the actual network name (see note below)

docker build -t url-shortener .
docker run -p 8080:8080 --network <network-name-from-above> \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5431/url_shortener \
  -e SPRING_DATASOURCE_USERNAME=url_shortener_user \
  -e SPRING_DATASOURCE_PASSWORD=url_shortener_pass \
  -e SPRING_DATA_REDIS_HOST=redis \
  -e KAFKA_SERVERS=kafka:29092 \
  -e APP_BASE_URL=http://localhost:8080/api/v1 \
  url-shortener
```

This matters more than it looks: **`host.docker.internal` connects to Postgres and Redis fine, but
silently breaks Kafka.** Verified directly — with `host.docker.internal:9092`, the app starts,
reports healthy, and shortening/redirecting both work, but clicks are never actually recorded
(`totalClicks` stays `0` forever). The Kafka broker's `EXTERNAL` listener advertises itself as
`localhost:9092` (see `docker-compose.yml`), which resolves to the *backend container itself* from
inside another container — not the Kafka container — so the consumer can never successfully
rebalance. Attaching to the compose network and using `kafka:29092` (the broker's internal
`PLAINTEXT` listener) avoids this entirely; confirmed a click was actually persisted and returned
by `GET /api/v1/analytics/{code}` this way.

The network name `docker compose` creates is `<parent-directory-name>_url_shortener` by default
(e.g. `ai-url-shortener_url_shortener` if you cloned into a folder called `ai-url-shortener`) — run
the `docker network ls` command above rather than assuming the name, since it depends on what you
named the folder.

**3. Frontend image:**
```bash
docker build -t url-shortener-frontend ./frontend
docker run -p 4200:80 url-shortener-frontend
```
**This alone will serve the UI but every API call will 404.** The frontend's nginx has no `/api/*`
proxy rule baked in — see `DEPLOYMENT.md`'s "Deploying the frontend" section for the nginx config
needed to actually connect it to the backend container. For local development, Option A avoids
this problem entirely.

## Troubleshooting

**"Port already in use" on 8080 or 4200** — something (often a previous run of this same app) is
already listening there:
```bash
lsof -ti :8080 | xargs kill -9   # or :4200
```

**Backend health check returns `DOWN`** — check `docker ps` for the three
`url_shortener_*` containers; if they're not `Up`, `docker compose up -d` again. A `DOWN`
aggregate status with `/actuator/health/liveness` and `/actuator/health/readiness` both `UP`
means the app process is fine but can't reach Postgres/Redis/Kafka.

**Frontend loads but every request fails** — confirm the backend is actually running and
reachable at `:8080` before starting `ng serve`; the dev-server proxy doesn't retry or queue
requests while the backend is down.
