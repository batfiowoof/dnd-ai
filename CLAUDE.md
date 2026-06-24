# CLAUDE.md

Operating guide for this repo. For the full architecture diagram, Kafka topics, data
model, and API reference, see **`dnd-ai/dnd-ai/README.md`** — this file is the quick map.

## Project

**D&D AI** — a multiplayer Dungeons & Dragons web app where an LLM acts as the Dungeon
Master. Players join sessions by invite code, take turns describing actions over a
WebSocket, and an Ollama-backed DM narrates responses while keeping world consistency via
RAG (pgvector).

> **Path note:** the actual project lives in **`dnd-ai/dnd-ai/`** — the Spring Boot
> backend is at the root of that folder, the Next.js frontend in `dnd-ai/dnd-ai/frontend/`.
> Ignore the stray `dnd-ai/untitled/` scaffold and the `python-3.10.6-amd64.exe` installer
> at the repo root; they are not part of the app.

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

Run from `dnd-ai/dnd-ai/` unless noted.

| Task | Command |
|---|---|
| Backend (dev, mock auth) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (Windows: `mvnw.cmd`) |
| Backend tests | `./mvnw test` |
| Frontend dev (port 3000) | `cd frontend && npm install && npm run dev` |
| Frontend build / lint | `npm run build` · `npm run lint` |
| Infra only | `docker compose up postgres kafka keycloak ollama` |
| Full stack | `docker compose up --build` |
| First-run model pull | `docker exec -it dnd-ollama ollama pull qwen3.5:4b` |

`docker-compose.yml` lives in `dnd-ai/dnd-ai/`. The **dev** profile uses mock auth — no
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

## Backend layout — `dnd-ai/dnd-ai/src/main/java/com/dungeon/master/`

- `controller/` + `websocket/` — REST and STOMP entry points (+ JWT channel interceptor)
- `service/ai/` — `DmAiService`, `RagService`, `EmbeddingService`
- `service/game/` — `GameSession`, `Turn`, `Player`, `Character` services
- `kafka/` — `producer/` + `consumer/` (action, dm-response, turn, session)
- `model/{entity,dto,enums}`, `repository/`, `config/`, `exception/`
- Flyway migrations: `src/main/resources/db/migration` (V1–V3). **Add a new
  `V{n}__*.sql`; never edit an already-applied migration.**

## Frontend layout — `dnd-ai/dnd-ai/frontend/src/`

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

- **Chat model:** `qwen3.5:4b` via Ollama, configured in `application.yml`
  (`spring.ai.ollama.chat.options.model`).
- **Embedding model:** `qwen2.5:7b` with **1536-dim** pgvector. Do **not** change the
  embedding model casually — a different model can change vector dimensionality and break
  the pgvector schema and stored embeddings.
