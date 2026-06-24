"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useCallback } from "react";
import { useAuth } from "@/context/AuthContext";
import { queryKeys } from "@/lib/queryKeys";
import {
  createSession,
  getGameState,
  getSessionByCode,
  getSessionHistory,
  getSessionPlayers,
  joinSession,
  kickPlayer,
  startSession,
} from "@/lib/api";
import type {
  CreateSessionRequest,
  JoinSessionRequest,
} from "@/types";

/** Returns a function that resolves a fresh JWT, throwing if unauthenticated. */
function useRequireToken() {
  const { getToken } = useAuth();
  return useCallback(async () => {
    const token = await getToken();
    if (!token) throw new Error("Not authenticated");
    return token;
  }, [getToken]);
}

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
      const player = await joinSession(token, gameState.sessionId, body);
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
