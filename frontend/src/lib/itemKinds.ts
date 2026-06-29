import type { ItemKind } from "@/types";

/** Selectable item kinds, in the order shown in the inventory "add item" picker. */
export const ITEM_KINDS: ItemKind[] = [
  "WEAPON",
  "ARMOR",
  "POTION",
  "POTION_HEALING",
  "SCROLL",
  "GEAR",
];

/** Human-readable label per item kind. */
export const KIND_LABELS: Record<ItemKind, string> = {
  WEAPON: "Weapon",
  ARMOR: "Armor",
  POTION: "Potion",
  POTION_HEALING: "Healing Potion",
  SCROLL: "Scroll",
  GEAR: "Gear",
};
