/**
 * Small client-side combat helpers shared by the tracker and the battle map: which side a
 * spell targets, and how to read a spell's range into feet for grid range-gating.
 */
import type { InventoryItem, SpellSummary } from "@/types";
import { conditionMeta } from "@/lib/conditions";

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

/**
 * A compact one-line mechanical summary of a spell for the casting UI, e.g.
 * "8d6 Fire · DEX save (half)", "Heals 2d8", "STR save → Restrained", "20-ft sphere".
 */
export function spellMechanics(s: SpellSummary): string {
  const parts: string[] = [];
  if (s.damageDice) {
    parts.push(`${s.damageDice}${s.damageType ? ` ${s.damageType}` : ""}`);
  }
  if (s.healDice) parts.push(`Heals ${s.healDice}`);
  if (s.saveAbility) {
    parts.push(`${s.saveAbility} save${s.halfOnSave ? " (half)" : ""}`);
  }
  if (s.condition) parts.push(`→ ${conditionMeta(s.condition).label}`);
  if (s.aoeShape && s.aoeSize > 0) parts.push(`${s.aoeSize}-ft ${s.aoeShape}`);
  if (parts.length === 0) {
    // No parsed mechanics — fall back to range so the line isn't empty.
    parts.push(s.range);
  }
  return parts.join(" · ");
}

/* ── Weapon attack range (mirrors CombatService.attackRangeFeet) ── */

/** Keyword → normal range (ft) for a ranged/thrown or reach weapon, longest match wins. */
const WEAPON_RANGE: Array<[string, number]> = [
  ["longbow", 150],
  ["crossbow", 80],
  ["shortbow", 80],
  ["bow", 80],
  ["blowgun", 25],
  ["sling", 30],
  ["javelin", 30],
  ["dart", 20],
  ["handaxe", 20],
  ["spear", 20],
  ["trident", 20],
  ["halberd", 10],
  ["glaive", 10],
  ["pike", 10],
  ["lance", 10],
  ["whip", 10],
];

/**
 * Infer the player's basic-attack range (ft) from their weapon items by name. Ranged/thrown
 * weapons get their normal range, reach weapons 10 ft, everything else (and no weapons) 5 ft
 * melee. Uses the longest reach available so an archer carrying a sword can still shoot.
 */
export function weaponRangeFeet(inventory: InventoryItem[] | undefined): number {
  let range = 5;
  for (const item of inventory ?? []) {
    // Only weapons set the range — avoids gear false matches ("Iron Spike" → "pike").
    if (item.kind !== "WEAPON") continue;
    const name = item.name.toLowerCase();
    for (const [kw, r] of WEAPON_RANGE) {
      if (name.includes(kw)) range = Math.max(range, r);
    }
  }
  return range;
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
