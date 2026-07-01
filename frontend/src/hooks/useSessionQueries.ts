"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import { useRequireToken } from "@/hooks/useRequireToken";
import {
  createSession,
  deleteSession,
  getGameState,
  getSessionByCode,
  getSessionHistory,
  getSessionPlayers,
  getUserSessions,
  joinSession,
  kickPlayer,
  leaveSession,
  startSession,
} from "@/lib/api";
import type {
  CreateSessionRequest,
  JoinSessionRequest,
} from "@/types";

/* ── Queries ─────────────────────────────────────────────────── */

/**
 * Fetches the session state once to seed the Zustand store. WebSocket events
 * are the continuous source of truth afterwards, so this never auto-refetches.
 */
export function useGameState(sessionId: string) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.session.state(sessionId),
    queryFn: async () => getGameState(await requireToken(), sessionId),
    enabled: !!sessionId,
    staleTime: Infinity,
  });
}

export function useSessionHistory(sessionId: string, enabled: boolean) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.session.history(sessionId),
    queryFn: async () => getSessionHistory(await requireToken(), sessionId),
    enabled: !!sessionId && enabled,
    staleTime: Infinity,
  });
}

/**
 * Player list. Polls only while `poll` is true (lobby WAITING) — safe because
 * the lobby has no logs/turns for a refetch to clobber.
 */
export function useSessionPlayers(
  sessionId: string,
  opts?: { poll?: boolean }
) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.session.players(sessionId),
    queryFn: async () => getSessionPlayers(await requireToken(), sessionId),
    enabled: !!sessionId,
    refetchInterval: opts?.poll ? 3000 : false,
  });
}

/** The current user's sessions (created or joined), for the "your adventures" list. */
export function useUserSessions(enabled = true) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.session.mine(),
    queryFn: async () => getUserSessions(await requireToken()),
    enabled,
    staleTime: 30_000,
  });
}

/* ── Mutations ───────────────────────────────────────────────── */

export function useCreateSession() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async (body: CreateSessionRequest) =>
      createSession(await requireToken(), body),
  });
}

export function useJoinByCode() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async ({
      code,
      body,
    }: {
      code: string;
      body: JoinSessionRequest;
    }) => {
      const token = await requireToken();
      const gameState = await getSessionByCode(token, code);
      // Already a member? Rejoin without calling POST /join — the backend join is idempotent, but
      // short-circuiting here avoids a needless round trip and works for an already-ACTIVE session.
      const existing = gameState.players.find(
        (p) => p.username === body.playerName
      );
      const player =
        existing ?? (await joinSession(token, gameState.sessionId, body));
      return { gameState, player };
    },
  });
}

export function useStartSession() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async (sessionId: string) =>
      startSession(await requireToken(), sessionId),
  });
}

export function useLeaveSession() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (sessionId: string) =>
      leaveSession(await requireToken(), sessionId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.session.mine() });
    },
  });
}

export function useDeleteSession() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (sessionId: string) =>
      deleteSession(await requireToken(), sessionId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.session.mine() });
    },
  });
}

export function useKickPlayer() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      sessionId,
      playerId,
    }: {
      sessionId: string;
      playerId: string;
    }) => kickPlayer(await requireToken(), sessionId, playerId),
    onSuccess: (_data, { sessionId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.session.players(sessionId),
      });
    },
  });
}
