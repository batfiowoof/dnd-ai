export type GameStatus = "WAITING" | "ACTIVE" | "FINISHED";
export type PlayerRole = "PLAYER" | "DM_AI";

export interface PlayerDto {
  id: string;
  username: string;
  characterName: string;
  role: PlayerRole;
  turnIndex: number;
  characterId: string | null;
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

export type WebSocketMessage =
  | DmResponseDto
  | SessionEvent
  | TurnChangeEvent
  | DmThinkingEvent
  | DmChunkEvent
  | DmNarrationEvent;

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
  backstory: string | null;
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
  backstory: string;
}
