"use client";

import { useEffect } from "react";
import {
  useGameState,
  useSessionHistory,
  useSessionPlayers,
} from "@/hooks/useSessionQueries";
import {
  useSessionStates,
  useActiveCombat,
} from "@/hooks/usePlayerStateQueries";
import { useSessionStore } from "@/store/sessionStore";
import { useToast } from "@/components/ui";

/**
 * Seeds the Zustand session store from the REST/React-Query layer for a session: the
 * canonical game state, chat history, lobby player list, per-player runtime stats, and any
 * active combat. Each query writes into the store via its matching action; nothing is
 * returned — sub-components subscribe to the store directly.
 *
 * Gating semantics are preserved verbatim from the original page:
 *  - `loadedStatus` is the status captured at load time (NOT the live store status), so a
 *    WebSocket GAME_STARTED transition can't re-trigger the one-shot history fetch and
 *    clobber live WS logs.
 *  - players poll only while WAITING; runtime states fetch the moment the session is live
 *    (keyed off the LIVE status, not the frozen one); history/combat key off `loadedStatus`.
 *
 * @param sessionId       session being viewed
 * @param scrollToBottom  page-owned scroll helper, invoked after seeding history
 */
export function useLobbyData(
  sessionId: string,
  scrollToBottom: () => void
) {
  const toast = useToast();

  const status = useSessionStore((s) => s.status);
  const hydrateFromGameState = useSessionStore((s) => s.hydrateFromGameState);
  const seedLogsFromHistory = useSessionStore((s) => s.seedLogsFromHistory);
  const setPlayers = useSessionStore((s) => s.setPlayers);
  const setRuntimeStates = useSessionStore((s) => s.setRuntimeStates);
  const setCombat = useSessionStore((s) => s.setCombat);

  /* ── load initial state (React Query → seed Zustand store) ──── */
  const gameStateQuery = useGameState(sessionId);
  // Whether the session was already underway at load time — drives the
  // one-shot history fetch. Derived from the seed query (not live status) so a
  // WebSocket GAME_STARTED transition can't re-trigger it and clobber WS logs.
  const loadedStatus = gameStateQuery.data?.status;
  const historyQuery = useSessionHistory(
    sessionId,
    loadedStatus === "ACTIVE" || loadedStatus === "FINISHED"
  );

  useEffect(() => {
    if (gameStateQuery.data) hydrateFromGameState(gameStateQuery.data);
  }, [gameStateQuery.data, hydrateFromGameState]);

  useEffect(() => {
    if (gameStateQuery.isError) toast.error("Failed to load session");
  }, [gameStateQuery.isError, toast]);

  useEffect(() => {
    if (historyQuery.data) {
      seedLogsFromHistory(historyQuery.data);
      scrollToBottom();
    }
  }, [historyQuery.data, seedLogsFromHistory, scrollToBottom]);

  /* ── players poll (lobby WAITING only) → seed store ─────────── */
  const playersQuery = useSessionPlayers(sessionId, {
    poll: status === "WAITING",
  });
  useEffect(() => {
    if (playersQuery.data) setPlayers(playersQuery.data);
  }, [playersQuery.data, setPlayers]);

  /* ── runtime states (HP / slots / inventory) → seed store ─────
     Gated on the LIVE status (not the frozen loadedStatus): when the creator
     starts the game from the lobby, loadedStatus stays "WAITING", so keying the
     one-shot fetch off it left stats empty until the first PLAYER_STATE event
     (i.e. the first turn). Runtime state is seeded on join/create, so fetching
     the moment the session is ACTIVE is correct and clobber-free (staleTime ∞). */
  const statesQuery = useSessionStates(
    sessionId,
    status === "ACTIVE" ||
      status === "FINISHED" ||
      loadedStatus === "ACTIVE" ||
      loadedStatus === "FINISHED"
  );
  useEffect(() => {
    if (statesQuery.data) setRuntimeStates(statesQuery.data);
  }, [statesQuery.data, setRuntimeStates]);

  /* ── active combat (resume on reload) → seed store ──────────── */
  const combatQuery = useActiveCombat(
    sessionId,
    loadedStatus === "ACTIVE" || loadedStatus === "FINISHED"
  );
  useEffect(() => {
    if (combatQuery.data !== undefined) setCombat(combatQuery.data);
  }, [combatQuery.data, setCombat]);
}
