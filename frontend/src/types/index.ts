export type GameStatus = "WAITING" | "ACTIVE" | "FINISHED";
export type PlayerRole = "PLAYER" | "DM_AI";

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
}

export interface CreateSessionRequest {
  playerName: string;
  characterId: string;
  worldSetting?: string;
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

export interface EnemyActionEvent {
  type: "ENEMY_ACTION";
  sessionId: string;
  attackerKind: CombatantKind;
  attackerName: string;
  targetKind: CombatantKind;
  targetName: string;
  attackRoll: RollSummary;
  vsAc: number;
  hit: boolean;
  damageRoll: RollSummary | null;
  targetCurrentHp: number;
  targetMaxHp: number;
  targetDefeated: boolean;
  combat: CombatStateDto;
}

export interface CombatLifecycleEvent {
  type: "COMBAT_START" | "COMBAT_TURN" | "COMBAT_END";
  sessionId: string;
  victory: boolean | null;
  combat: CombatStateDto;
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
  | EnemyActionEvent
  | CombatLifecycleEvent;

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
