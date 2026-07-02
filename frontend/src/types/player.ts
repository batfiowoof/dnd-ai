/* ── Player runtime state (HP / spell slots / inventory) ──────── */
export type ItemKind =
  | "POTION_HEALING"
  | "POTION"
  | "SCROLL"
  | "WEAPON"
  | "ARMOR"
  | "GEAR";

export interface InventoryItem {
  name: string;
  qty: number;
  kind: ItemKind;
  /** Display/context flag for weapons & armor; no mechanical effect. */
  equipped?: boolean;
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
}

export interface PlayerStateEvent {
  type: "PLAYER_STATE";
  sessionId: string;
  state: PlayerRuntimeState;
}
