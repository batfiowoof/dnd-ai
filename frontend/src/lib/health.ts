/**
 * Display metadata for enemy health bands. The server hides exact enemy HP and sends only a
 * coarse band (5E "you don't know a monster's HP"); this maps each band to a label, a colour,
 * and a ring/bar fill fraction so the battle map and tracker can render it consistently with
 * the dark-red theme. Player/ally HP stays exact and is rendered from numbers as before.
 */

export type HealthBandKey =
  | "healthy"
  | "hurt"
  | "bloodied"
  | "critical"
  | "down";

export interface BandMeta {
  label: string;
  /** SVG stroke/fill colour (theme-aligned hex). */
  color: string;
  /** Tailwind classes for a chip/bar tint. */
  barClass: string;
  /** Ring/bar fill fraction (visual only — not the real HP ratio). */
  fill: number;
}

const META: Record<HealthBandKey, BandMeta> = {
  healthy: { label: "Healthy", color: "#22c55e", barClass: "bg-success", fill: 1 },
  hurt: { label: "Hurt", color: "#a3b81f", barClass: "bg-lime-500", fill: 0.7 },
  bloodied: { label: "Bloodied", color: "#c9a227", barClass: "bg-gold", fill: 0.45 },
  critical: { label: "Critical", color: "#ef4444", barClass: "bg-danger", fill: 0.2 },
  down: { label: "Down", color: "#6b7280", barClass: "bg-surface-light", fill: 0 },
};

const FALLBACK: BandMeta = META.healthy;

export function bandMeta(band: string | null | undefined): BandMeta {
  if (!band) return FALLBACK;
  return META[band.toLowerCase() as HealthBandKey] ?? FALLBACK;
}

/**
 * Exact-HP helpers for players/allies (whose real HP is known). Enemies use the coarse bands
 * above instead. Kept here as the single source of truth for the 50% / 25% threshold ladder that
 * was previously re-implemented in CharacterStatus, the lobby roster, and CombatTracker.
 */

/** HP fraction clamped to [0,1]. */
export function hpRatio(current: number, max: number): number {
  if (max <= 0) return 0;
  return Math.max(0, Math.min(1, current / max));
}

/** Bar fill colour for an exact-HP ratio: green > 50%, gold > 25%, red below. */
export function hpColor(ratio: number): string {
  if (ratio > 0.5) return "bg-success";
  if (ratio > 0.25) return "bg-gold";
  return "bg-danger";
}

/**
 * Same 50% / 25% threshold ladder as {@link hpColor}, but returns a raw CSS `var(--color-…)`
 * string instead of a Tailwind class — for SVG `stroke`/`fill` attributes (e.g. the battle-map
 * HP ring) where a utility class can't apply.
 */
export function hpColorVar(ratio: number): string {
  if (ratio > 0.5) return "var(--color-success)";
  if (ratio > 0.25) return "var(--color-gold)";
  return "var(--color-danger)";
}
