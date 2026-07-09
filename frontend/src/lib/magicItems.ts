import type { MagicItemRarity, MagicItemSummary } from "@/types";

/**
 * Client-side helpers for surfacing magic items in the inventory UI. The catalog metadata is
 * fetched once from `/api/combat/magic-items` (see `api.getMagicItems`) and indexed by lowercased
 * name; each inventory item is looked up by name to badge it with rarity/attunement. Mirrors the
 * backend `MagicItemCatalog` name-resolution (exact match, plus a light `+N` synthetic fallback).
 */

/** Human-readable rarity label — always rendered alongside the color chip so meaning never relies on color alone. */
export const RARITY_LABELS: Record<MagicItemRarity, string> = {
  COMMON: "Common",
  UNCOMMON: "Uncommon",
  RARE: "Rare",
  VERY_RARE: "Very Rare",
  LEGENDARY: "Legendary",
  ARTIFACT: "Artifact",
  VARIES: "Rarity Varies",
  UNKNOWN: "Magic",
};

/**
 * Rarity → border/text colour classes (conventional 5e rarity colours). ALWAYS paired with the
 * text label above — the colour is decorative reinforcement, never the sole signal (a11y).
 */
export const RARITY_BADGE: Record<MagicItemRarity, string> = {
  COMMON: "border-zinc-400/50 text-zinc-300",
  UNCOMMON: "border-emerald-500/50 text-emerald-300",
  RARE: "border-sky-500/50 text-sky-300",
  VERY_RARE: "border-fuchsia-500/50 text-fuchsia-300",
  LEGENDARY: "border-amber-500/60 text-amber-300",
  ARTIFACT: "border-rose-500/60 text-rose-300",
  VARIES: "border-zinc-400/50 text-zinc-300",
  UNKNOWN: "border-accent/50 text-accent-light",
};

/** Build a name→summary index (lowercased keys) from the catalog list. */
export function buildMagicIndex(items: MagicItemSummary[]): Record<string, MagicItemSummary> {
  const idx: Record<string, MagicItemSummary> = {};
  for (const it of items) idx[it.name.toLowerCase()] = it;
  return idx;
}

/**
 * The magic-item summary for an inventory item name, or null when it isn't a magic item. Exact
 * name match first; falls back to treating a `+1/+2/+3`-named item as a (non-attunement) magic
 * item, mirroring the backend's `+N` name synthesis so common loot is badged without a catalog row.
 */
export function magicFor(
  name: string,
  index: Record<string, MagicItemSummary>
): MagicItemSummary | null {
  const exact = index[name.toLowerCase()];
  if (exact) return exact;
  if (/\+\s*[123]\b/.test(name)) {
    return {
      key: name,
      name,
      itemType: "",
      slot: null,
      rarity: "UNKNOWN",
      requiresAttunement: false,
      hasEffect: true,
    };
  }
  return null;
}
