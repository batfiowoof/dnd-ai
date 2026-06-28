"use client";

import { useQuery } from "@tanstack/react-query";
import { useCallback } from "react";
import { useAuth } from "@/context/AuthContext";
import { getCombatMonsters, getCombatSpells } from "@/lib/api";

function useRequireToken() {
  const { getToken } = useAuth();
  return useCallback(async () => {
    const token = await getToken();
    if (!token) throw new Error("Not authenticated");
    return token;
  }, [getToken]);
}

/** Spell combat metadata (level / effect / target type) for the in-combat cast menu. */
export function useCombatSpells(enabled: boolean) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: ["combat", "spells"],
    queryFn: async () => getCombatSpells(await requireToken()),
    enabled,
    staleTime: Infinity,
  });
}

/** Available encounter monsters for the host's encounter picker. */
export function useCombatMonsters(enabled: boolean) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: ["combat", "monsters"],
    queryFn: async () => getCombatMonsters(await requireToken()),
    enabled,
    staleTime: Infinity,
  });
}
