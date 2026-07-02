"use client";

import { useQuery } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import { useRequireToken } from "@/hooks/useRequireToken";
import { getTravelMap } from "@/lib/api";

/**
 * Fetches the session's travel map (locations + routes). The party's live position and in-game
 * clock come from the Zustand store (seeded by game state, updated by the LOCATION_CHANGED event),
 * so this only supplies the relatively static location graph and never auto-refetches.
 */
export function useTravelMap(sessionId: string, enabled: boolean) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.session.map(sessionId),
    queryFn: async () => getTravelMap(await requireToken(), sessionId),
    enabled: !!sessionId && enabled,
    staleTime: Infinity,
  });
}
