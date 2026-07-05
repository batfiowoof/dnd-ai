# Roadmap

Planned features that are scoped but not yet built. Each entry lists the concrete files and
the approach agreed with the maintainer, so a follow-up session can execute without
re-discovering the codebase.

This is the **final feature batch** before the project pivots to its last phase — **QoL,
bug fixes, and stabilization**. Everything below is a *new* gameplay mechanic that fills an
authentic SRD 5.2.1 / 2024-PHB gap in an already-deep engine (combat with
conditions/concentration/death-saves/grid, leveling/ASI, spell slots + per-spell combat
resolution, exhaustion, rests, quests, shops/currency, NPCs, travel, world-building,
RAG-grounded DM). Each feature **reuses existing infrastructure** rather than adding a new
subsystem. **Encumbrance** and **Bastions** were considered and deferred.

> **Suggested build order (dependency-aware):** ~~1 → 2~~ (shipped) → 3 → 4 → 5 → 6 → 7 → 8.
> Feature 1 (structured proficiency) was foundational — features 5 (feats) and 7 (spell prep) now
> plug into its proficiency model; features 5–8 lean on the combat/roll hooks the earlier ones
> establish.

---

## ✅ Shipped (context)

Prior batches delivered the core loop. Most recently:

- **Saving Throws, Expertise & Passive Scores (feature 1)** — `ProficiencyLevel` enum
  {NONE,HALF,PROFICIENT,EXPERTISE}; structured `skillProficiencies` map + `savingThrowProficiencies`
  set on `Character`/`PlayerRuntimeState` (JSONB, no migration); `CheckModifierService` expertise/
  half-prof + `computeSaveModifier` + `passiveScore`; `Skills` (skill→ability); `DmRollTools.rollSave`.
  Frontend: Saving-Throw + Passive-Score sheet sections, creation-wizard Expertise picker
  (`LEVEL1_EXPERTISE_GRANTS`), `dnd5e.ts` mirrors. Half-prof (Bard JoAT) wired but no level-1 UI source.
- **Weapon Mastery (2024 PHB) (feature 2)** — `WeaponMastery` enum + `CombatMath.WEAPON_MASTERY`
  name-lookup (no `InventoryItem` field); `WeaponMasteryRules` fires from `CombatService`
  applyPendingDamage/miss branch, martial-gated. Topple (CON save→prone), Vex/Sap (synthetic
  conditions), Slow (SLOWED), Push (grid), Graze (on-miss), Cleave (auto nearest foe), Nick
  (off-hand die). Informational mastery chip in `CombatControls`. *Simplifications: no known-mastery
  count, Slow=halve-speed, Cleave auto-target, Nick=extra damage not extra attack.*
- **Equipment paper-doll** — `EquipSlot`/`ItemSubtype` enums + `InventoryItem.slot/subtype`,
  slot-aware `PlayerStateService.equipItem` (single occupancy + allowed-slot validation), the
  paper-doll `InventoryManager` (native DnD + tap fallback), the `CharacterSheetDialog` mirror,
  and **AC wired from equipped gear** (`CombatMath.armorClassBase`: body armor sets base + DEX
  cap, shield +2, main-hand weapon drives the damage die).
- **Structured bonus actions** — `CombatService.playerOffHandAttack` / `playerSecondWind` /
  `playerCunningAction` (routed through `requireBonusActionAvailable` → `markBonusAction`),
  `/combat/bonus/*` WS verbs, off-hand damage without the ability mod, and gated `CombatControls`.
  (Out-of-combat action economy remains deferred.)
- Earlier: session-rejoin self-heal, travel-map beacon animation, direct World Builder step
  navigation, removal of session-creation presets, removal of the on-screen NPC relationships panel.

---

## Reference data already bundled (reuse before adding content)

- `resources/dnd5e/srd-5.2.1-structured.json` — machine-readable build data (classes with
  `savingThrows`, species, backgrounds, feats, spells with `ritual`/`concentration`/`combat`,
  **equipment with a `mastery` field**). Served by `Dnd5eReferenceService` / `Dnd5eController`
  (`/api/srd/*`) and `SpellCatalog`.
- `resources/dnd5e/monsters.json` — 323 structured stat blocks (`MonsterCatalog`). **No**
  legendary/lair data yet (see §8).
- `resources/srd/srd-5.2.1.json` — flat prose lore corpus via `SrdContent` (spells, monsters
  [stat-free], rules, **251 magic items**, equipment, feats, conditions, classes, species,
  backgrounds). Magic items & conditions exist here **only as prose** — no structured type.
- **Engine owns all math/randomness; the LLM never rolls** (`DiceService`, `RollMode`). Rules
  math is stateless in `service/game/*Rules.java` + `service/game/combat/CombatMath.java`, mirrored
  client-side in `frontend/src/lib/{dnd5e,combat}.ts`.

---

## 3. Magic Items & Attunement

**Goal:** Make magic items mechanical, with the attunement limit (max 3, requires a short rest).
Completes the loot/reward loop.

### What exists / why it's a gap
251 magic items exist **as prose only**; inventory, shops, and the paper-doll all work, but magic
items have zero mechanical effect. Equipped gear already recomputes AC via
`CombatMath.armorClassBase`.

### Data
Parse the `MAGIC_ITEM` prose entries from `SrdContent` into a structured catalog (rarity, slot,
attunement-required, and a small typed `effect`) — mirror the spell `combat` block that
`SpellCatalog` already loads.

### Backend
- New `service/game/MagicItemCatalog.java` (mirror `MonsterCatalog` / `SpellCatalog` load pattern) +
  a structured `model/dto/MagicItemEffect.java` (bonus AC/attack/damage, resistance, ability-score
  set, advantage-on-X).
- Attunement state on `PlayerRuntimeState` (attuned item ids, cap 3) + `attuneItem` / `endAttunement`
  in `service/game/PlayerStateService.java`, gated on a short rest (`shortRest` already advances the
  clock).
- Wire item bonuses into `CombatMath.attackBonus` / `damageDice` / `armorClass` behind the
  equipped-and-attuned check. Let `ShopService` and loot reference magic items by id.

### Frontend
- Attunement affordance on the paper-doll (`components/game/InventoryManager.tsx`) +
  `CharacterSheetDialog`. Reuse `components/ui/` primitives; rarity color coding must pair color with
  a text label (a11y).

**Scope:** support +N weapon/armor, resistance, stat-set, and advantage effects mechanically; leave
charge-based/activated items DM-narrated.

---

## 4. Reactions & Opportunity Attacks

**Goal:** A real reaction economy in combat.

### What exists / why it's a gap
Reactions are only implicit; `model/dto/Token.java` already tracks `reactionAvailable` but nothing
spends it on a real opportunity attack or reaction spell, and there are no readied actions.

### Backend
- Detect provoking movement in `CombatService.playerMove` (and enemy movement): a creature leaving
  another's melee reach — use `CombatMath.isMelee` / `enemyReachFeet`. Offer an opportunity attack
  that spends the reaction.
- Add reaction spells resolved through `CombatSpellResolver`: *Shield* (+5 AC vs a triggering hit),
  *Absorb Elements*.
- Add a **Ready** action that stores a trigger. Reaction resets at turn start (already per-round).
  Route "reaction already used" through `WsErrors`.

### Frontend
- An async reaction **prompt** (modal/toast with a short decision window) in `components/combat/` when
  a reaction is available; wire senders via `hooks/useGameActions.ts` + `lib/websocket.ts`, respecting
  the existing turn-broadcast model.

**Scope:** opportunity attack + *Shield* + basic Ready first; no full readied-spell targeting engine.

---

## 5. Feats with mechanical effects

**Goal:** Wire a handful of the 17 SRD feats to real effects (today feats are name-only display text).

### Backend
- A small `service/game/FeatEffects.java` hook applied where the relevant math runs:
  - **Alert** → initiative bonus in `TurnService` initiative roll
  - **Tough** → +2 HP/level in `LevelingRules` / HP calc
  - **Lucky** → luck points → reroll via `DiceService` (shares plumbing with §6)
  - **Savage Attacker** → reroll weapon damage once in `CombatMath`
  - **Skilled** / expertise-granting feats → feed the proficiency model in §1

### Frontend
- Show active feat effects on `CharacterSheetDialog` (the origin feat is already chosen at creation).

**Scope:** the ~4–5 feats with clean engine hooks; others stay descriptive.

---

## 6. Heroic Inspiration (2024)

**Goal:** Evolve existing Inspiration to the 2024 rule — **reroll any die after rolling** — plus 2024
grant sources.

### What exists / why it's a gap
Today Inspiration grants advantage (a *pre-roll* decision). `PlayerRuntimeState.inspiration` (bool)
and the DM award tag already exist.

### Backend
- Change spend semantics in `PlayerStateService` / `DiceService` to reroll the last die (keep the
  second result) rather than roll-twice-advantage. Grant to Humans on `longRest` and where a class
  feature applies.

### Frontend
- Update the Inspiration control copy/affordance to "reroll" in the roll UI (`components/dice/`,
  `QuickRollBar`).

**Scope:** rename + behavior change only; not a new resource.

---

## 7. Ritual casting & spell preparation

**Goal:** Prepared-caster rules + ritual casting without a slot.

### Backend
- Prepared-spell tracking (a prepared subset of `knownSpells`) for cleric / druid / wizard / paladin,
  sized by `castingMod` + level. Extend `SpellcastingRules` / `SpellSlotTable`.
- Ritual-tag casting in the out-of-combat path (no slot consumed) using the `ritual` flag already
  present on spells in `srd-5.2.1-structured.json`.

### Frontend
- A "prepare spells" step (extend `components/character/SpellPicker.tsx`) available after a long rest;
  a ritual affordance in the cast UI.

**Scope:** preparation counts + ritual tag; no material-component tracking.

---

## 8. Legendary & Lair actions

**Goal:** Boss drama for high-CR monsters.

### What exists / why it's a gap
`monsters.json` stat blocks have **no** legendary/lair data — this feature must author it.

### Backend
- Extend the `monsters.json` schema + `MonsterCatalog` / `Bestiary` with optional `legendaryActions`
  (count + options) and `lairActions`.
- Drive them in `CombatService`: legendary actions at the end of each hero's turn, lair actions on
  initiative count 20, reusing enemy attack resolution and `EnemyTacticsService` for the choice.

### Frontend
- Surface legendary/lair beats in the combat log / roll feed (`components/combat/CombatRollFeed.tsx`).

**Scope:** hand-author data for a curated set of iconic high-CR monsters, not all 323 stat blocks.

---

## Notes for whoever picks this up
- Read `CLAUDE.md` and `CLEAN_CODE.md` first — layered architecture, reuse rules, and the
  friendly-error contract (`WsErrors` / `getErrorMessage` + toast) are non-negotiable.
- Any UI work starts with the `/ui-ux-pro-max` skill, then reuses `components/ui/` primitives and the
  dark-red theme tokens in `app/globals.css`.
- Every feature is frontend + backend; run `cd frontend && npm run lint && npm run build` and
  `./mvnw test` before finishing, and verify end-to-end in the running app (e.g. land a Topple attack
  and confirm prone applies; attune an item and confirm the AC/attack bonus changes; trigger an
  opportunity attack).
