import type { RegionNode, TravelPace } from "@/types";

/**
 * Client-side mirror of the backend travel math (see `TravelService`) so the confirm modal can
 * preview a leg's duration before committing. The server remains authoritative — this is only a
 * preview, kept in sync with the same constants.
 */

/** Real-world miles per unit of normalized (0–100) map distance. */
const MILES_PER_UNIT = 1.2;
/** 5e miles covered per 8-hour travel day, by pace. */
const MILES_PER_DAY: Record<TravelPace, number> = { FAST: 30, NORMAL: 24, SLOW: 18 };
/** Hours of walking per travel day. */
const HOURS_PER_TRAVEL_DAY = 8;
/** The campaign clock starts at dawn (08:00) so "Day 1" doesn't read as midnight. */
const CLOCK_START_MINUTES = 8 * 60;

export const PACE_OPTIONS: { value: TravelPace; label: string }[] = [
  { value: "SLOW", label: "Slow" },
  { value: "NORMAL", label: "Normal" },
  { value: "FAST", label: "Fast" },
];

/** A one-line description of what each pace trades off, shown under the pace picker. */
export const PACE_BLURB: Record<TravelPace, string> = {
  SLOW: "Cautious and quiet — slower, but less likely to be ambushed.",
  NORMAL: "A steady march at the standard pace.",
  FAST: "Covers ground quickly, but you're easier to catch off guard.",
};

export interface TravelEstimate {
  miles: number;
  days: number;
  /** Human-readable duration, e.g. "about 2 days" or "about 6 hours". */
  durationText: string;
}

/** Straight-line distance between two nodes on the normalized 0–100 canvas. */
export function nodeDistance(a: RegionNode, b: RegionNode): number {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

/** Estimate a leg's distance and travel time at the given pace (mirrors the server). */
export function estimateTravel(
  from: RegionNode | null,
  to: RegionNode,
  pace: TravelPace
): TravelEstimate {
  const miles = from ? nodeDistance(from, to) * MILES_PER_UNIT : 0;
  const days = miles / MILES_PER_DAY[pace];
  return { miles, days, durationText: durationText(miles, days) };
}

function durationText(miles: number, days: number): string {
  if (miles <= 0) return "a short while";
  if (days >= 1) {
    const d = Math.max(1, Math.round(days));
    return `about ${d} ${d === 1 ? "day" : "days"}`;
  }
  const h = Math.max(1, Math.round(days * HOURS_PER_TRAVEL_DAY));
  return `about ${h} ${h === 1 ? "hour" : "hours"}`;
}

/** Format elapsed in-game minutes as "Day N • HH:MM", starting the campaign clock at dawn. */
export function formatGameClock(inGameMinutes: number): string {
  const total = Math.max(0, Math.floor(inGameMinutes)) + CLOCK_START_MINUTES;
  const day = Math.floor(total / 1440) + 1;
  const minutesOfDay = total % 1440;
  const hh = String(Math.floor(minutesOfDay / 60)).padStart(2, "0");
  const mm = String(minutesOfDay % 60).padStart(2, "0");
  return `Day ${day} · ${hh}:${mm}`;
}
