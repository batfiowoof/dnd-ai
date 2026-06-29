import type { TerrainType } from "@/types";

/** Logical cell size (px in the SVG user space). ≥44 keeps tap targets accessible. */
export const CELL = 56;
/** Portrait diameter inside a token (matches Portrait `sm` = 36px). */
export const PORTRAIT = 36;

export const TERRAIN_LABEL: Record<TerrainType, string> = {
  WALL: "Wall",
  DIFFICULT: "Difficult terrain",
  HAZARD: "Hazard",
};
