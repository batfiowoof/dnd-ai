"use client";

import { useQuery } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import { useRequireToken } from "@/hooks/useRequireToken";
import { getActiveCombat, getSessionStates } from "@/lib/api";

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
