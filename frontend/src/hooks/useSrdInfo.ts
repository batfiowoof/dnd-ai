"use client";

import { useQuery } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import {
  getSpell,
  getEquipment,
  nameToIndex,
  type SrdEquipment,
} from "@/lib/dnd5eapi";

/**
 * SRD reference lookups for the in-session character sheet. Names stored in the
 * runtime state (spells, inventory items) are resolved to the app's `/api/srd`
 * content on demand. Lookups are:
 *   • lazy — only fired when a disclosure row is expanded (`enabled`),
 *   • cached forever (the SRD catalog is static),
 *   • silently degrading — a 404 yields `null`, so a custom or non-SRD item
 *     simply shows "No description available" instead of erroring.
 */
const STATIC = { staleTime: Infinity, retry: 0 } as const;

export interface SrdInfo {
  name: string;
  /** Short qualifier under the title (e.g. "Evocation cantrip", "Martial weapon"). */
  subtitle?: string;
  /** Structured one-line facts (damage, AC, range, properties). */
  facts: string[];
  /** Prose paragraphs (rich for spells/gear, often empty for weapons/armor). */
  paragraphs: string[];
}

const ORDINAL = ["Cantrip", "1st", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th", "9th"];

function spellSubtitle(level: number, school?: string): string {
  if (level === 0) return school ? `${school} cantrip` : "Cantrip";
  const lvl = ORDINAL[level] ?? `${level}th`;
  return school ? `${lvl}-level ${school}` : `${lvl}-level spell`;
}

/** Synthesize readable facts/prose from an equipment payload (weapon/armor/gear). */
function buildEquipmentInfo(e: SrdEquipment): SrdInfo {
  const facts: string[] = [];

  // Weapons — the useful data is structured, not prose.
  if (e.damage) {
    const type = e.damageType ? ` ${e.damageType.toLowerCase()}` : "";
    facts.push(`Damage: ${e.damage}${type}`);
  }
  if (e.mastery) facts.push(`Mastery: ${e.mastery}`);

  // Armor.
  if (e.ac) facts.push(`Armor Class: ${e.ac}`);

  // Common.
  if (e.weight) facts.push(`Weight: ${e.weight}`);
  if (e.cost) facts.push(`Cost: ${e.cost}`);

  const subtitleBits = [
    e.weaponType && `${e.weaponType} weapon`,
    e.armorCategory && `${e.armorCategory} armor`,
  ].filter(Boolean) as string[];

  return {
    name: e.name,
    subtitle: subtitleBits[0] ?? e.category,
    facts,
    paragraphs: [],
  };
}

/** Lazy spell lookup. Returns `null` (not an error) when the name doesn't resolve. */
export function useSrdSpell(name: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.dnd5e.spellByName(name),
    enabled: enabled && !!name,
    ...STATIC,
    queryFn: async (): Promise<SrdInfo | null> => {
      try {
        const s = await getSpell(nameToIndex(name));
        const paragraphs = [s.desc, s.higherLevel]
          .filter((p): p is string => !!p && p.trim().length > 0);
        return {
          name: s.name,
          subtitle: spellSubtitle(s.level, s.school),
          facts: [],
          paragraphs,
        };
      } catch {
        return null;
      }
    },
  });
}

/**
 * Lazy equipment lookup against the `/api/srd/equipment` catalog. Returns `null`
 * when the name doesn't resolve (e.g. a seeded potion the SRD list doesn't carry).
 */
export function useSrdEquipment(name: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.dnd5e.equipmentByName(name),
    enabled: enabled && !!name,
    ...STATIC,
    queryFn: async (): Promise<SrdInfo | null> => {
      try {
        return buildEquipmentInfo(await getEquipment(nameToIndex(name)));
      } catch {
        return null;
      }
    },
  });
}
