"use client";

import { useQuery } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import { useRequireToken } from "@/hooks/useRequireToken";
import { getAvailableShops } from "@/lib/api";

/**
 * The calling player's purse plus the shops open at the party's current location. The current
 * region/subregion are part of the query key, so travelling (which updates them in the game store)
 * automatically refetches — keeping the "Shop" button and its contents in step with where the party
 * stands. The purse also refreshes so it reflects coin earned since the panel was last opened.
 */
export function useAvailableShops(
  sessionId: string,
  enabled: boolean,
  currentRegion: string | null,
  currentSubregion: string | null
) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: [
      ...queryKeys.session.shops(sessionId),
      currentRegion ?? "",
      currentSubregion ?? "",
    ],
    queryFn: async () => getAvailableShops(await requireToken(), sessionId),
    enabled: !!sessionId && enabled,
    staleTime: Infinity,
  });
}
