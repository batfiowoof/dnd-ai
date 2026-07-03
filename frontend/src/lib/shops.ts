import type { ShopType } from "@/types";

/** Human-readable label per shop type (shared by the authoring step and the in-game panel). */
export const SHOP_TYPE_LABELS: Record<ShopType, string> = {
  GENERAL: "General Store",
  BLACKSMITH: "Blacksmith",
  ALCHEMIST: "Alchemist",
  ARCANE: "Arcane Shop",
  TRADER: "Trader",
  TEMPLE: "Temple",
};
