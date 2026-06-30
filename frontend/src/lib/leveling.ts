/* ── D&D 5E character advancement (frontend mirror of LevelingRules) ──
 * Display values and level-up picker caps only. The backend stays authoritative for
 * the stats it actually applies; this module just drives what the Level Up UI shows
 * and offers. Deliberately reduced (in the spirit of SpellSlotTable / lib/spells.ts).
 */

import type { ClassInfo } from "@/lib/dnd5e";
import { isCasterClass } from "@/lib/characterCreation";

export const MAX_LEVEL = 20;
export const ABILITY_CAP = 20;

const ASI_LEVELS = new Set([4, 8, 12, 16, 19]);

/** Proficiency bonus for a level: +2 at 1–4, +3 at 5–8 … +6 at 17–20. */
export function proficiencyBonusForLevel(level: number): number {
  const clamped = Math.max(1, Math.min(level, MAX_LEVEL));
  return 2 + Math.floor((clamped - 1) / 4);
}

/** Whether reaching `level` grants an Ability Score Improvement. */
export function isAsiLevel(level: number): boolean {
  return ASI_LEVELS.has(level);
}

/** Fixed average HP for a hit die taken after level 1 (d6→4, d8→5, d10→6, d12→7). */
export function fixedHpForHitDie(hitDie: number): number {
  return Math.floor(hitDie / 2) + 1;
}

/** Highest spell level a caster of this class+level can cast (0 for non-casters). */
export function highestSpellLevel(
  selectedClass: ClassInfo | null,
  level: number
): number {
  if (!isCasterClass(selectedClass) || !selectedClass) return 0;
  const c = selectedClass.name.toLowerCase();
  const half = c === "paladin" || c === "ranger" || c === "warlock";
  const effective = half ? Math.floor(level / 2) : level;
  if (effective < 1) return 0;
  return Math.min(9, Math.ceil(effective / 2));
}

export interface SpellPicks {
  /** New cantrips the picker offers this level. */
  cantrips: number;
  /** New leveled spells the picker offers this level. */
  spells: number;
  /** Highest spell level the new picks may be chosen from. */
  maxSpellLevel: number;
}

/**
 * How many new spells/cantrips the level-up picker offers a caster reaching `newLevel`.
 * Simplified: one new leveled spell per level, plus a new cantrip at the canonical
 * cantrip-progression levels (4 and 10). Non-casters get nothing.
 */
export function newSpellPicksFor(
  selectedClass: ClassInfo | null,
  newLevel: number
): SpellPicks {
  if (!isCasterClass(selectedClass)) {
    return { cantrips: 0, spells: 0, maxSpellLevel: 0 };
  }
  const maxSpellLevel = highestSpellLevel(selectedClass, newLevel);
  return {
    cantrips: newLevel === 4 || newLevel === 10 ? 1 : 0,
    spells: maxSpellLevel > 0 ? 1 : 0,
    maxSpellLevel,
  };
}
