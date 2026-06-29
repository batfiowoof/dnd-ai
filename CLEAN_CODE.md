# Clean Code & Structure Guide

How code is organized in this repo and the conventions to follow when adding to it. Read this
before building a feature; it's the contract that keeps the codebase layered and reusable instead
of letting logic, types, and error handling sprawl back into components and god-files.

The golden rule: **before writing new code, look for an existing helper, hook, primitive, type, or
enum that already does the job.** Most "new" UI/logic in this app is a recombination of things that
already live in `lib/`, `components/ui/`, `hooks/`, `model/dto`, or `model/mapper`.

---

## Layered architecture

### Frontend (`frontend/src`)

```
app/         Next.js pages — composition & local UI state ONLY (no fetch logic, no WS plumbing)
components/  Presentational components
  ui/        Design-system primitives (Button, Panel, Modal, Field, Alert, Spinner, Tooltip,
             ConfirmDialog, HpBar, Toast, cn) — the ONLY place to style buttons/panels/inputs
hooks/       React Query wrappers + stateful behaviour (useGameSocket, useGameActions,
             useRequireToken, use*Queries)
lib/         Pure logic & clients — api.ts (REST), websocket.ts (STOMP transport), errors.ts,
             sessionStorage.ts, dnd5e.ts, combat.ts, conditions.ts, health.ts, queryKeys.ts, prefs.ts
store/       Zustand live game state (sessionStore)
types/       All shared TS types/DTOs (single barrel, imported via @/types)
context/     React context providers (AuthContext)
```

**Dependency direction:** `app → components → hooks → (lib / store / types)`. Lower layers never
import upward. A page should read like a wiring diagram: call hooks, pass props to components.

**What does NOT belong in a page/component:**
- `fetch`/REST calls → use `lib/api.ts` (wrapped) via a `hooks/use*Queries` hook.
- STOMP connect/subscribe/publish → use `useGameSocket` (lifecycle + dispatch) and `useGameActions`
  (guarded sends). Never hand-roll `clientRef`/`subscribe` in a component.
- `localStorage` access → use a typed wrapper (`lib/sessionStorage.ts`, `lib/prefs.ts`). Never
  build `dnd-…-${id}` keys inline.
- D&D math, formatting, HP/health logic → `lib/dnd5e.ts`, `lib/combat.ts`, `lib/health.ts`.
- Re-implemented bars/badges/chips → reuse `components/ui/HpBar`, the condition/health metadata in
  `lib/conditions.ts` / `lib/health.ts`, `DeathSaveTrack`'s exported helpers.

### Backend (`src/main/java/com/dungeon/master`)

```
controller/   REST entry points — validate input, delegate to a service, return a DTO. Thin.
websocket/    STOMP @MessageMapping handlers + WsErrors + auth interceptor
service/      ALL business logic (service/ai, service/game). Owns repositories & @Transactional.
repository/   Spring Data interfaces
model/
  entity/     JPA entities — NEVER returned across the web boundary
  dto/        Request/response records (+ ErrorResponse, WsError)
  enums/      Domain enums (the source of truth for closed sets)
  mapper/     entity → DTO mappers (e.g. PlayerMapper) — one place per mapping
config/       Spring config · exception/  GlobalExceptionHandler + custom exceptions
kafka/        producer/ + consumer/ + event/
```

**Dependency direction:** `controller / websocket → service → repository`. Hard rules:
- **Controllers never inject a repository** and never build DTOs inline. If a controller needs
  assembled data, add a service method that returns the DTO (see
  `TurnService.getSessionHistoryDtos`).
- **Entities never cross the web boundary** — map to a DTO first.
- **Mapping lives in `model/mapper`**, not copy-pasted into each service. `PlayerMapper.toDto` is the
  single source for `Player → PlayerDto`; add a mapper rather than re-writing positional
  constructors. (A MapStruct-based mapper layer is a reasonable future step; today these are plain
  `@Component` mappers, which stay Lombok-friendly.)
- **Shared authorization helpers, not inline checks.** Host-only actions call
  `GameSessionService.requireHost(sessionId, username, action)` — don't re-write
  `username.equals(session.getCreatedBy())`.

---

## Types & enums

- **One home for shared types.** Frontend DTOs live in `types/index.ts` (`@/types`). UI-model types
  may live next to their logic module (e.g. `lib/dnd5e.ts`), but anything crossing module
  boundaries goes in `types/`.
- **Enums are the source of truth for closed sets.** Backend: `model/enums`. Don't sprinkle magic
  strings where an enum exists.
- **const-array → union for runtime option lists.** When a union type is *also* needed as a runtime
  list (dropdowns, option cards), derive both from one `const X = [...] as const` (see
  `ABILITY_NAMES`/`ALL_SKILLS` in `lib/dnd5e.ts`) instead of hand-maintaining the list and the union
  separately.
- **Keep frontend types in sync with backend DTOs.** When you change a DTO, update `types/index.ts`
  in the same change.

---

## Error handling (human-readable, end to end)

Users must never see a stack trace, a raw exception message, or a bare status code. The pipeline:

**Backend**
- REST errors flow through `exception/GlobalExceptionHandler` (`@RestControllerAdvice`) → the typed
  `ErrorResponse { timestamp, status, message, path }`. Throw the right exception and let the handler
  map it; don't catch-and-format in the controller. Status mapping: not-found → 404
  (`SessionNotFoundException`, `PlayerNotFoundException`, `CharacterNotFoundException`),
  conflict → 409, forbidden → 403 (`NotYourTurnException`, `AccessDeniedException`), bad
  state/argument → 400. The catch-all `Exception` → 500 with a **generic** message (real cause is
  logged, never sent).
- **WebSocket** handlers can't use the REST advice. Every error is sanitized through
  `WsErrors.from(e)` → the typed `WsError { code, message }` on `/user/queue/errors`. Domain
  exceptions pass their message through; anything unexpected collapses to a generic message. A
  `@MessageExceptionHandler` backstop guarantees no handler leaks. **Never** hand-build
  `Map.of("error", e.getMessage())`.

**Frontend**
- `lib/api.ts` throws a typed `ApiError(status, serverMessage)`; `lib/errors.ts:getErrorMessage(e,
  fallback)` is the single mapper from any caught value to friendly copy (4xx → server message,
  5xx/network/non-JSON → calm fallback). Always run caught errors through it.
- Surface errors with the **toast system** (`useToast()` from `@/components/ui`):
  `toast.error(getErrorMessage(e, "Couldn't do X"))`. Don't add per-page `useState("")` +
  `setTimeout` error banners.
- Render-time crashes are caught by `components/ErrorBoundary` (wraps the app in `Providers`) and the
  route-level `app/error.tsx` — both show a themed fallback, not a white screen.

---

## UI conventions

- **Any UI/UX work starts with the `ui-ux-pro-max` skill** (project directive), then reuses
  `components/ui/` primitives and the design tokens in `app/globals.css` (`@theme`). Dark-red
  fantasy-tabletop theme: accent `#dc2626` on warm near-black, gold as a sparing secondary.
- Tailwind **v4**, CSS-first — there is no `tailwind.config.js`.
- Don't hand-roll button/panel/input/toast styles; extend the primitive or add a new one to
  `components/ui/` (and export it from `components/ui/index.ts`).
- Accessibility is non-negotiable: `role`/`aria-live` on alerts, focus-visible rings, respect
  `prefers-reduced-motion` (handled globally), color is never the only signal (pair with icon/text).

---

## Naming & general

- Files/dirs: kebab or domain-grouped as already present; match the neighbours.
- A function/hook/component does one thing; if a page handler is a pure pass-through to a transport
  call, it belongs in a hook (`useGameActions`), not the page.
- Delete dead code as you refactor (unused store fields, imports, handlers) — don't leave it
  "just in case".
- Match the surrounding code's comment density and idiom. Comment the *why*, not the *what*.
- Flyway: add a new `V{n}__*.sql`; never edit an applied migration.
