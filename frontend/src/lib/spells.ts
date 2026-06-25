/* ── D&D 5E spell rules ───────────────────────────────────────────
 * Spell *content* (names, schools, descriptions) comes from dnd5eapi.co via
 * `hooks/useDnd5eData.ts`. This module keeps only the level-1 selection caps,
 * which the API does not expose for prepared casters (no `spells_known`).
 * Casting in play stays slot-level; chosen names are stored for sheet display.
 */

export interface Spell {
  name: string;
  level: number; // 0 = cantrip
  /** Filled from the spell detail endpoint; may be absent if that fetch failed. */
  school?: string;
  desc?: string;
}

/** Level-1 cantrips-known and spells-known counts, per caster class. */
export const SPELLCASTING: Record<string, { cantripsKnown: number; spellsKnown: number }> = {
  Bard: { cantripsKnown: 2, spellsKnown: 4 },
  Cleric: { cantripsKnown: 3, spellsKnown: 3 },
  Druid: { cantripsKnown: 2, spellsKnown: 3 },
  Sorcerer: { cantripsKnown: 4, spellsKnown: 2 },
  Warlock: { cantripsKnown: 2, spellsKnown: 2 },
  Wizard: { cantripsKnown: 3, spellsKnown: 6 },
};

/** Classes that pick spells during level-1 creation. */
export function isCaster(className: string): boolean {
  return className in SPELLCASTING;
}
