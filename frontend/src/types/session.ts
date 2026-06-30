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
}

/** An authored campaign milestone the DM can award once to level the whole party. */
export interface Milestone {
  key: string;
  title: string;
  description: string;
}

export interface CreateSessionRequest {
  playerName: string;
  characterId: string;
  worldSetting?: string;
  /** When set, start from a saved World (server renders its setting, milestones, monsters). */
  worldId?: string;
  turnMode?: TurnMode;
  maxPlayers?: number;
  difficulty?: Difficulty;
  dmStyle?: DmStyle;
  dmLength?: DmLength;
  allowAiCombat?: boolean;
  allowAiRolls?: boolean;
  collabWindowSeconds?: number;
  milestones?: Milestone[];
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
