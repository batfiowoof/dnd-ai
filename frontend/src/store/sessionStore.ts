import { create } from "zustand";
import type {
  CombatLifecycleEvent,
  CombatStateDto,
  DiceRollEvent,
  DmResponseDto,
  EnemyActionEvent,
  GameStateDto,
  PlayerDto,
  PlayerRuntimeState,
  TurnEventDto,
} from "@/types";

/* ─── Chat log entry ─────────────────────────────────────────── */
export interface LogEntry {
  id: string;
  type: "action" | "dm" | "system" | "roll";
  playerName?: string;
  text: string;
  turnNumber: number;
}

/* ─── Live dice roll (drives the roll modal animation) ────────── */
export interface DiceRoll {
  id: string; // unique per event so identical rolls still re-trigger the modal
  playerId: string;
  playerName: string;
  label: string;
  notation: string;
  count: number;
  sides: number;
  modifier: number;
  faces: number[];
  total: number;
  crit: boolean;
  fumble: boolean;
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
  lastRoll: DiceRoll | null;
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
  combat: CombatStateDto | null;
  lastEnemyAction: (EnemyActionEvent & { eventId: string }) | null;

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
  applyDiceRoll: (evt: DiceRollEvent) => void;
  setRuntimeStates: (states: PlayerRuntimeState[]) => void;
  applyPlayerState: (state: PlayerRuntimeState) => void;
  setCombat: (combat: CombatStateDto | null) => void;
  applyCombatLifecycle: (evt: CombatLifecycleEvent) => void;
  applyEnemyAction: (evt: EnemyActionEvent) => void;
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
  lastRoll: null as DiceRoll | null,
  runtimeByPlayerId: {} as Record<string, PlayerRuntimeState>,
  combat: null as CombatStateDto | null,
  lastEnemyAction: null as (EnemyActionEvent & { eventId: string }) | null,
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

  /* DICE_ROLL — authoritative roll from the backend. Sets `lastRoll` (drives the
     modal animation) and appends a compact, persistent transcript line. */
  applyDiceRoll: (evt) =>
    set((state) => {
      const id = `roll-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const roll: DiceRoll = {
        id,
        playerId: evt.playerId,
        playerName: evt.playerName,
        label: evt.label,
        notation: evt.notation,
        count: evt.count,
        sides: evt.sides,
        modifier: evt.modifier,
        faces: evt.faces,
        total: evt.total,
        crit: evt.crit,
        fumble: evt.fumble,
      };
      const flourish = evt.crit ? " — Critical!" : evt.fumble ? " — Fumble!" : "";
      const entry: LogEntry = {
        id,
        type: "roll",
        playerName: evt.playerName,
        text: `${evt.label}: ${evt.notation} → ${evt.total}${flourish}`,
        turnNumber: state.turnNumber,
      };
      return { lastRoll: roll, logs: [...state.logs, entry] };
    }),

  setRuntimeStates: (states) =>
    set(() => ({
      runtimeByPlayerId: Object.fromEntries(
        states.map((s) => [s.playerId, s])
      ),
    })),

  applyPlayerState: (state) =>
    set((s) => ({
      runtimeByPlayerId: { ...s.runtimeByPlayerId, [state.playerId]: state },
    })),

  setCombat: (combat) => set({ combat }),

  /* COMBAT_START / COMBAT_TURN keep the tracker fresh; COMBAT_END clears the
     overlay and drops a transcript line. */
  applyCombatLifecycle: (evt) =>
    set((state) => {
      if (evt.type === "COMBAT_END") {
        const text = evt.victory
          ? "The enemies are vanquished! Combat ends."
          : "The party has fallen...";
        return {
          combat: null,
          lastEnemyAction: null,
          logs: [
            ...state.logs,
            {
              id: `combat-end-${Date.now()}`,
              type: "system",
              text,
              turnNumber: state.turnNumber,
            },
          ],
        };
      }
      const note =
        evt.type === "COMBAT_START"
          ? {
              id: `combat-start-${Date.now()}`,
              type: "system" as const,
              text: "Roll for initiative — combat begins!",
              turnNumber: state.turnNumber,
            }
          : null;
      return {
        combat: evt.combat,
        logs: note ? [...state.logs, note] : state.logs,
      };
    }),

  /* ENEMY_ACTION — refresh combat (enemy HP) and trigger the action modal. */
  applyEnemyAction: (evt) =>
    set(() => ({
      combat: evt.combat,
      lastEnemyAction: {
        ...evt,
        eventId: `ea-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      },
    })),

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
