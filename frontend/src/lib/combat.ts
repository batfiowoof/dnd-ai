/**
 * Small client-side combat helpers shared by the tracker and the battle map: which side a
 * spell targets, and how to read a spell's range into feet for grid range-gating.
 */
import type { SpellSummary } from "@/types";

/** True when a spell affects allies/self (heals & buffs) rather than enemies. */
export function isAllyTargeting(s: SpellSummary): boolean {
  return (
    s.effectType === "HEAL" ||
    s.effectType === "BUFF" ||
    s.targetType === "ALLY" ||
    s.targetType === "SELF"
  );
}

/**
 * Parse a spell's range string into feet for grid targeting. "Self" → 0, "Touch" → 5,
 * "60 feet" → 60. Anything unrecognised → Infinity (no range gate).
 */
export function parseRangeFeet(range: string | null | undefined): number {
  if (!range) return Infinity;
  const r = range.toLowerCase();
  if (r.startsWith("self")) return 0;
  if (r.startsWith("touch")) return 5;
  const m = r.match(/(\d+)\s*(?:feet|foot|ft)/);
  return m ? parseInt(m[1], 10) : Infinity;
}

/** Max selectable targets for a non-AoE targeted spell (null/0 → single target). */
export function targetCap(s: SpellSummary): number {
  return s.maxTargets && s.maxTargets > 0 ? s.maxTargets : 1;
}

/* ── Attack advantage/disadvantage preview (client mirror of ConditionRules.attackMode) ── */

export type AttackMode = "advantage" | "disadvantage" | "normal";

const has = (conds: string[] | undefined, name: string) =>
  !!conds?.some((c) => c.toLowerCase() === name);

/**
 * Preview the net roll mode for an attacker (with `attackerConds`) striking a defender (with
 * `defenderConds`). Mirrors the server's SRD rules so the UI can show ADV/DIS before the roll.
 * Advantage and disadvantage cancel to "normal".
 */
export function attackModePreview(
  attackerConds: string[] | undefined,
  defenderConds: string[] | undefined,
  melee: boolean
): AttackMode {
  const advantage =
    has(defenderConds, "blinded") ||
    has(defenderConds, "paralyzed") ||
    has(defenderConds, "petrified") ||
    has(defenderConds, "restrained") ||
    has(defenderConds, "stunned") ||
    has(defenderConds, "unconscious") ||
    has(defenderConds, "faerie-fire") ||
    (has(defenderConds, "prone") && melee);
  const disadvantage =
    has(attackerConds, "blinded") ||
    has(attackerConds, "frightened") ||
    has(attackerConds, "poisoned") ||
    has(attackerConds, "prone") ||
    has(attackerConds, "restrained") ||
    has(attackerConds, "enfeebled") ||
    (has(defenderConds, "prone") && !melee);
  if (advantage && disadvantage) return "normal";
  if (advantage) return "advantage";
  if (disadvantage) return "disadvantage";
  return "normal";
}
