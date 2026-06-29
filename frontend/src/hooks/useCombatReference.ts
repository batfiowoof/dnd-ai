"use client";

import { useQuery } from "@tanstack/react-query";
import { useRequireToken } from "@/hooks/useRequireToken";
import { getCombatMonsters, getCombatSpells } from "@/lib/api";

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
