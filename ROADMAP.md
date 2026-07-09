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

> **Suggested build order (dependency-aware):** ~~1 → 2 → 3 → 4 → 5 → 6 → 7 → 8~~ — **all shipped.**
> Feature 1 (structured proficiency) was foundational — features 5 (feats) and 7 (spell prep) now
> plug into its proficiency model; features 5–8 lean on the combat/roll hooks the earlier ones
> establish. Features 5 (Lucky) and 6 (Heroic Inspiration) shipped together — they share one
> interactive reroll window.
>
> **This batch is complete.** The project now moves to its last phase — QoL, bug fixes, and
> stabilization.

---

## ✅ Shipped (context)

Prior batches delivered the core loop. Most recently:

- **Legendary & Lair actions (feature 8)** — boss drama for high-CR monsters. `MonsterActionKind`
  enum {ATTACK,SAVE,NARRATIVE} + `MonsterAction` DTO; a hand-authored
  `resources/dnd5e/monster-actions.json` overlay (analogue of `magic-item-effects.json`) merged by
  key in `MonsterCatalog` — the generated `monsters.json` has no boss data, and the SRD prose is
  truncated for legendary actions and silent on lair actions. **26 curated bosses**: all 20 Adult +
  Ancient dragons (shared shape — Detect / Tail Attack / Wing Attack; DC = 8 + PB + STR mod, which
  reproduces the published stat blocks) plus Lich, Vampire, Kraken, Tarrasque, Aboleth, Mummy Lord.
  `Enemy` gains `legendaryActions`/`lairActions` (JSONB) + `legendaryActionMax`/
  `legendaryActionsRemaining`/`legendaryResistances`; `CombatEncounter.lairActionRound` (`V35`).
  **Legendary actions** fire from `CombatService.advanceTurn` at the end of each hero's turn
  (priciest affordable option, budget refilled at the boss's own turn); **lair actions** fire once
  per round from the `resolveUntilPlayerOrEnd` loop at `MonsterActionRules.lairSlot` — the
  initiative-count-20 slot. SAVE actions are the first enemy→player saving throws in the engine
  (`CheckModifierService.computeSaveModifier`, mirroring `CombatSpellResolver`'s player→enemy path).
  **Legendary Resistance** hooks a new shared `rollEnemySave` helper that dedupes the resolver's two
  save sites. Host toggles "In its lair" (`Switch`) in `StartEncounterControl`; gold-railed boss rows
  in `CombatRollFeed`.
  *Simplifications: legendary actions fire only at the end of player turns, not other monsters';
  they resolve via `applySwing`, never `runMultiattack`, so no Shield/Absorb reaction window opens
  against them (`ReactionPause` would escape `advanceTurn`'s try/catch and roll back the
  transaction); Legendary Resistance is per-encounter, not per-day, and is spent only against
  condition-imposing effects; `lairSlot` clamps to the last combatant when everyone rolls ≥ 20
  initiative (otherwise the lair would never act); homebrew `CustomMonster`s get no boss mechanics;
  lair actions rotate by round rather than being chosen tactically.*
- **Ritual casting & spell preparation (feature 7)** — `preparedSpells`/`preparedMax` on
  `PlayerRuntimeState` (`V34`, backfills prepared = known so mid-session casters keep casting).
  `SpellSlotTable.preparedCount`/`isPreparedCaster` (cleric/druid/wizard/paladin; `castingMod + level`,
  paladin off half level) sizes the cap; `PlayerStateSeeder` seeds a capped prepared subset (known
  casters keep all known prepared). Both cast paths now gate leveled casts on `preparedSpells`
  (`CombatService.playerCastSpell` + `GameWebSocketController.handleCast`). `ritual` parsed into
  `SpellEffect`/`SpellCatalog`/`SpellSummary`; `handleCast` casts a known Ritual-tagged spell with no
  slot. `/prepare` WS verb + `PlayerStateService.setPreparedSpells` (cap-validated). Frontend:
  `PrepareSpellsDialog` (reuses `SpellPicker`) behind a "Prepare" button, a "Rituals (no slot)"
  section + prepared-only slot casts in `ActionBar`, prepared/unprepared badges on the sheet.
  *Simplifications: prepared = subset of `knownSpells` (not the full class list); prepare is available
  any time out of combat (not strictly post-long-rest); no material-component tracking.*
- **Feats with mechanical effects (feature 5)** — `FeatKey` enum + `FeatEffects` bean (analogue of
  `MagicItemEffects`) resolving a character's origin feat from its background via `Dnd5eReferenceService`.
  **Alert** adds proficiency bonus to initiative (`CombatService.startCombat`); **Tough** adds a derived
  +2/level to max HP at seed + level-up (`PlayerStateSeeder`/`applyLevelUpToRuntime`, base HP untouched);
  **Savage Attacker** rerolls weapon damage keep-higher once/turn (`Token.savageAttackerUsed` gate);
  **Lucky** grants Luck Points = proficiency bonus (`V33` migration, regained on long rest). Added the
  missing SRD data (Tough/Lucky feats + Farmer/Merchant/Charlatan backgrounds) so all are selectable.
  *Simplifications: origin feat from background only; Skilled stays descriptive (no skill-choice UI);
  Savage Attacker fires on the main damage roll.*
- **Heroic Inspiration 2024 (feature 6)** — evolved Inspiration from pre-roll advantage to a post-roll
  **interactive reroll**. Shared `RerollWindow` (blocks the DM roll tool on a bounded wait, mirrors
  `ReactionWindow`) + `RerollPromptEvent`/`RerollChoiceRequest`/`RerollResource`; `DmRollTools`
  offers a reroll on a *failed* single-target check/save/contest when the player holds Inspiration or
  Luck (Inspiration uses the new roll, Lucky keeps the better). Granted to Humans on long rest. Removed
  the old `spendInspiration` pre-arm plumbing end-to-end. Frontend `RerollPromptModal` + store/socket
  wiring, luck/inspiration badges. *Simplification: no reroll on group checks; the DM roll thread
  blocks up to ~12s (single-backend).*
- **Reactions & Opportunity Attacks (feature 4)** — real reaction economy: opportunity attacks (both
  directions), Ready action, *Shield*/*Absorb Elements* reaction spells, timed `ReactionWindow` +
  `ReactionPromptModal`.
- **Saving Throws, Expertise & Passive Scores (feature 1)** — `ProficiencyLevel` enum
  {NONE,HALF,PROFICIENT,EXPERTISE}; structured `skillProficiencies` map + `savingThrowProficiencies`
  set on `Character`/`PlayerRuntimeState` (JSONB, no migration); `CheckModifierService` expertise/
  half-prof + `computeSaveModifier` + `passiveScore`; `Skills` (skill→ability); `DmRollTools.rollSave`.
  Frontend: Saving-Throw + Passive-Score sheet sections, creation-wizard Expertise picker
  (`LEVEL1_EXPERTISE_GRANTS`), `dnd5e.ts` mirrors. Half-prof (Bard JoAT) wired but no level-1 UI source.
- **Magic Items & Attunement (feature 3)** — `MagicItemRarity` enum + `MagicItemEffect` DTO;
  `MagicItemCatalog` parses rarity/type/slot/attunement from the SRD prose headers of all 251
  items, merges a curated `resources/dnd5e/magic-item-effects.json` overlay (~8 iconic items), and
  synthesizes `+N` weapon/armor and typed-Resistance effects from item names. `MagicItemEffects`
  (bean, analogue of `ConditionRules`) gates each item live-when-attuned-or-equipped and sums AC/
  attack/damage/save bonuses, resistances, advantage, and set-ability overrides. `PlayerRuntimeState.
  attunedItems` (JSONB, `V32` migration) + `attuneItem`/`endAttunement` (cap 3) in `PlayerStateService`;
  `toDto` folds effective abilities + item AC. Wired into `CombatService` armorClass/attackBonus/
  damageDice (via `CombatMath.addFlat`) + enemy-damage resistance halving, and `CheckModifierService`
  saves. `/combat/magic-items` REST + `/attunement/attune|end` WS verbs. Frontend: `lib/magicItems.ts`
  (rarity color+label, name synthesis), attunement section + rarity chips in `InventoryManager`,
  attuned list on `CharacterSheetDialog`. *Simplifications: charge/activated items stay DM-narrated;
  combat AC uses character DEX (sheet AC uses effective DEX); advantage flags surfaced, not fully wired.*
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

## No features remain in this batch

Everything scoped above has shipped. New work belongs to the **QoL / bug-fix / stabilization**
phase — add entries here only if a new mechanic is genuinely required.

Deliberately still deferred: **Encumbrance**, **Bastions**, out-of-combat action economy,
charge/activated magic items, and legendary/lair data for the remaining ~297 stat blocks (the
overlay in `resources/dnd5e/monster-actions.json` is the extension point — add a key, no code).

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
