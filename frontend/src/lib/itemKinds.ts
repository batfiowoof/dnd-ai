import type { EquipSlot, InventoryItem, ItemKind, ItemSubtype } from "@/types";

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

/* ── Equipment slots (paper-doll) ─────────────────────────────── */

/** All eight slots, in the read order the picker/legend uses. */
export const EQUIP_SLOTS: EquipSlot[] = [
  "HEAD",
  "NECK",
  "CHEST",
  "HANDS",
  "MAIN_HAND",
  "OFF_HAND",
  "FEET",
  "RING",
];

/** Full slot label. */
export const SLOT_LABELS: Record<EquipSlot, string> = {
  HEAD: "Head",
  NECK: "Neck",
  CHEST: "Chest",
  HANDS: "Hands",
  MAIN_HAND: "Main Hand",
  OFF_HAND: "Off Hand",
  FEET: "Feet",
  RING: "Ring",
};

/** Compact mono glyph shown inside an empty slot (kept as text — no emoji icons). */
export const SLOT_SHORT: Record<EquipSlot, string> = {
  HEAD: "HEAD",
  NECK: "NECK",
  CHEST: "CHEST",
  HANDS: "HANDS",
  MAIN_HAND: "MAIN",
  OFF_HAND: "OFF",
  FEET: "FEET",
  RING: "RING",
};

/** Selectable subtypes for the "add item" picker (OTHER = backpack-only). */
export const ITEM_SUBTYPES: ItemSubtype[] = [
  "WEAPON",
  "SHIELD",
  "BODY_ARMOR",
  "HELMET",
  "GLOVES",
  "BOOTS",
  "AMULET",
  "RING",
  "OTHER",
];

/** Human-readable label per subtype. */
export const SUBTYPE_LABELS: Record<ItemSubtype, string> = {
  WEAPON: "Weapon",
  SHIELD: "Shield",
  BODY_ARMOR: "Body Armor",
  HELMET: "Helmet",
  GLOVES: "Gloves",
  BOOTS: "Boots",
  AMULET: "Amulet",
  RING: "Ring",
  OTHER: "Other (no slot)",
};

/** Which slots each subtype may occupy. Mirrors the backend ItemSubtype enum. */
const SUBTYPE_SLOTS: Record<ItemSubtype, EquipSlot[]> = {
  HELMET: ["HEAD"],
  AMULET: ["NECK"],
  BODY_ARMOR: ["CHEST"],
  GLOVES: ["HANDS"],
  WEAPON: ["MAIN_HAND", "OFF_HAND"],
  SHIELD: ["OFF_HAND"],
  BOOTS: ["FEET"],
  RING: ["RING"],
  OTHER: [],
};

/** Fallback subtype for items without an explicit one. Mirrors ItemSubtype.fromKind. */
export function subtypeFromKind(kind: ItemKind): ItemSubtype {
  if (kind === "WEAPON") return "WEAPON";
  if (kind === "ARMOR") return "BODY_ARMOR";
  return "OTHER";
}

/** The slots an item may be equipped into — from its subtype, falling back to kind. */
export function allowedSlotsFor(item: InventoryItem): EquipSlot[] {
  const subtype = item.subtype ?? subtypeFromKind(item.kind);
  return SUBTYPE_SLOTS[subtype];
}

/** Whether an item can be equipped anywhere at all. */
export function isEquippable(item: InventoryItem): boolean {
  return allowedSlotsFor(item).length > 0;
}
