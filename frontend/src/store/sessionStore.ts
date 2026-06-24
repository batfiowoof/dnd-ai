import { create } from "zustand";
import type {
  DmResponseDto,
  GameStateDto,
  PlayerDto,
  TurnEventDto,
} from "@/types";

/* ─── Chat log entry ─────────────────────────────────────────── */
export interface LogEntry {
  id: string;
  type: "action" | "dm" | "system";
  playerName?: string;
  text: string;
  turnNumber: number;
}

interface SessionState {
  players: PlayerDto[];
  status: string;
  createdBy: string | null;
  currentTurnPlayerId: string | null;
  turnNumber: number;
  logs: LogEntry[];
  connected: boolean;
  error: string;

  /* server-state seeding (React Query → store) */
  hydrateFromGameState: (gs: GameStateDto) => void;
  seedLogsFromHistory: (history: TurnEventDto[]) => void;
  setPlayers: (players: PlayerDto[]) => void;

  /* optimistic local entry (own action before the DM responds) */
  addLog: (entry: LogEntry) => void;

  /* WebSocket-driven live updates */
  applyDmResponse: (dm: DmResponseDto, playerName?: string) => void;
  setTurnChange: (nextPlayerId: string | null, turnNumber: number) => void;
  applyPlayerEvent: (gs: GameStateDto) => void;
  applyGameStarted: (gs: GameStateDto) => void;
  applyGameEnded: () => void;

  setConnected: (connected: boolean) => void;
  setError: (error: string) => void;
  reset: () => void;
}

const initialState = {
  players: [] as PlayerDto[],
  status: "WAITING",
  createdBy: null as string | null,
  currentTurnPlayerId: null as string | null,
  turnNumber: 0,
  logs: [] as LogEntry[],
  connected: false,
  error: "",
};

export const useSessionStore = create<SessionState>((set) => ({
  ...initialState,

  hydrateFromGameState: (gs) =>
    set({
      players: gs.players,
      status: gs.status,
      createdBy: gs.createdBy,
      currentTurnPlayerId: gs.currentTurnPlayerId,
      turnNumber: gs.turnNumber,
    }),

  seedLogsFromHistory: (history) =>
    set(() => {
      const entries: LogEntry[] = [];
      history.forEach((h) => {
        entries.push({
          id: `${h.id}-action`,
          type: "action",
          playerName: h.playerName,
          text: h.action,
          turnNumber: h.turnNumber,
        });
        if (h.dmResponse) {
          entries.push({
            id: `${h.id}-dm`,
            type: "dm",
            text: h.dmResponse,
            turnNumber: h.turnNumber,
          });
        }
      });
      return { logs: entries };
    }),

  setPlayers: (players) => set({ players }),

  addLog: (entry) => set((state) => ({ logs: [...state.logs, entry] })),

  applyDmResponse: (dm, playerName) =>
    set((state) => {
      const actionId = `ws-action-${dm.turnNumber}-${dm.playerId}`;
      const dmId = `ws-dm-${dm.turnNumber}-${dm.playerId}`;

      const turnFields = {
        currentTurnPlayerId: dm.nextTurnPlayerId,
        turnNumber: dm.turnNumber + 1,
      };

      // Already recorded this DM response — only advance the turn.
      if (state.logs.some((e) => e.id === dmId)) {
        return turnFields;
      }

      const newEntries: LogEntry[] = [];
      if (!state.logs.some((e) => e.id === actionId)) {
        newEntries.push({
          id: actionId,
          type: "action",
          playerName: playerName ?? "Player",
          text: dm.playerAction,
          turnNumber: dm.turnNumber,
        });
      }
      newEntries.push({
        id: dmId,
        type: "dm",
        text: dm.dmNarration,
        turnNumber: dm.turnNumber,
      });

      return { logs: [...state.logs, ...newEntries], ...turnFields };
    }),

  setTurnChange: (nextPlayerId, turnNumber) =>
    set({ currentTurnPlayerId: nextPlayerId, turnNumber }),

  applyPlayerEvent: (gs) =>
    set({ players: gs.players, status: gs.status, createdBy: gs.createdBy }),

  applyGameStarted: (gs) =>
    set((state) => ({
      players: gs.players,
      currentTurnPlayerId: gs.currentTurnPlayerId,
      turnNumber: gs.turnNumber,
      createdBy: gs.createdBy,
      status: "ACTIVE",
      logs: [
        ...state.logs,
        {
          id: "system-start",
          type: "system",
          text: "The adventure begins... The Dungeon Master awaits your actions.",
          turnNumber: 0,
        },
      ],
    })),

  applyGameEnded: () =>
    set((state) => ({
      status: "FINISHED",
      logs: [
        ...state.logs,
        {
          id: "system-end",
          type: "system",
          text: "The adventure has come to an end.",
          turnNumber: state.turnNumber,
        },
      ],
    })),

  setConnected: (connected) => set({ connected }),
  setError: (error) => set({ error }),
  reset: () => set({ ...initialState }),
}));
