/**
 * Display metadata for combat conditions/status effects. One source of truth shared by the
 * battle map (SVG token badges), the combat tracker (chips), the character panel, and the
 * roll feed/action modal so a condition reads the same everywhere.
 *
 * "Never color alone": every badge carries a glyph + label, and a tooltip with the full name,
 * not just a colour. Tones map onto the existing theme tokens (danger red, gold, success green).
 */

export type ConditionTone = "debuff" | "buff" | "highlight";

export interface ConditionMeta {
  /** Full human name, e.g. "Restrained" — used in tooltips / aria-labels. */
  label: string;
  /** Compact 2-char code drawn in the SVG token badge and the tracker chip. */
  code: string;
  /** Severity/role colour family. */
  tone: ConditionTone;
  /** One-line description of the mechanical effect, shown in the tooltip. */
  hint: string;
}

const META: Record<string, ConditionMeta> = {
  restrained: { label: "Restrained", code: "Rs", tone: "debuff", hint: "Speed 0; disadvantage on attacks & DEX saves; attacks against have advantage." },
  paralyzed: { label: "Paralyzed", code: "Pz", tone: "debuff", hint: "Incapacitated, can't move; auto-fails STR/DEX saves; melee hits auto-crit." },
  prone: { label: "Prone", code: "Pr", tone: "debuff", hint: "Disadvantage on attacks; melee against has advantage, ranged has disadvantage." },
  blinded: { label: "Blinded", code: "Bl", tone: "debuff", hint: "Disadvantage on attacks; attacks against have advantage." },
  frightened: { label: "Frightened", code: "Fr", tone: "debuff", hint: "Disadvantage on attacks while the source is in sight." },
  grappled: { label: "Grappled", code: "Gr", tone: "debuff", hint: "Speed becomes 0." },
  poisoned: { label: "Poisoned", code: "Po", tone: "debuff", hint: "Disadvantage on attack rolls and ability checks." },
  charmed: { label: "Charmed", code: "Ch", tone: "debuff", hint: "Can't attack the charmer; the charmer has advantage on social checks." },
  stunned: { label: "Stunned", code: "St", tone: "debuff", hint: "Incapacitated; auto-fails STR/DEX saves; attacks against have advantage." },
  incapacitated: { label: "Incapacitated", code: "In", tone: "debuff", hint: "Can take no actions or reactions — turn is skipped." },
  unconscious: { label: "Unconscious", code: "Un", tone: "debuff", hint: "Incapacitated and prone; melee hits auto-crit." },
  "faerie-fire": { label: "Faerie Fire", code: "Ff", tone: "highlight", hint: "Outlined in light — attacks against it have advantage." },
  baned: { label: "Bane", code: "Ba", tone: "debuff", hint: "−2 to attack rolls and saving throws." },
  blessed: { label: "Bless", code: "Bs", tone: "buff", hint: "+2 to attack rolls and saving throws." },
  slowed: { label: "Slowed", code: "Sl", tone: "debuff", hint: "Speed halved." },
  enfeebled: { label: "Enfeebled", code: "En", tone: "debuff", hint: "Disadvantage on Strength-based attacks." },
  blurred: { label: "Blur", code: "Bu", tone: "buff", hint: "Attacks against you have disadvantage." },
  "mage-armor": { label: "Mage Armor", code: "MA", tone: "buff", hint: "AC = 13 + DEX while unarmored." },
  "shield-of-faith": { label: "Shield of Faith", code: "SF", tone: "buff", hint: "+2 AC." },
  barkskin: { label: "Barkskin", code: "Bk", tone: "buff", hint: "AC can't be lower than 16." },
};

/** Look up a condition's display metadata, falling back to a sensible default for unknown keys. */
export function conditionMeta(name: string): ConditionMeta {
  const key = name?.toLowerCase().trim();
  if (key && META[key]) return META[key];
  const label = name ? name.charAt(0).toUpperCase() + name.slice(1) : "Unknown";
  return {
    label,
    code: (name || "?").slice(0, 2).replace(/^\w/, (c) => c.toUpperCase()),
    tone: "debuff",
    hint: label,
  };
}

/** Tailwind classes for a condition chip (pill) by tone, matching the dark-red theme tokens. */
export function conditionChipClasses(tone: ConditionTone): string {
  switch (tone) {
    case "buff":
      return "border-success/40 bg-success/15 text-success";
    case "highlight":
      return "border-gold/40 bg-gold-muted text-gold";
    default:
      return "border-accent/40 bg-accent-dark/25 text-accent-light";
  }
}

/** Hex fill/stroke for an SVG token badge by tone (kept in sync with globals.css tokens). */
export function conditionBadgeColors(tone: ConditionTone): { fill: string; text: string } {
  switch (tone) {
    case "buff":
      return { fill: "#16341f", text: "#22c55e" };
    case "highlight":
      return { fill: "#3a2f0c", text: "#c9a227" };
    default:
      return { fill: "#3b1414", text: "#f87171" };
  }
}
