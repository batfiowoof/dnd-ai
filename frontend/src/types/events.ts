import type { GameStateDto, NpcState } from "./session";
import type { PlayerStateEvent } from "./player";
import type { CombatActionEvent, CombatLifecycleEvent } from "./combat";

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

/** Neutral system line broadcast to the room (e.g. "X gains Inspiration!"). */
export interface SystemMessageEvent {
  type: "SYSTEM";
  sessionId: string;
  text: string;
}

/**
 * Pushed privately (via /user/queue/reaction) to a single player when an enemy attack has hit them
 * and they may spend their reaction on a spell. The paused enemy turn resumes once they answer with
 * a `/combat/reaction` choice or the `secondsLeft` window elapses (auto-decline).
 */
export interface ReactionPromptEvent {
  type: "REACTION_PROMPT";
  sessionId: string;
  promptId: string;
  /** Attacking enemy's display name. */
  attacker: string;
  /** Triggering damage type (e.g. "Fire"), or null. */
  damageType: string | null;
  /** Reaction spells the player may cast now. */
  spellOptions: Array<"SHIELD" | "ABSORB">;
  /** Decision window in seconds before an automatic decline. */
  secondsLeft: number;
}

/**
 * Pushed privately (via /user/queue/reroll) to a single player right after one of their d20 rolls
 * fails, when they hold a resource that can reroll it (Heroic Inspiration and/or Lucky points). The
 * DM's roll is paused until they answer with a `/roll/reroll` choice or the `secondsLeft` window
 * elapses (auto-keep).
 */
export interface RerollPromptEvent {
  type: "REROLL_PROMPT";
  sessionId: string;
  promptId: string;
  /** The roll's display label (e.g. "DEX (Stealth) check"). */
  label: string;
  /** The failed roll's total. */
  originalTotal: number;
  /** The DC the roll was measured against. */
  dc: number;
  /** Spendable resources offered ("INSPIRATION" / "LUCK"). */
  options: Array<"INSPIRATION" | "LUCK">;
  /** Remaining Luck Points, for display. */
  luckPoints: number;
  /** Decision window in seconds before an automatic keep. */
  secondsLeft: number;
}

/** Broadcast when an NPC's attitude toward the party changes (drives the relationship panel). */
export interface NpcStateEvent {
  type: "NPC_STATE";
  sessionId: string;
  state: NpcState;
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
  | ReactionPromptEvent
  | RerollPromptEvent
  | SystemMessageEvent
  | NpcStateEvent;
