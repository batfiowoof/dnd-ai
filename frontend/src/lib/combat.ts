/**
 * Small client-side combat helpers shared by the tracker and the battle map: which side a
 * spell targets, and how to read a spell's range into feet for grid range-gating.
 */
import type {
  InventoryItem,
  PlayerRuntimeState,
  RollSummary,
  SpellSummary,
} from "@/types";
import { conditionMeta } from "@/lib/conditions";

/** A castable known spell: the player's chosen name resolved to its catalog metadata. */
export interface Castable extends SpellSummary {
  /** True when this spell heals/buffs allies (so it targets the party, not enemies). */
  allyTargeting: boolean;
}

/**
 * Resolve the player's castable spells (cantrips + prepared leveled) to catalog metadata, keeping
 * only those they can pay for right now (cantrips are free; leveled need a matching slot). Only
 * PREPARED leveled spells are castable — known-but-unprepared spells are excluded.
 */
export function resolveCastable(
  state: PlayerRuntimeState | null,
  spells: SpellSummary[]
): Castable[] {
  if (!state) return [];
  const byName = new Map(spells.map((s) => [s.name.toLowerCase(), s]));
  const hasSlot = (level: number) =>
    state.spellSlots.some((s) => s.level === level && s.used < s.max);
  const known = [...(state.cantrips ?? []), ...(state.preparedSpells ?? [])];
  const seen = new Set<string>();
  const out: Castable[] = [];
  for (const name of known) {
    const meta = byName.get(name.toLowerCase());
    if (!meta || seen.has(meta.name)) continue;
    seen.add(meta.name);
    if (meta.level > 0 && !hasSlot(meta.level)) continue;
    out.push({
      ...meta,
      allyTargeting:
        meta.effectType === "HEAL" ||
        meta.effectType === "BUFF" ||
        meta.targetType === "ALLY" ||
        meta.targetType === "SELF",
    });
  }
  return out.sort((a, b) => a.level - b.level || a.name.localeCompare(b.name));
}

/**
 * Human-readable breakdown of a rolled damage expression: "[5]+3 = 8" — the individual die
 * faces, then the flat modifier, then the total. The modifier is recovered as
 * total − Σfaces, so it works for any "NdM+K" the backend rolled (including crit-doubled
 * dice, which simply arrive with more faces). Prefix with {@code roll.notation} at the call
 * site for the full "1d8+3 [5]+3 = 8" line.
 */
export function formatDamageRoll(roll: RollSummary): string {
  const faces = roll.faces.map((f) => `[${f}]`).join("");
  const mod = roll.total - roll.faces.reduce((a, b) => a + b, 0);
  const modStr = mod > 0 ? `+${mod}` : mod < 0 ? `${mod}` : "";
  return `${faces}${modStr} = ${roll.total}`;
}

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
  if (s.tempHpDice) parts.push(`Temp HP ${s.tempHpDice}`);
  if (s.saveAbility) {
    parts.push(`${s.saveAbility} save${s.halfOnSave ? " (half)" : ""}`);
  }
  if (s.condition) parts.push(`→ ${conditionMeta(s.condition).label}`);
  if (s.aoeShape && s.aoeSize > 0) parts.push(`${s.aoeSize}-ft ${s.aoeShape}`);
  if (s.terrain) parts.push("difficult terrain");
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

/* ── Weapon mastery (2024 PHB) — client mirror of CombatMath.WEAPON_MASTERY ── */

/** Keyword → 2024 mastery property; most-specific first (mirrors the backend table). */
const WEAPON_MASTERY: Array<[string, string]> = [
  ["greataxe", "Cleave"],
  ["greatsword", "Graze"],
  ["maul", "Topple"],
  ["halberd", "Cleave"],
  ["glaive", "Graze"],
  ["heavy crossbow", "Push"],
  ["light crossbow", "Slow"],
  ["hand crossbow", "Vex"],
  ["pike", "Push"],
  ["lance", "Topple"],
  ["longsword", "Sap"],
  ["battleaxe", "Topple"],
  ["warhammer", "Push"],
  ["war pick", "Sap"],
  ["morningstar", "Sap"],
  ["rapier", "Vex"],
  ["longbow", "Slow"],
  ["flail", "Sap"],
  ["trident", "Topple"],
  ["shortsword", "Vex"],
  ["scimitar", "Nick"],
  ["shortbow", "Vex"],
  ["mace", "Sap"],
  ["spear", "Sap"],
  ["handaxe", "Vex"],
  ["quarterstaff", "Topple"],
  ["javelin", "Slow"],
  ["blowgun", "Vex"],
  ["greatclub", "Push"],
  ["light hammer", "Nick"],
  ["dagger", "Nick"],
  ["dart", "Vex"],
  ["sling", "Slow"],
  ["club", "Slow"],
  ["sickle", "Nick"],
  ["whip", "Slow"],
];

/** One-line effect of each mastery, for the combat tooltip. */
export const MASTERY_INFO: Record<string, string> = {
  Cleave: "On a hit, carry the damage to a second creature within 5 ft.",
  Graze: "On a miss, still deal your ability modifier in damage.",
  Nick: "Make the off-hand attack without spending your bonus action.",
  Push: "On a hit, shove a Large-or-smaller target 10 ft away.",
  Sap: "On a hit, the target has disadvantage on its next attack.",
  Slow: "On a hit, reduce the target's speed until your next turn.",
  Topple: "On a hit, force a CON save or knock the target prone.",
  Vex: "On a hit, your next attack against the target has advantage.",
};

/** Classes that gain Weapon Mastery in the 2024 rules (mirrors backend MARTIAL_CLASSES). */
const MARTIAL_CLASSES = new Set([
  "barbarian",
  "fighter",
  "monk",
  "paladin",
  "ranger",
  "rogue",
]);

/**
 * The mastery of the player's equipped/best weapon, gated to martial classes — or null when the
 * class gains no mastery or the weapon has none. Equipped weapon wins, else the first weapon.
 */
export function weaponMasteryFor(
  inventory: InventoryItem[] | undefined,
  className: string | undefined
): string | null {
  if (!className || !MARTIAL_CLASSES.has(className.toLowerCase())) return null;
  let chosen: InventoryItem | null = null;
  for (const item of inventory ?? []) {
    if (item.kind !== "WEAPON") continue;
    if (!chosen) chosen = item;
    if (item.equipped) {
      chosen = item;
      break;
    }
  }
  if (!chosen) return null;
  const name = chosen.name.toLowerCase();
  for (const [kw, mastery] of WEAPON_MASTERY) {
    if (name.includes(kw)) return mastery;
  }
  return null;
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
    has(defenderConds, "blurred") ||
    (has(defenderConds, "prone") && !melee);
  if (advantage && disadvantage) return "normal";
  if (advantage) return "advantage";
  if (disadvantage) return "disadvantage";
  return "normal";
}
