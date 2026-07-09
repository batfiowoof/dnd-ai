/* ── Combat ───────────────────────────────────────────────────── */
export type CombatStatus = "ACTIVE" | "ENDED";
export type CombatantKind = "PLAYER" | "ENEMY";

export interface Combatant {
  kind: CombatantKind;
  refId: string;
  name: string;
  initiative: number;
  dexMod: number;
}

export interface EnemyDto {
  id: string;
  name: string;
  armorClass: number;
  alive: boolean;
  /** Active condition keys (e.g. "restrained", "blinded") for badges on the map/cards. */
  conditions: string[];
  /** Coarse health band — exact HP is hidden from players (5E convention). */
  healthBand: string;
  /** Melee reach in feet (for opportunity-attack threat zones on the grid). */
  reachFeet: number;
}

/* ── Tactical grid (Phase A/B) ───────────────────────────────── */
export type TerrainType = "WALL" | "DIFFICULT" | "HAZARD";

export interface TerrainCell {
  x: number;
  y: number;
  type: TerrainType;
}

export interface MapFeature {
  x: number;
  y: number;
  label: string;
}

/** Per-combatant position + action-economy flags on the battle grid. */
export interface Token {
  x: number;
  y: number;
  /** Feet of movement already spent this turn. */
  movementUsedFeet: number;
  reactionAvailable: boolean;
  dashed: boolean;
  disengaged: boolean;
  dodging: boolean;
  /** Whether this combatant has spent its action this turn (Dash/Disengage/Dodge/Attack/Cast/Item are mutually exclusive). */
  actionUsed: boolean;
  /** Whether this combatant has spent its bonus action this turn (e.g. a Bonus-Action spell). */
  bonusActionUsed: boolean;
  /** Whether this player is holding their reaction for a spell (suppresses auto opportunity attacks). */
  holdingReaction?: boolean;
  /** Enemy id this player readied an attack against, or null. */
  readiedTargetEnemyId?: string | null;
}

export interface GridState {
  width: number;
  height: number;
  backgroundImageUrl: string | null;
  terrain: TerrainCell[];
  features: MapFeature[];
  /** Keyed by combatant refId (player id / enemy id). */
  tokens: Record<string, Token>;
}

export interface CombatStateDto {
  encounterId: string;
  status: CombatStatus;
  round: number;
  activeIndex: number;
  active: Combatant | null;
  order: Combatant[];
  enemies: EnemyDto[];
  /** Tactical grid; null for encounters started before grids existed. */
  grid: GridState | null;
}

export interface RollSummary {
  notation: string;
  faces: number[];
  total: number;
  crit: boolean;
  fumble: boolean;
}

/** One actor's resolution against a single target (attack / save / heal / effect). */
export interface CombatTarget {
  targetKind: CombatantKind;
  targetName: string;
  attackRoll: RollSummary | null;
  vsAc: number | null;
  hit: boolean | null;
  saveRoll: RollSummary | null;
  saveDc: number | null;
  saved: boolean | null;
  damageRoll: RollSummary | null;
  heal: number | null;
  condition: string | null;
  /** Exact HP for player/ally targets; 0 for enemies (hidden — see {@link healthBand}). */
  currentHp: number;
  maxHp: number;
  defeated: boolean;
  /** Health band for ENEMY targets (exact HP hidden); null for players/allies. */
  healthBand: string | null;
}

export type CombatActionKind =
  | "ATTACK"
  | "SPELL_DAMAGE"
  | "SPELL_HEAL"
  | "SPELL_EFFECT"
  | "ITEM"
  /** A boss acting between the heroes' turns. */
  | "LEGENDARY_ACTION"
  /** The boss's lair acting on initiative count 20. */
  | "LAIR_ACTION";

/** Legendary and lair beats are the boss's, and read as one visual family in the feed. */
export function isBossAction(kind: CombatActionKind): boolean {
  return kind === "LEGENDARY_ACTION" || kind === "LAIR_ACTION";
}

/**
 * One actor's complete combat action (an attack, multiattack, AoE spell, or heal),
 * carried as a single event. The client buffers these in arrival order and plays them
 * back one at a time so the turn order reads correctly.
 */
export interface CombatActionEvent {
  type: "COMBAT_ACTION";
  sessionId: string;
  seq: number;
  actorKind: CombatantKind;
  actorName: string;
  actionKind: CombatActionKind;
  label: string;
  targets: CombatTarget[];
  combat: CombatStateDto;
  /** A weapon hit awaiting its second phase: the player must roll damage to resolve it. */
  awaitingDamage?: boolean;
}

export interface CombatLifecycleEvent {
  type: "COMBAT_START" | "COMBAT_TURN" | "COMBAT_END";
  sessionId: string;
  victory: boolean | null;
  combat: CombatStateDto;
}
