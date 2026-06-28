# CLAUDE.md

Operating guide for this repo. For the full architecture diagram, Kafka topics, data
model, and API reference, see **`README.md`** — this file is the quick map.

## Project

**D&D AI** — a multiplayer Dungeons & Dragons web app where an LLM acts as the Dungeon
Master. Players join sessions by invite code, take turns describing actions over a
WebSocket, and an Ollama-backed DM narrates responses while keeping world consistency via
RAG (pgvector).

> **Layout note:** the project lives at the repo root — the Spring Boot backend is at the
> root (`pom.xml`, `src/`), and the Next.js frontend is in `frontend/`.

## ⚠️ Frontend work directive (read first)

**Any frontend / UI / UX task** — new pages, new components, styling, layout changes,
design tweaks — **MUST first invoke the `ui-ux-pro-max` skill (`/ui-ux-pro-max`)** for
design guidance *before* writing UI code. Then:

- Keep the established **dark red fantasy-tabletop theme** (accent `#dc2626` on warm
  near-black; gold `--color-gold` as a sparing secondary accent).
- **Reuse the primitives in `frontend/src/components/ui/`** (`Button`, `Panel`, `Brand`,
  `Field`, `Divider`, `Spinner`) — don't hand-roll button/panel/input styles.
- All design tokens live in `frontend/src/app/globals.css` (`@theme`); fonts are wired in
  `layout.tsx` via `next/font` (Cinzel display, Inter body, JetBrains Mono for codes/stats).
- Tailwind **v4** — CSS-first config, there is **no `tailwind.config.js`**.

## Run / dev commands

Run from the repo root unless noted.

| Task | Command |
|---|---|
| Backend (dev, mock auth) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (Windows: `mvnw.cmd`) |
| Backend tests | `./mvnw test` |
| Frontend dev (port 3000) | `cd frontend && npm install && npm run dev` |
| Frontend build / lint | `npm run build` · `npm run lint` |
| Infra only | `docker compose up postgres kafka keycloak` |
| Full stack | `docker compose up --build` |
| First-run model pull (on the **host**) | `ollama pull gemma4:e4b && ollama pull bge-m3` |

`docker-compose.yml` lives at the repo root. The **dev** profile uses mock auth — no
Keycloak setup needed.

## Architecture map

```
Next.js frontend  ⇄  Spring Boot backend  ⇄  Kafka (KRaft)
   (REST /api/*  +  STOMP /ws)                 │
                              ┌────────────────┼────────────────┐
                         PostgreSQL+pgvector   Ollama        Keycloak
```

See `README.md` for the full diagram, the four `game.*` Kafka topics, and the table-level
data model.

## Backend layout — `src/main/java/com/dungeon/master/`

- `controller/` + `websocket/` — REST and STOMP entry points (+ JWT channel interceptor)
- `service/ai/` — `DmAiService`, `RagService`, `EmbeddingService`
- `service/game/` — `GameSession`, `Turn`, `Player`, `Character` services
- `kafka/` — `producer/` + `consumer/` (action, dm-response, turn, session)
- `model/{entity,dto,enums}`, `repository/`, `config/`, `exception/`
- Flyway migrations: `src/main/resources/db/migration` (V1–V3). **Add a new
  `V{n}__*.sql`; never edit an already-applied migration.**

## Frontend layout — `frontend/src/`

- `app/` — App Router pages (`login`, `/` landing, `characters`, `characters/new`,
  `characters/[id]/edit`, `lobby/[sessionId]`, `game/[sessionId]`)
- `components/` — `RequireAuth`, plus `components/ui/` design primitives
- `lib/` — `api.ts` (REST), `websocket.ts` (STOMP), `dnd5e.ts` (D&D math/data)
- `context/AuthContext.tsx` — Keycloak auth · `types/` — shared TS types

## Conventions / gotchas

- **Dev auth:** the dev profile accepts an `X-User` header as identity; for WebSocket
  STOMP, the username is passed as the Bearer token.
- **Client storage:** per-session keys in `localStorage` — `dnd-playerId-<sessionId>`,
  `dnd-joinCode-<sessionId>`, `dnd-username`.
- **Lobby ⇄ game:** `lobby/[sessionId]` is one page that switches view on session status
  (`WAITING` → lobby, `ACTIVE`/`FINISHED` → chat room). The standalone `game/[sessionId]`
  page is the older/duplicate chat variant.
- **Presentation vs logic:** API calls, WebSocket helpers, D&D math, and auth are settled
  — UI work should not change their behavior.

## AI / LLM

- **Inference runs on the host's Ollama**, not a container. In Docker the backend reaches it
  via `host.docker.internal:11434` (set in `docker-compose.yml`); for local `mvnw` runs it
  uses `localhost:11434`. Make sure host Ollama listens on `0.0.0.0` (`OLLAMA_HOST=0.0.0.0`).
- **Chat model:** `gemma4:e4b` via Ollama, configured in `application.yml`
  (`spring.ai.ollama.chat.options.model`).
- **Embedding model:** `bge-m3` with **1024-dim** pgvector. Do **not** change the embedding
  model casually — a different model can change vector dimensionality and break the pgvector
  schema and stored embeddings (the dimension lives in both `application.yml` and the
  `world_documents.embedding` column; changing it needs a new Flyway migration).
