"use client";

import { useQuery } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import {
  getSpell,
  getEquipment,
  getMagicItem,
  nameToIndex,
  type ApiEquipment,
  type ApiDamage,
} from "@/lib/dnd5eapi";

/**
 * SRD reference lookups for the in-session character sheet. Names stored in the
 * runtime state (spells, inventory items) are resolved to dnd5eapi.co content on
 * demand. Lookups are:
 *   • lazy — only fired when a disclosure row is expanded (`enabled`),
 *   • cached forever (the SRD catalog is static),
 *   • silently degrading — a 404 / flaky API yields `null`, so a custom or
 *     non-SRD item simply shows "No description available" instead of erroring.
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

function damageText(label: string, d?: ApiDamage): string | null {
  if (!d?.damage_dice) return null;
  const type = d.damage_type?.name ? ` ${d.damage_type.name.toLowerCase()}` : "";
  return `${label}: ${d.damage_dice}${type}`;
}

/** Synthesize readable facts/prose from an equipment payload (weapon/armor/gear). */
function buildEquipmentInfo(e: ApiEquipment): SrdInfo {
  const facts: string[] = [];

  // Weapons — the useful data is structured, not in `desc`.
  const dmg = damageText("Damage", e.damage);
  if (dmg) facts.push(dmg);
  const versatile = damageText("Two-handed", e.two_handed_damage);
  if (versatile) facts.push(versatile);
  if (e.range && (e.range.normal > 5 || e.range.long)) {
    facts.push(
      `Range: ${e.range.normal}${e.range.long ? `/${e.range.long}` : ""} ft`
    );
  }
  if (e.properties?.length) {
    facts.push(`Properties: ${e.properties.map((p) => p.name).join(", ")}`);
  }

  // Armor.
  if (e.armor_class) {
    const dex = e.armor_class.dex_bonus
      ? e.armor_class.max_bonus != null
        ? ` + Dex (max ${e.armor_class.max_bonus})`
        : " + Dex"
      : "";
    facts.push(`Armor Class: ${e.armor_class.base}${dex}`);
  }
  if (e.str_minimum) facts.push(`Requires Str ${e.str_minimum}`);
  if (e.stealth_disadvantage) facts.push("Disadvantage on Stealth");

  const subtitleBits = [
    e.weapon_category && `${e.weapon_category} weapon`,
    e.armor_category && `${e.armor_category} armor`,
  ].filter(Boolean) as string[];

  return {
    name: e.name,
    subtitle: subtitleBits[0] ?? e.equipment_category?.name,
    facts,
    paragraphs: (e.desc ?? []).filter((p) => p.trim().length > 0),
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
        return {
          name: s.name,
          subtitle: spellSubtitle(s.level, s.school?.name),
          facts: [],
          paragraphs: (s.desc ?? []).filter((p) => p.trim().length > 0),
        };
      } catch {
        return null;
      }
    },
  });
}

/**
 * Lazy equipment lookup. Tries the mundane `/equipment` catalog first, then
 * falls back to `/magic-items` (potions, scrolls, wondrous items — including the
 * "Potion of Healing" the backend seeds for every character). Returns `null`
 * when neither resolves.
 */
export function useSrdEquipment(name: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.dnd5e.equipmentByName(name),
    enabled: enabled && !!name,
    ...STATIC,
    queryFn: async (): Promise<SrdInfo | null> => {
      const idx = nameToIndex(name);
      try {
        return buildEquipmentInfo(await getEquipment(idx));
      } catch {
        try {
          return buildEquipmentInfo(await getMagicItem(idx));
        } catch {
          return null;
        }
      }
    },
  });
}
