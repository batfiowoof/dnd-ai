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
