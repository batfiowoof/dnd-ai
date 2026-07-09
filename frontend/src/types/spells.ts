/* ── Combat reference data (from /api/combat/*) ───────────────── */
export type SpellEffectType =
  | "DAMAGE"
  | "HEAL"
  | "BUFF"
  | "DEBUFF"
  | "CONTROL"
  | "UTILITY";
export type SpellTargetType = "ENEMY" | "ALLY" | "SELF" | "AREA" | "ANY";

export interface SpellSummary {
  name: string;
  level: number;
  school: string;
  effectType: SpellEffectType;
  targetType: SpellTargetType;
  maxTargets: number | null;
  concentration: boolean;
  range: string;
  parsed: boolean;
  /** AoE template shape ("sphere"/"cube"/"cone"/"line"/…), or null for single/multi-target spells. */
  aoeShape: string | null;
  /** AoE size in feet (radius / cube side / cone or line length); 0 when not an area spell. */
  aoeSize: number;
  /** Casting time, e.g. "Action", "Bonus Action", "Reaction" — drives the bonus-action economy. */
  castingTime: string;
  /** Mechanical fields for the "what does this spell do" description shown while casting. */
  damageDice: string | null;
  damageType: string | null;
  healDice: string | null;
  saveAbility: string | null;
  halfOnSave: boolean;
  condition: string | null;
  /** Short flavour line (first sentence of the SRD prose). */
  summary: string;
  /** Terrain the spell creates over its area ("DIFFICULT"), else null. */
  terrain: string | null;
  /** Temp-HP granted to ally/self (e.g. "1d4+4"), else null. */
  tempHpDice: string | null;
  /** Whether the spell carries the Ritual tag (castable without a slot, out of combat). */
  ritual: boolean;
}

export interface MonsterSummary {
  key: string;
  name: string;
  cr: number | null;
  type: string | null;
  size: string | null;
  hp: number | null;
  ac: number | null;
}
