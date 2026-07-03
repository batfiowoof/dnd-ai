# Roadmap

Planned features that are scoped but not yet built. Each entry lists the concrete files and
the approach agreed with the maintainer, so a follow-up session can execute without
re-discovering the codebase.

> Shipped in the batch that created this file (for context): session-rejoin self-heal,
> travel-map beacon animation, direct World Builder step navigation, removal of
> session-creation presets, and removal of the on-screen NPC relationships panel.

---

## 1. Equipment paper-doll (8-slot, drag-and-drop, type-restricted) — ✅ Shipped

> Built: `EquipSlot`/`ItemSubtype` enums + `InventoryItem.slot/subtype`, slot-aware
> `PlayerStateService.equipItem` (single occupancy + allowed-slot validation), the paper-doll
> `InventoryManager` (native DnD + tap fallback), and the `CharacterSheetDialog` mirror.
> **AC is now wired from equipped gear** (`CombatMath.armorClassBase`): recognized body armor in
> CHEST sets the base (+ DEX capped by category), a shield in OFF_HAND adds +2, falling back to the
> character's entered AC when unarmored — reflected in both combat defense and the displayed AC.
> The main-hand weapon still drives the damage die.

**Goal:** Replace the flat inventory list with a paper-doll of equip slots. Items are dragged
onto slots; a slot only accepts compatible items (no "sword on the head"). Single occupancy per
slot (equipping into an occupied slot swaps the previous item back to the backpack).

**Slots (decided):** `HEAD, NECK, CHEST, HANDS, MAIN_HAND, OFF_HAND, FEET, RING`.

### Why it needs new data
Today an item is `InventoryItem(name, qty, kind, equipped)` where `kind` is the coarse
`ItemKind { POTION_HEALING, POTION, SCROLL, WEAPON, ARMOR, GEAR }`, and `equipped` is a single
boolean with **no mechanical effect** (documented as display-only — no AC recompute). `WEAPON`
vs `ARMOR` can't distinguish a helmet from boots, so a slot/subtype concept is required to
enforce which items fit which slots.

### Backend (`src/main/java/com/dungeon/master/`)
- **New enum** `model/enums/EquipSlot.java` with the 8 slots above.
- **Extend the item DTO** `model/dto/InventoryItem.java` — add an optional `slot` (the slot the
  item is currently equipped into, null when in the backpack) and/or an item **subtype** so a
  given item's *allowed* slots can be derived (e.g. HELMET→HEAD, BOOTS→FEET, SHIELD→OFF_HAND).
  Stored as JSONB (`PlayerRuntimeState.inventory`, `Character.startingInventory`) — **no SQL
  migration needed**, but old rows will deserialize with `slot=null` (fine).
- **Allowed-slot mapping:** a small helper mapping item subtype (or `ItemKind` as a fallback:
  `WEAPON→{MAIN_HAND, OFF_HAND}`, `ARMOR→{CHEST}`) to the set of legal `EquipSlot`s.
- **`service/game/PlayerStateService.equipItem(...)`** (currently ~lines 316–332, just flips the
  boolean): change signature to accept a target `EquipSlot`; validate the item's allowed slots
  contain it (reject mismatches with a friendly `WsError` via the WS error contract), enforce
  single occupancy (auto-unequip the current occupant of that slot back to the backpack), then
  re-broadcast state. Keep an `unequip` path (slot → null).
- **`model/dto/EquipItemRequest.java`** — add the target `slot`.
- **`websocket/GameWebSocketController.handleEquipItem`** (~line 247) — pass the slot through.

### Frontend (`frontend/src/`)
- **Rebuild** `components/game/InventoryManager.tsx` (currently a flat `<ul>` with an
  Equip/Unequip button gated on `kind === WEAPON || ARMOR`) into two regions: the 8 slot
  rectangles (a paper-doll grid) and a backpack list of unequipped items.
- **Drag-and-drop:** prefer native HTML5 DnD to avoid a new dependency; drop zones validate the
  dragged item's allowed slots and show accept/reject affordance (highlight vs. shake). Provide a
  non-drag fallback (click item → click a legal slot) for touch/accessibility.
- **Types:** extend `types/player.ts` `InventoryItem` + add an `EquipSlot` union +
  slot-label/icon map (mirror `lib/itemKinds.ts`).
- **Send path:** extend `lib/websocket.ts:sendEquipItem` and `hooks/useGameActions.ts:equipItem`
  to carry the slot; the lobby page already wires `onEquip` at
  `app/lobby/[sessionId]/page.tsx`.
- **Read-only mirror:** update `components/game/CharacterSheetDialog.tsx` Equipment section to
  show slotted gear.
- Follow the design directive: reuse `components/ui/` primitives, dark-red theme; invoke
  `/ui-ux-pro-max` before building the paper-doll layout.

**Out of scope (unless later decided):** AC/stat recompute from equipped armor — `equipped`
stays cosmetic.

---

## 2. Structured bonus actions — ✅ Shipped

> Built: `CombatService.playerOffHandAttack` / `playerSecondWind` / `playerCunningAction` (routed
> through `requireBonusActionAvailable` → `markBonusAction`), `/combat/bonus/*` WS verbs, off-hand
> damage without the ability mod (`CombatMath.offHandDamageDice`), and the gated
> off-hand-toggle / Second Wind / Cunning Action controls in `CombatControls`. Out-of-combat
> economy remains deferred.

**Goal:** Give players real bonus-action controls in combat. Today the `Bonus` economy pip
exists but is only consumed by bonus-action *spells* (a spell whose `castingTime == "Bonus
Action"`); there's no button for common bonus actions.

**Scope (decided):** off-hand (two-weapon) attack + common class bonus actions
(Second Wind, Cunning Action: Dash / Disengage / Hide). Out-of-combat action economy is
**deferred**.

### What already exists (build on this)
- `model/dto/Token.java` tracks `bonusActionUsed` (and `actionUsed`, `reactionAvailable`, etc.).
- `service/game/CombatService.java` has `requireBonusActionAvailable(enc, player)` (~line 1285)
  and `markBonusAction(enc, player)` (~line 1302); it resets `bonusActionUsed` per turn and
  auto-ends the turn when action+bonus+movement are all spent.
- Frontend `components/combat/CombatControls.tsx` already renders the `bonusSpent` pip and knows
  bonus-action spells; `components/combat/CombatTracker.tsx` derives `bonusSpent` from
  `myToken.bonusActionUsed`.

### Backend
- Add verbs to `websocket/CombatWebSocketController.java` (alongside `/combat/attack`, `/cast`,
  `/dash`, `/disengage`, `/dodge`, …): e.g. `/combat/offhand-attack` and a generic
  `/combat/bonus-action` (or per-ability endpoints). Each routes through
  `requireBonusActionAvailable` → resolve → `markBonusAction`.
- Off-hand attack: reuse the existing attack-resolution path but spend the **bonus** action and
  apply two-weapon rules (no ability-mod to damage unless a relevant feature applies — keep it
  simple/DM-narrated if full rules are too heavy).
- Class bonus actions (Second Wind, Cunning Action) can start DM-narrated (mechanical effect
  optional) and be tightened later; gate availability by class where cheap to check.

### Frontend
- Add gated buttons to `CombatControls.tsx`, enabled only when `!bonusSpent` and the ability is
  appropriate; wire to new `hooks/useGameActions.ts` / `lib/websocket.ts` senders.
- Keep the human-readable error contract (`WsErrors`/`getErrorMessage` + toast) for
  "already used your bonus action this turn".

---

## Notes for whoever picks this up
- Read `CLAUDE.md` and `CLEAN_CODE.md` first — layered architecture, reuse rules, and the
  friendly-error contract are non-negotiable.
- Both features are frontend + backend; run `cd frontend && npm run lint && npm run build` and
  `./mvnw test` before finishing.
