export type GameStatus = "WAITING" | "ACTIVE" | "FINISHED";
export type PlayerRole = "PLAYER" | "DM_AI";

export interface PlayerDto {
  id: string;
  username: string;
  characterName: string;
  role: PlayerRole;
  turnIndex: number;
}

export interface GameStateDto {
  sessionId: string;
  joinCode: string;
  status: GameStatus;
  players: PlayerDto[];
  currentTurnPlayerId: string | null;
  turnNumber: number;
}

export interface CreateSessionRequest {
  playerName: string;
  characterName: string;
}

export interface CreateSessionResponse {
  sessionId: string;
  joinCode: string;
  playerId: string;
}

export interface JoinSessionRequest {
  playerName: string;
  characterName: string;
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

export type WebSocketMessage = DmResponseDto | SessionEvent | TurnChangeEvent;
