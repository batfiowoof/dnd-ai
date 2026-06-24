"use client";

import { useQuery } from "@tanstack/react-query";
import { useCallback } from "react";
import { useAuth } from "@/context/AuthContext";
import { queryKeys } from "@/lib/queryKeys";
import { getActiveCombat, getSessionStates } from "@/lib/api";

function useRequireToken() {
  const { getToken } = useAuth();
  return useCallback(async () => {
    const token = await getToken();
    if (!token) throw new Error("Not authenticated");
    return token;
  }, [getToken]);
}

/**
 * One-shot fetch of every player's runtime state to seed the store. Live updates
 * thereafter arrive via PLAYER_STATE WebSocket events.
 */
export function useSessionStates(sessionId: string, enabled: boolean) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.session.states(sessionId),
    queryFn: async () => getSessionStates(await requireToken(), sessionId),
    enabled: !!sessionId && enabled,
    staleTime: Infinity,
  });
}

/** One-shot fetch of any in-progress combat to seed the store on (re)load. */
export function useActiveCombat(sessionId: string, enabled: boolean) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.session.combat(sessionId),
    queryFn: async () => getActiveCombat(await requireToken(), sessionId),
    enabled: !!sessionId && enabled,
    staleTime: Infinity,
  });
}
