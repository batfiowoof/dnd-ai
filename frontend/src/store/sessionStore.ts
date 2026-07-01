import { create } from "zustand";
import type {
  CombatActionEvent,
  CombatLifecycleEvent,
  CombatStateDto,
  DiceRollEvent,
  DmResponseDto,
  GameStateDto,
  PlayerDto,
  PlayerRuntimeState,
  RoundStatusEvent,
  TurnEventDto,
  TurnMode,
} from "@/types";
import {
  prependFeed,
  feedFromRoll,
  feedFromAction,
  type FeedEntry,
  type FeedRoll,
} from "./feedBuilders";

/* ─── Collaborative round collection status ──────────────────── */
export interface RoundStatus {
  secondsLeft: number;
  submitted: number;
  total: number;
  open: boolean;
}

/* ─── Chat log entry ─────────────────────────────────────────── */
export interface LogEntry {
  id: string;
  type: "action" | "dm" | "system" | "roll";
  playerName?: string;
  text: string;
  turnNumber: number;
  /** Raw streamed accumulation for DM entries (keeps the [[ tag anchor so live-strip is stable). */
  raw?: string;
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

/** Re-exported so existing `@/store/sessionStore` imports of the feed types keep working. */
export type { FeedRoll, FeedEntry };

interface SessionState {
  players: PlayerDto[];
  status: string;
  createdBy: string | null;
  currentTurnPlayerId: string | null;
  turnNumber: number;
  turnMode: TurnMode;
  logs: LogEntry[];
  dmThinking: boolean;
  connected: boolean;
  lastRoll: DiceRoll | null;
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
  combat: CombatStateDto | null;
  /**
   * FIFO buffer of combat actions awaiting animation. The backend resolves a whole
   * combat beat (the player's action, then every enemy turn) in one burst; queueing
   * lets the modal play them back one at a time, in order, instead of only ever showing
   * the last event (the old single-slot bug where it looked like the enemy acted first).
   */
  combatActionQueue: (CombatActionEvent & { eventId: string })[];
  /** The local player's id — used to route their OWN rolls to the big modal vs the side feed. */
  myPlayerId: string | null;
  /** Compact non-blocking roll feed shown docked on the battle map. */
  combatFeed: FeedEntry[];
  /** True between "Start Encounter" and the COMBAT_START event (drives the init loader). */
  combatInitializing: boolean;
  /**
   * True while the DM is narrating a resolved combat beat (DM_THINKING→DM_NARRATION for the
   * "combat" sentinel). Combined with a non-empty {@link combatActionQueue} it gates the local
   * player's controls so the turn doesn't snap back before the animations + narration finish.
   */
  combatNarrating: boolean;
  /** Collaborative round collection status (null when no window is open). */
  round: RoundStatus | null;
  /** End-of-session recap; null until the session ends (or while it's still being written). */
  recap: string | null;
  /** True while the recap is being generated after the session ends. */
  recapPending: boolean;

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
  applyCombatAction: (evt: CombatActionEvent) => void;
  dequeueCombatAction: () => void;
  /** Append an action's lines to the compact feed at PLAYBACK time (paced with the modal). */
  pushCombatFeed: (evt: CombatActionEvent) => void;
  /** Defensive release of the narration gate (safety timeout if DM_NARRATION never arrives). */
  clearCombatNarrating: () => void;
  setMyPlayerId: (id: string | null) => void;
  setCombatInitializing: (initializing: boolean) => void;
  setTurnChange: (nextPlayerId: string | null, turnNumber: number) => void;
  applyPlayerEvent: (gs: GameStateDto) => void;
  applyGameStarted: (gs: GameStateDto) => void;
  applyGameEnded: () => void;
  /** The recap is being generated (RECAP_PENDING). */
  beginRecap: () => void;
  /** The recap finished (RECAP_READY) — store the final text. */
  setRecap: (recap: string) => void;

  /* collaborative round */
  applyRoundStatus: (evt: RoundStatusEvent) => void;

  setConnected: (connected: boolean) => void;
  reset: () => void;
}

const initialState = {
  players: [] as PlayerDto[],
  status: "WAITING",
  createdBy: null as string | null,
  currentTurnPlayerId: null as string | null,
  turnNumber: 0,
  turnMode: "COLLABORATIVE" as TurnMode,
  logs: [] as LogEntry[],
  dmThinking: false,
  connected: false,
  lastRoll: null as DiceRoll | null,
  runtimeByPlayerId: {} as Record<string, PlayerRuntimeState>,
  combat: null as CombatStateDto | null,
  combatActionQueue: [] as (CombatActionEvent & { eventId: string })[],
  myPlayerId: null as string | null,
  combatFeed: [] as FeedEntry[],
  combatInitializing: false,
  combatNarrating: false,
  round: null as RoundStatus | null,
  recap: null as string | null,
  recapPending: false,
};

/** Sentinel playerId the backend uses for streamed combat-beat narration. */
const COMBAT_NARRATION_KEY = "combat";

export const useSessionStore = create<SessionState>((set) => ({
  ...initialState,

  hydrateFromGameState: (gs) =>
    set({
      players: gs.players,
      status: gs.status,
      createdBy: gs.createdBy,
      currentTurnPlayerId: gs.currentTurnPlayerId,
      turnNumber: gs.turnNumber,
      turnMode: gs.turnMode ?? "COLLABORATIVE",
      recap: gs.recap ?? null,
    }),

  seedLogsFromHistory: (history) =>
    set(() => {
      const entries: LogEntry[] = [];
      history.forEach((h) => {
        // Combat beats aren't player speech — the `action` is a mechanical summary that
        // appeared live as dice/HP modals, not a chat bubble. Replay it as a neutral
        // system line so it isn't falsely attributed to a player.
        if (h.source === "COMBAT") {
          entries.push({
            id: `${h.id}-combat`,
            type: "system",
            text: h.action,
            turnNumber: h.turnNumber,
          });
        } else {
          entries.push({
            id: `${h.id}-action`,
            type: "action",
            playerName: h.playerName,
            text: h.action,
            turnNumber: h.turnNumber,
          });
        }
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
      // A "combat" beat narration is streaming — gate the local controls until it finishes.
      const narrating =
        playerId === COMBAT_NARRATION_KEY ? true : state.combatNarrating;
      // The DM is now resolving — the collaborative collection window has flushed.
      if (!action)
        return { dmThinking: true, round: null, combatNarrating: narrating };
      const actionId = `ws-action-${turnNumber}-${playerId}`;
      if (state.logs.some((e) => e.id === actionId))
        return { dmThinking: true, round: null, combatNarrating: narrating };
      const entry: LogEntry = {
        id: actionId,
        type: "action",
        playerName: playerName ?? "Player",
        text: action,
        turnNumber,
      };
      return {
        dmThinking: true,
        round: null,
        combatNarrating: narrating,
        logs: [...state.logs, entry],
      };
    }),

  /* DM_CHUNK — append a streamed token to the live DM entry (create on first chunk).
     Directive tags ([[ENCOUNTER…]] / [[ROLL…]]) are stripped from the live view so the raw
     marker never flashes; the final DmResponse reconciles with the authoritative clean text. */
  appendDmChunk: ({ turnNumber, playerId, delta }) =>
    set((state) => {
      const dmId = `ws-dm-${turnNumber}-${playerId}`;
      if (state.logs.some((e) => e.id === dmId)) {
        return {
          dmThinking: false,
          logs: state.logs.map((e) => {
            if (e.id !== dmId) return e;
            const raw = (e.raw ?? "") + delta;
            return { ...e, raw, text: stripTags(raw) };
          }),
        };
      }
      const entry: LogEntry = {
        id: dmId,
        type: "dm",
        raw: delta,
        text: stripTags(delta),
        turnNumber,
      };
      return { dmThinking: false, logs: [...state.logs, entry] };
    }),

  /* DM_NARRATION — finalize the opening narration text (turn 0, no turn advance). */
  applyDmNarration: ({ turnNumber, playerId, dmNarration }) =>
    set((state) => {
      // Combat beat narration finished streaming — release the controls gate.
      const narrating =
        playerId === COMBAT_NARRATION_KEY ? false : state.combatNarrating;
      const dmId = `ws-dm-${turnNumber}-${playerId}`;
      if (state.logs.some((e) => e.id === dmId)) {
        return {
          dmThinking: false,
          combatNarrating: narrating,
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
      return {
        dmThinking: false,
        combatNarrating: narrating,
        logs: [...state.logs, entry],
      };
    }),

  /* DmResponseDto — the canonical, persisted DM response. Reconciles the streamed
     entry with the authoritative text and advances the turn. */
  applyDmResponse: (dm, playerName) =>
    set((state) => {
      const actionId = `ws-action-${dm.turnNumber}-${dm.playerId}`;
      const dmId = `ws-dm-${dm.turnNumber}-${dm.playerId}`;

      // Only initiative mode rotates a pointer — the backend sends nextTurnPlayerId for it and
      // null for collaborative/freeform. Keep the existing pointer when none is given. Always
      // clear the round-collection indicator (this round has resolved).
      const turnFields = {
        currentTurnPlayerId: dm.nextTurnPlayerId ?? state.currentTurnPlayerId,
        turnNumber: dm.turnNumber + 1,
        dmThinking: false,
        round: null as RoundStatus | null,
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
      // Big centre modal only for the LOCAL player's own roll; everyone else's
      // (enemy/NPC, other players, others' death saves) goes to the side feed only.
      const mine = !!state.myPlayerId && evt.playerId === state.myPlayerId;
      return {
        lastRoll: mine ? roll : state.lastRoll,
        logs: [...state.logs, entry],
        combatFeed: prependFeed(state.combatFeed, [feedFromRoll(evt)]),
      };
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
          combatActionQueue: [],
          combatFeed: [],
          combatInitializing: false,
          combatNarrating: false,
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
        // The battlefield has arrived — clear the init loader.
        combatInitializing: false,
        logs: note ? [...state.logs, note] : state.logs,
      };
    }),

  /* COMBAT_ACTION — refresh combat (HP/positions) and queue the action for paced
     one-at-a-time playback in the centre modal. EVERY action is queued (the player's own,
     enemies', and other players') so the whole beat is watchable in initiative order. The
     compact feed is NOT populated here: it's pushed at PLAYBACK time (see pushCombatFeed)
     so its lines appear one-by-one in step with the modal rather than all at once. */
  applyCombatAction: (evt) =>
    set((s) => ({
      combat: evt.combat,
      combatActionQueue: [
        ...s.combatActionQueue,
        {
          ...evt,
          eventId: `ca-${evt.seq}-${Date.now()}-${Math.random()
            .toString(36)
            .slice(2, 6)}`,
        },
      ],
    })),

  /* Drop the head of the queue once its animation has played. */
  dequeueCombatAction: () =>
    set((s) => ({ combatActionQueue: s.combatActionQueue.slice(1) })),

  /* Push one action's lines to the feed when the modal reveals it, pacing the corner feed
     with the centre-modal playback. */
  pushCombatFeed: (evt) =>
    set((s) => ({ combatFeed: prependFeed(s.combatFeed, feedFromAction(evt)) })),

  clearCombatNarrating: () => set({ combatNarrating: false }),

  setMyPlayerId: (id) => set({ myPlayerId: id }),
  setCombatInitializing: (initializing) =>
    set({ combatInitializing: initializing }),

  setTurnChange: (nextPlayerId, turnNumber) =>
    set({ currentTurnPlayerId: nextPlayerId, turnNumber }),

  applyPlayerEvent: (gs) =>
    set({ players: gs.players, status: gs.status, createdBy: gs.createdBy }),

  applyGameStarted: (gs) =>
    set((state) => ({
      players: gs.players,
      currentTurnPlayerId: gs.currentTurnPlayerId,
      turnNumber: gs.turnNumber,
      turnMode: gs.turnMode ?? state.turnMode,
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
      recapPending: true,
    })),

  beginRecap: () => set({ recapPending: true }),

  setRecap: (recap) => set({ recap: recap || null, recapPending: false }),

  /* ROUND_STATUS — live collaborative collection indicator. */
  applyRoundStatus: (evt) =>
    set({
      round: evt.open
        ? {
            secondsLeft: evt.secondsLeft,
            submitted: evt.submitted,
            total: evt.total,
            open: true,
          }
        : null,
    }),

  setConnected: (connected) => set({ connected }),
  reset: () => set({ ...initialState }),
}));

/**
 * Strip a directive tag from streamed DM text so the raw [[ENCOUNTER…]] / [[ROLL…]] marker
 * never flashes mid-stream. Tags are emitted as the final line, so everything from a complete
 * opening marker to the end is hidden; a trailing PARTIAL marker still being streamed (e.g.
 * "[", "[[", "[[RO") is also suppressed so a split bracket can't leak character-by-character.
 * Run on the retained raw accumulation (which keeps the "[[" anchor) — the final, authoritative
 * text comes from the DmResponseDto, so aggressive live stripping is safe.
 */
function stripTags(text: string): string {
  return text
    // complete opening marker → end of text
    .replace(/\n?\[\[\s*(?:ENCOUNTER|ROLL)\b[\s\S]*$/i, "")
    // trailing partial opening still streaming ("[", "[[", "[[E", "[[ROL", …)
    .replace(
      /\[\[?\s*(?:E(?:N(?:C(?:O(?:U(?:N(?:T(?:E(?:R)?)?)?)?)?)?)?)?|R(?:O(?:L(?:L)?)?)?)?$/i,
      ""
    )
    .trimEnd();
}
