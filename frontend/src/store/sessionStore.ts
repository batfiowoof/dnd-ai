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

/* ─── Compact combat roll feed (docked on the map; non-blocking) ─── */
export interface FeedRoll {
  sides: number;
  faces: number[];
  total: number;
  crit: boolean;
  fumble: boolean;
}

/** One line in the map-docked roll feed (enemy attacks, NPC/other-player rolls). */
export interface FeedEntry {
  id: string;
  actorName: string;
  actorKind?: "PLAYER" | "ENEMY";
  title: string;
  roll: FeedRoll | null;
  outcome: string | null;
  detail: string | null;
  tone: "good" | "bad" | "neutral";
}

/** Most recent entries kept in the feed (older ones scroll out; chat log keeps the full record). */
const FEED_CAP = 8;

/** Prepend an action's entries (newest first) and cap the list. */
function prependFeed(feed: FeedEntry[], entries: FeedEntry[]): FeedEntry[] {
  if (entries.length === 0) return feed;
  return [...entries, ...feed].slice(0, FEED_CAP);
}

/** A single d20 roll line from a DiceRollEvent. */
function feedFromRoll(evt: DiceRollEvent): FeedEntry {
  return {
    id: `rf-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    actorName: evt.playerName,
    title: evt.label,
    roll: {
      sides: evt.sides,
      faces: evt.faces,
      total: evt.total,
      crit: evt.crit,
      fumble: evt.fumble,
    },
    outcome: evt.crit ? "Crit!" : evt.fumble ? "Fumble!" : null,
    detail: evt.notation,
    tone: evt.crit ? "good" : evt.fumble ? "bad" : "neutral",
  };
}

/** One feed line per target of a combat action. Pure-move actions (no targets) yield nothing. */
function feedFromAction(evt: CombatActionEvent): FeedEntry[] {
  const base = `caf-${evt.seq}-${Date.now()}-${Math.random()
    .toString(36)
    .slice(2, 6)}`;
  return evt.targets.map((t, i) => {
    const r = t.attackRoll ?? t.saveRoll;
    const roll: FeedRoll | null = r
      ? { sides: 20, faces: r.faces, total: r.total, crit: r.crit, fumble: r.fumble }
      : null;

    // Outcome + whether it benefits the party (drives colour from the players' POV).
    let outcome: string | null = null;
    let good: boolean | null = null;
    if (t.hit !== null) {
      outcome = t.hit ? "Hit" : "Miss";
      good = t.targetKind === "ENEMY" ? t.hit : !t.hit;
    } else if (t.saved !== null) {
      outcome = t.saved ? "Saved" : "Failed";
      good = t.targetKind === "ENEMY" ? !t.saved : t.saved;
    } else if (t.heal !== null && t.heal > 0) {
      outcome = "Heal";
      good = true;
    } else if (t.condition) {
      outcome = t.condition;
      good = t.targetKind === "ENEMY";
    }
    if (t.defeated) {
      outcome = "Down!";
      good = t.targetKind === "ENEMY";
    }

    const bits: string[] = [];
    if (t.damageRoll) bits.push(`${t.damageRoll.total} dmg`);
    if (t.heal !== null && t.heal > 0) bits.push(`+${t.heal}`);
    bits.push(`${Math.max(0, t.currentHp)}/${t.maxHp}`);

    const tone: FeedEntry["tone"] =
      good === true ? "good" : good === false ? "bad" : "neutral";

    return {
      id: `${base}-${i}`,
      actorName: evt.actorName,
      actorKind: evt.actorKind,
      title: `${evt.label} ${t.targetName}`.trim(),
      roll,
      outcome,
      detail: bits.join(" · "),
      tone,
    };
  });
}

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
  error: string;
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
  /** Collaborative round collection status (null when no window is open). */
  round: RoundStatus | null;

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
  setMyPlayerId: (id: string | null) => void;
  setCombatInitializing: (initializing: boolean) => void;
  setTurnChange: (nextPlayerId: string | null, turnNumber: number) => void;
  applyPlayerEvent: (gs: GameStateDto) => void;
  applyGameStarted: (gs: GameStateDto) => void;
  applyGameEnded: () => void;

  /* collaborative round */
  applyRoundStatus: (evt: RoundStatusEvent) => void;

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
  turnMode: "COLLABORATIVE" as TurnMode,
  logs: [] as LogEntry[],
  dmThinking: false,
  connected: false,
  error: "",
  lastRoll: null as DiceRoll | null,
  runtimeByPlayerId: {} as Record<string, PlayerRuntimeState>,
  combat: null as CombatStateDto | null,
  combatActionQueue: [] as (CombatActionEvent & { eventId: string })[],
  myPlayerId: null as string | null,
  combatFeed: [] as FeedEntry[],
  combatInitializing: false,
  round: null as RoundStatus | null,
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
      turnMode: gs.turnMode ?? "COLLABORATIVE",
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
      // The DM is now resolving — the collaborative collection window has flushed.
      if (!action) return { dmThinking: true, round: null };
      const actionId = `ws-action-${turnNumber}-${playerId}`;
      if (state.logs.some((e) => e.id === actionId))
        return { dmThinking: true, round: null };
      const entry: LogEntry = {
        id: actionId,
        type: "action",
        playerName: playerName ?? "Player",
        text: action,
        turnNumber,
      };
      return { dmThinking: true, round: null, logs: [...state.logs, entry] };
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

  /* COMBAT_ACTION — always refresh combat (HP/positions) and push compact feed lines.
     Only the LOCAL player's OWN action is queued for the big centre modal; enemy and
     other players' actions show in the side feed only (and update the board). */
  applyCombatAction: (evt) =>
    set((s) => {
      const me = s.players.find((p) => p.id === s.myPlayerId);
      const mine =
        !!me &&
        !!evt.actorName &&
        evt.actorName.toLowerCase() === me.characterName?.toLowerCase();
      return {
        combat: evt.combat,
        combatActionQueue: mine
          ? [
              ...s.combatActionQueue,
              {
                ...evt,
                eventId: `ca-${evt.seq}-${Date.now()}-${Math.random()
                  .toString(36)
                  .slice(2, 6)}`,
              },
            ]
          : s.combatActionQueue,
        combatFeed: prependFeed(s.combatFeed, feedFromAction(evt)),
      };
    }),

  /* Drop the head of the queue once its animation has played. */
  dequeueCombatAction: () =>
    set((s) => ({ combatActionQueue: s.combatActionQueue.slice(1) })),

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
    })),

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
  setError: (error) => set({ error }),
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
