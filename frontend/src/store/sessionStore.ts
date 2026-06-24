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
  dmThinking: boolean;
  connected: boolean;
  error: string;

  /* server-state seeding (React Query → store) */
  hydrateFromGameState: (gs: GameStateDto) => void;
  seedLogsFromHistory: (history: TurnEventDto[]) => void;
  setPlayers: (players: PlayerDto[]) => void;

  /* optimistic local entry (own action before the DM responds) */
  addLog: (entry: LogEntry) => void;

  /* WebSocket-driven live updates */
  beginDmTurn: (args: {
    turnNumber: number;
    playerId: string;
    playerName?: string;
    action?: string;
  }) => void;
  appendDmChunk: (args: {
    turnNumber: number;
    playerId: string;
    delta: string;
  }) => void;
  applyDmNarration: (args: {
    turnNumber: number;
    playerId: string;
    dmNarration: string;
  }) => void;
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
  dmThinking: false,
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

  /* DM_THINKING — show the typing indicator and (for player turns) render the
     action line first, so order is consistent across all clients. */
  beginDmTurn: ({ turnNumber, playerId, playerName, action }) =>
    set((state) => {
      if (!action) return { dmThinking: true };
      const actionId = `ws-action-${turnNumber}-${playerId}`;
      if (state.logs.some((e) => e.id === actionId)) return { dmThinking: true };
      const entry: LogEntry = {
        id: actionId,
        type: "action",
        playerName: playerName ?? "Player",
        text: action,
        turnNumber,
      };
      return { dmThinking: true, logs: [...state.logs, entry] };
    }),

  /* DM_CHUNK — append a streamed token to the live DM entry (create on first chunk). */
  appendDmChunk: ({ turnNumber, playerId, delta }) =>
    set((state) => {
      const dmId = `ws-dm-${turnNumber}-${playerId}`;
      if (state.logs.some((e) => e.id === dmId)) {
        return {
          dmThinking: false,
          logs: state.logs.map((e) =>
            e.id === dmId ? { ...e, text: e.text + delta } : e
          ),
        };
      }
      const entry: LogEntry = { id: dmId, type: "dm", text: delta, turnNumber };
      return { dmThinking: false, logs: [...state.logs, entry] };
    }),

  /* DM_NARRATION — finalize the opening narration text (turn 0, no turn advance). */
  applyDmNarration: ({ turnNumber, playerId, dmNarration }) =>
    set((state) => {
      const dmId = `ws-dm-${turnNumber}-${playerId}`;
      if (state.logs.some((e) => e.id === dmId)) {
        return {
          dmThinking: false,
          logs: state.logs.map((e) =>
            e.id === dmId ? { ...e, text: dmNarration } : e
          ),
        };
      }
      const entry: LogEntry = {
        id: dmId,
        type: "dm",
        text: dmNarration,
        turnNumber,
      };
      return { dmThinking: false, logs: [...state.logs, entry] };
    }),

  /* DmResponseDto — the canonical, persisted DM response. Reconciles the streamed
     entry with the authoritative text and advances the turn. */
  applyDmResponse: (dm, playerName) =>
    set((state) => {
      const actionId = `ws-action-${dm.turnNumber}-${dm.playerId}`;
      const dmId = `ws-dm-${dm.turnNumber}-${dm.playerId}`;

      const turnFields = {
        currentTurnPlayerId: dm.nextTurnPlayerId,
        turnNumber: dm.turnNumber + 1,
        dmThinking: false,
      };

      let logs = state.logs;

      // Ensure the action line exists (DM_THINKING usually added it already).
      if (!logs.some((e) => e.id === actionId)) {
        const action: LogEntry = {
          id: actionId,
          type: "action",
          playerName: playerName ?? "Player",
          text: dm.playerAction,
          turnNumber: dm.turnNumber,
        };
        logs = [...logs, action];
      }

      // Upsert the DM narration with the final, authoritative text.
      if (logs.some((e) => e.id === dmId)) {
        logs = logs.map((e) =>
          e.id === dmId ? { ...e, text: dm.dmNarration } : e
        );
      } else {
        const narration: LogEntry = {
          id: dmId,
          type: "dm",
          text: dm.dmNarration,
          turnNumber: dm.turnNumber,
        };
        logs = [...logs, narration];
      }

      return { logs, ...turnFields };
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
          text: "The adventure begins...",
          turnNumber: 0,
        },
      ],
    })),

  applyGameEnded: () =>
    set((state) => ({
      status: "FINISHED",
      dmThinking: false,
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
