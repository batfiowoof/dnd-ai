import type { ProficiencyLevel } from "@/lib/dnd5e";

/* ── Player runtime state (HP / spell slots / inventory) ──────── */
export type ItemKind =
  | "POTION_HEALING"
  | "POTION"
  | "SCROLL"
  | "WEAPON"
  | "ARMOR"
  | "GEAR";

/** The eight paper-doll equipment slots. */
export type EquipSlot =
  | "HEAD"
  | "NECK"
  | "CHEST"
  | "HANDS"
  | "MAIN_HAND"
  | "OFF_HAND"
  | "FEET"
  | "RING";

/** Fine-grained item type; refines which slots an item may occupy (see lib/itemKinds). */
export type ItemSubtype =
  | "HELMET"
  | "AMULET"
  | "BODY_ARMOR"
  | "GLOVES"
  | "WEAPON"
  | "SHIELD"
  | "BOOTS"
  | "RING"
  | "OTHER";

export interface InventoryItem {
  name: string;
  qty: number;
  kind: ItemKind;
  /** Display/context flag for weapons & armor; no mechanical effect. Kept in sync with `slot`. */
  equipped?: boolean;
  /** The paper-doll slot this item occupies, or null/undefined when in the backpack. */
  slot?: EquipSlot | null;
  /** Optional finer type used to derive allowed slots; falls back to `kind` when absent. */
  subtype?: ItemSubtype | null;
}

export interface SpellSlot {
  level: number;
  max: number;
  used: number;
}

export interface PlayerRuntimeState {
  playerId: string;
  currentHp: number;
  maxHp: number;
  tempHp: number;
  armorClass: number;
  /** Ability scores keyed by STR/DEX/CON/INT/WIS/CHA. */
  abilities: Record<string, number>;
  /** Skill training keyed by canonical skill name → level (drives expertise/half-prof math). */
  skillProficiencies?: Record<string, ProficiencyLevel>;
  /** Ability abbreviations the character is proficient in for saving throws ("STR","CON",…). */
  savingThrowProficiencies?: string[];
  spellSlots: SpellSlot[];
  inventory: InventoryItem[];
  conditions: string[];
  cantrips: string[];
  knownSpells: string[];
  /** Whether the player currently holds Inspiration (spendable on a roll for advantage). */
  inspiration: boolean;
  /* ── Death saving throws (mostly Phase C; types land now) ── */
  deathSaveSuccesses: number;
  deathSaveFailures: number;
  /** Stabilized at 0 HP (no longer rolling death saves, still unconscious). */
  stable: boolean;
  /** Three failed death saves — the character has died. */
  dead: boolean;
  /** Name of the concentration spell this player is currently sustaining, if any. */
  concentratingSpell?: string | null;
  /** 5e exhaustion level (0–6); accrues without a long rest, eased one level per long rest. */
  exhaustionLevel: number;
  /** Hit Dice available to spend on a short rest. */
  hitDiceRemaining: number;
  /** Total Hit Dice pool (character level). */
  hitDiceTotal: number;
  /** Coin purse in copper (1 gp = 100 cp, 1 sp = 10 cp). Spent/earned at shops; format via lib/money. */
  copper: number;
}

export interface PlayerStateEvent {
  type: "PLAYER_STATE";
  sessionId: string;
  state: PlayerRuntimeState;
}
