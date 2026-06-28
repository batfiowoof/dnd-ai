export type GameStatus = "WAITING" | "ACTIVE" | "FINISHED";
export type PlayerRole = "PLAYER" | "DM_AI";

/* ── Host session settings ────────────────────────────────────── */
export type TurnMode = "COLLABORATIVE" | "INITIATIVE" | "FREEFORM";
export type Difficulty = "EASY" | "NORMAL" | "DEADLY";
export type DmStyle = "HEROIC" | "GRIMDARK" | "COMEDIC";
export type DmLength = "CONCISE" | "STANDARD" | "RICH";

export interface PlayerDto {
  id: string;
  username: string;
  characterName: string;
  role: PlayerRole;
  turnIndex: number;
  characterId: string | null;
  imageUrl: string | null;
  characterSheet: Record<string, unknown> | null;
}

export interface GameStateDto {
  sessionId: string;
  joinCode: string;
  status: GameStatus;
  players: PlayerDto[];
  currentTurnPlayerId: string | null;
  turnNumber: number;
  createdBy: string | null;
  worldSetting: string | null;
  turnMode: TurnMode;
  maxPlayers: number;
  difficulty: Difficulty;
  dmStyle: DmStyle;
  dmLength: DmLength;
  allowAiCombat: boolean;
  allowAiRolls: boolean;
  collabWindowSeconds: number;
  /** Open ability checks, so a reconnecting client can re-open its roll prompt. */
  pendingChecks: PendingCheckDto[];
}

/** An open ability check surfaced on game state (the transient ROLL_REQUEST is missed on reload). */
export interface PendingCheckDto {
  playerId: string;
  ability: string;
  dc: number;
  skill: string | null;
  reason: string | null;
  suggestedModifier: number;
  /** STANDARD / GROUP / CONTEST — lets a reconnecting client frame the re-opened prompt. */
  checkKind: CheckKind;
  /** Opposed party for a CONTEST (null otherwise). */
  targetLabel: string | null;
  /** The DM's situational grant carried so the re-opened badge survives reload (NORMAL when none). */
  dmMode?: RollMode;
}

export interface CreateSessionRequest {
  playerName: string;
  characterId: string;
  worldSetting?: string;
  turnMode?: TurnMode;
  maxPlayers?: number;
  difficulty?: Difficulty;
  dmStyle?: DmStyle;
  dmLength?: DmLength;
  allowAiCombat?: boolean;
  allowAiRolls?: boolean;
  collabWindowSeconds?: number;
}

export interface CreateSessionResponse {
  sessionId: string;
  joinCode: string;
  playerId: string;
}

export interface JoinSessionRequest {
  playerName: string;
  characterId: string;
}

export interface TurnEventDto {
  id: string;
  playerId: string;
  playerName: string;
  action: string;
  dmResponse: string;
  timestamp: string;
  turnNumber: number;
  /** NARRATIVE = player turn; COMBAT = auto-resolved combat beat (mechanical summary + narration). */
  source: "NARRATIVE" | "COMBAT";
}

export interface DmResponseDto {
  sessionId: string;
  playerId: string;
  playerAction: string;
  dmNarration: string;
  nextTurnPlayerId: string;
  turnNumber: number;
}

export type SessionEventType =
  | "PLAYER_JOINED"
  | "PLAYER_LEFT"
  | "GAME_STARTED"
  | "GAME_ENDED";

export interface SessionEvent {
  type: SessionEventType;
  gameState?: GameStateDto;
  playerId?: string;
  sessionId?: string;
}

export interface TurnChangeEvent {
  type: "TURN_CHANGE";
  nextPlayerId: string;
  turnNumber: string;
}

/* ── Streaming DM messages ────────────────────────────────────── */
export interface DmThinkingEvent {
  type: "DM_THINKING";
  turnNumber: string;
  playerId?: string;
  playerName?: string;
  action?: string;
}

export interface DmChunkEvent {
  type: "DM_CHUNK";
  turnNumber: string;
  playerId: string;
  delta: string;
}

export interface DmNarrationEvent {
  type: "DM_NARRATION";
  turnNumber: string;
  playerId: string;
  dmNarration: string;
}

/* ── Dice rolling ─────────────────────────────────────────────── */
export type RollMode = "NORMAL" | "ADVANTAGE" | "DISADVANTAGE";

/** Kind of ability check the DM called for. */
export type CheckKind = "STANDARD" | "GROUP" | "CONTEST";

export interface DiceRollEvent {
  type: "DICE_ROLL";
  sessionId: string;
  playerId: string;
  playerName: string;
  label: string;
  notation: string;
  count: number;
  sides: number;
  modifier: number;
  mode: RollMode;
  faces: number[];
  discarded: number[] | null;
  total: number;
  crit: boolean;
  fumble: boolean;
}

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
}

/** Summary of a session the current user belongs to, for the "your adventures" list. */
export interface SessionSummary {
  sessionId: string;
  joinCode: string;
  status: GameStatus;
  title: string;
  createdBy: string | null;
  createdAt: string;
  playerCount: number;
  isCreator: boolean;
  myPlayerId: string;
}

export interface PlayerStateEvent {
  type: "PLAYER_STATE";
  sessionId: string;
  state: PlayerRuntimeState;
}

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
  maxHp: number;
  currentHp: number;
  armorClass: number;
  alive: boolean;
}

export interface CombatStateDto {
  encounterId: string;
  status: CombatStatus;
  round: number;
  activeIndex: number;
  active: Combatant | null;
  order: Combatant[];
  enemies: EnemyDto[];
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
  currentHp: number;
  maxHp: number;
  defeated: boolean;
}

export type CombatActionKind =
  | "ATTACK"
  | "SPELL_DAMAGE"
  | "SPELL_HEAL"
  | "SPELL_EFFECT"
  | "ITEM";

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
}

/* ── Combat reference data (from /api/combat/*) ───────────────── */
export type SpellEffectType =
  | "DAMAGE"
  | "HEAL"
  | "BUFF"
  | "DEBUFF"
  | "CONTROL"
  | "UTILITY";
export type SpellTargetType = "ENEMY" | "ALLY" | "SELF" | "AREA" | "ANY";

export interface SpellSummary {
  name: string;
  level: number;
  school: string;
  effectType: SpellEffectType;
  targetType: SpellTargetType;
  maxTargets: number | null;
  concentration: boolean;
  range: string;
  parsed: boolean;
}

export interface MonsterSummary {
  key: string;
  name: string;
  cr: number | null;
  type: string | null;
  size: string | null;
  hp: number | null;
  ac: number | null;
}

export interface CombatLifecycleEvent {
  type: "COMBAT_START" | "COMBAT_TURN" | "COMBAT_END";
  sessionId: string;
  victory: boolean | null;
  combat: CombatStateDto;
}

/* ── Collaborative round + LLM-requested checks ───────────────── */
export interface RoundStatusEvent {
  type: "ROUND_STATUS";
  sessionId: string;
  secondsLeft: number;
  submitted: number;
  total: number;
  /** false once the window has flushed (the DM is now resolving the round). */
  open: boolean;
}

export interface RollRequestEvent {
  type: "ROLL_REQUEST";
  sessionId: string;
  playerId: string;
  ability: string;
  dc: number;
  skill: string | null;
  reason: string | null;
  suggestedModifier: number;
  /** The DM's situational grant — ADVANTAGE/DISADVANTAGE, or NORMAL when none. */
  dmMode: RollMode;
  /** STANDARD / GROUP / CONTEST — frames how the prompt reads. */
  checkKind: CheckKind;
  /** Opposed party for a CONTEST (null otherwise). */
  targetLabel: string | null;
}

/** Neutral system line broadcast to the room (e.g. "X gains Inspiration!"). */
export interface SystemMessageEvent {
  type: "SYSTEM";
  sessionId: string;
  text: string;
}

export type WebSocketMessage =
  | DmResponseDto
  | SessionEvent
  | TurnChangeEvent
  | DmThinkingEvent
  | DmChunkEvent
  | DmNarrationEvent
  | DiceRollEvent
  | PlayerStateEvent
  | CombatActionEvent
  | CombatLifecycleEvent
  | RoundStatusEvent
  | RollRequestEvent
  | SystemMessageEvent;

/* ── Character types ──────────────────────────────────────────── */

export interface CharacterDto {
  id: string;
  name: string;
  race: string;
  characterClass: string;
  level: number;
  background: string | null;
  alignment: string | null;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  hitPoints: number;
  armorClass: number;
  speed: number;
  proficiencyBonus: number;
  equipment: string[];
  proficiencies: string[];
  features: string[];
  cantrips: string[];
  knownSpells: string[];
  startingInventory: InventoryItem[];
  backstory: string | null;
  imageUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CharacterCreateUpdateRequest {
  name: string;
  race: string;
  characterClass: string;
  level: number;
  background: string;
  alignment: string;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  hitPoints: number;
  armorClass: number;
  speed: number;
  equipment: string[];
  proficiencies: string[];
  features: string[];
  cantrips: string[];
  knownSpells: string[];
  startingInventory: InventoryItem[];
  backstory: string;
  imageUrl: string;
}
