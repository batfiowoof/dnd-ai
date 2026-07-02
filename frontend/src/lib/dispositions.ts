/**
 * Client mirror of the backend `DispositionBand` enum: how an NPC feels about the party, bucketed
 * from a signed score in [-100, 100]. Used by the world-builder attitude picker and the in-game
 * relationship panel. Kept in sync with the server (which is authoritative).
 */

export type DispositionBandKey =
  | "HOSTILE"
  | "UNFRIENDLY"
  | "NEUTRAL"
  | "FRIENDLY"
  | "DEVOTED";

export interface DispositionBand {
  key: DispositionBandKey;
  label: string;
  /** Inclusive score bounds. */
  min: number;
  max: number;
  /** Representative score, used when the picker sets a band. */
  center: number;
  /** A small glyph so attitude never relies on colour alone. */
  icon: string;
  /** Theme accent for the band chip. */
  accent: string;
}

export const MIN_SCORE = -100;
export const MAX_SCORE = 100;

/** Ordered coldest → warmest (drives the SegmentedControl and legend order). */
export const BANDS: DispositionBand[] = [
  { key: "HOSTILE", label: "Hostile", min: -100, max: -45, center: -70, icon: "✖", accent: "var(--color-accent)" },
  { key: "UNFRIENDLY", label: "Unfriendly", min: -44, max: -15, center: -30, icon: "▾", accent: "#c2703a" },
  { key: "NEUTRAL", label: "Neutral", min: -14, max: 14, center: 0, icon: "•", accent: "var(--color-text-muted)" },
  { key: "FRIENDLY", label: "Friendly", min: 15, max: 44, center: 30, icon: "♦", accent: "#3f9d6b" },
  { key: "DEVOTED", label: "Devoted", min: 45, max: 100, center: 70, icon: "♥", accent: "var(--color-gold)" },
];

export function clampScore(score: number): number {
  return Math.max(MIN_SCORE, Math.min(MAX_SCORE, Math.round(score)));
}

/** The band a disposition score falls into. */
export function bandFromScore(score: number | null | undefined): DispositionBand {
  const s = clampScore(score ?? 0);
  return BANDS.find((b) => s >= b.min && s <= b.max) ?? BANDS[2];
}

/** The representative score for a band key (used when the attitude picker changes). */
export function centerOfBand(key: DispositionBandKey): number {
  return BANDS.find((b) => b.key === key)?.center ?? 0;
}
