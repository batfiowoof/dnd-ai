"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useWorldDraftStore } from "@/store/worldDraftStore";
import { Field, controlClass, useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateShops } from "@/hooks/useWorldQueries";
import { draftToContext, emptyShop, emptyStockEntry } from "@/lib/worldBuilder";
import { formatCoins, parseCostToCopper } from "@/lib/money";
import { queryKeys } from "@/lib/queryKeys";
import { listEquipment, kindFromCategory, type SrdEquipment } from "@/lib/dnd5eapi";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import {
  LabeledInput,
  LabeledTextarea,
  LabeledSelect,
  LabeledNumber,
} from "@/components/world/shared/WorldField";
import { ITEM_KINDS, KIND_LABELS } from "@/lib/itemKinds";
import { SHOP_TYPE_LABELS } from "@/lib/shops";
import type { ItemKind, ShopType } from "@/types";

const NONE = "— None —";
const ANYWHERE = "— Anywhere in region —";

const SHOP_TYPE_ORDER: ShopType[] = [
  "GENERAL",
  "BLACKSMITH",
  "ALCHEMIST",
  "ARCANE",
  "TRADER",
  "TEMPLE",
];
const SHOP_TYPE_OPTIONS = SHOP_TYPE_ORDER.map((t) => SHOP_TYPE_LABELS[t]);
const KIND_OPTIONS = ITEM_KINDS.map((k) => KIND_LABELS[k]);

const EQUIPMENT_DATALIST_ID = "srd-equipment-list";

/**
 * Step 8 — shops: location-gated merchants the party buys from and sells to. Each is anchored to a
 * region/subregion (so it's only reachable there) and priced by an economy factor, making the same
 * goods cost more in a wealthy town and less in a poor one. Stock lines can be picked from the SRD
 * equipment catalog (prefilling kind + price) or hand-authored.
 */
export default function ShopsStep() {
  const draft = useWorldDraftStore();
  const { shops, regions, npcs, setField } = draft;
  const toast = useToast();
  const generate = useGenerateShops();

  // SRD equipment catalog, for the stock autocomplete + price/kind prefill. Public endpoint; cached.
  const { data: equipment } = useQuery({
    queryKey: queryKeys.dnd5e.equipmentList,
    queryFn: listEquipment,
    staleTime: Infinity,
  });
  const equipmentByName = useMemo(() => {
    const map = new Map<string, SrdEquipment>();
    for (const e of equipment ?? []) map.set(e.name.trim().toLowerCase(), e);
    return map;
  }, [equipment]);

  async function handleGenerate() {
    try {
      const items = await generate.mutateAsync(draftToContext(draft));
      setField({ shops: [...shops, ...items] });
      toast.success(`Added ${items.length} shops`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate shops"));
    }
  }

  return (
    <div>
      <SectionIntro title="Shops">
        Shops are where the party spends its gold. Each is tied to a{" "}
        <strong className="text-text">place</strong> — a shop is only open when the party is standing
        there — and carries an <strong className="text-text">economy factor</strong> so the same goods
        cost more in a wealthy city and less in a poor one. Players{" "}
        <strong className="text-text">buy and sell</strong> from its stock during play.
      </SectionIntro>

      {/* Shared autocomplete source for every stock row's item field. */}
      <datalist id={EQUIPMENT_DATALIST_ID}>
        {(equipment ?? []).map((e) => (
          <option key={e.index} value={e.name} />
        ))}
      </datalist>

      <RepeatableSection
        noun="shops"
        items={shops}
        onChange={(shops) => setField({ shops })}
        makeEmpty={emptyShop}
        aiSlot={<AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />}
        titleOf={(s) => s.name}
        renderItem={(shop, update) => {
          const region = regions.find((r) => r.name === shop.region);
          const subOptions = region?.subregions ?? [];
          return (
            <div className="space-y-4">
              {/* Identity */}
              <div className="grid gap-3 sm:grid-cols-[1fr_11rem]">
                <LabeledInput
                  label="Name"
                  placeholder="e.g. The Rusty Anvil"
                  value={shop.name}
                  onChange={(v) => update({ name: v })}
                />
                <LabeledSelect
                  label="Type"
                  value={SHOP_TYPE_LABELS[shop.type]}
                  options={SHOP_TYPE_OPTIONS}
                  onChange={(label) => update({ type: shopTypeForLabel(label) })}
                />
              </div>
              <LabeledTextarea
                label="Description"
                rows={2}
                placeholder="e.g. A soot-stained forge on the docks; the hammer never stops ringing."
                value={shop.description}
                onChange={(v) => update({ description: v })}
              />

              {/* Location — the gate: the shop only opens where it sits. */}
              <div className="grid gap-3 sm:grid-cols-2">
                <LabeledSelect
                  label="Region"
                  hint="Where this shop sits. It's only reachable while the party is here."
                  value={shop.region || NONE}
                  options={[NONE, ...regions.map((r) => r.name || "(unnamed region)")]}
                  // Changing region invalidates the old subregion — clear it.
                  onChange={(v) => update({ region: v === NONE ? "" : v, subregion: "" })}
                />
                <LabeledSelect
                  label="Subregion"
                  hint={
                    shop.region && subOptions.length === 0
                      ? "This region has no subregions — the shop opens anywhere in it."
                      : "Pin the shop to one spot, or leave it open across the whole region."
                  }
                  value={shop.subregion || ANYWHERE}
                  options={[ANYWHERE, ...subOptions.map((s) => s.name || "(unnamed subregion)")]}
                  onChange={(v) => update({ subregion: v === ANYWHERE ? "" : v })}
                />
              </div>

              {/* Economy + owner */}
              <div className="grid gap-3 sm:grid-cols-2">
                <Field
                  label="Economy"
                  hint="Scales every price. Buy = list × factor; sell = half of that."
                >
                  <div className="flex items-center gap-3">
                    <input
                      type="range"
                      min={0.8}
                      max={1.3}
                      step={0.05}
                      value={shop.economyFactor}
                      onChange={(e) => update({ economyFactor: Number(e.target.value) })}
                      aria-label="Economy factor"
                      className="h-2 flex-1 cursor-pointer accent-[var(--color-accent)]"
                    />
                    <span className="w-24 flex-shrink-0 text-right text-sm tabular-nums text-gold">
                      {economyLabel(shop.economyFactor)}
                    </span>
                  </div>
                </Field>
                {npcs.length > 0 ? (
                  <LabeledSelect
                    label="Owner (NPC)"
                    value={shop.ownerNpcName || NONE}
                    options={[NONE, ...npcs.map((n) => n.name)]}
                    onChange={(v) => update({ ownerNpcName: v === NONE ? "" : v })}
                  />
                ) : (
                  <LabeledInput
                    label="Owner (NPC)"
                    placeholder="Name of the shopkeeper (optional)"
                    value={shop.ownerNpcName}
                    onChange={(v) => update({ ownerNpcName: v })}
                  />
                )}
              </div>

              {/* Stock */}
              <div className="rounded-lg border border-border/70 bg-bg-elevated/40 p-3">
                <div className="mb-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-accent">
                    Stock
                  </span>
                  <p className="mt-0.5 text-xs text-text-muted">
                    Start typing to pick a standard item (fills its kind and 5e price), or write your
                    own. Prices are the base list value — the economy factor above adjusts them.
                  </p>
                </div>
                <RepeatableSection
                  noun="items"
                  items={shop.stock}
                  onChange={(stock) => update({ stock })}
                  makeEmpty={emptyStockEntry}
                  titleOf={(it) => it.name}
                  renderItem={(item, updateItem) => (
                    <div className="grid gap-3 sm:grid-cols-[1fr_9rem_9rem_6rem]">
                      <Field label="Item">
                        <input
                          type="text"
                          list={EQUIPMENT_DATALIST_ID}
                          value={item.name}
                          placeholder="e.g. Longsword"
                          onChange={(e) => {
                            const v = e.target.value;
                            const hit = equipmentByName.get(v.trim().toLowerCase());
                            if (hit) {
                              updateItem({
                                name: hit.name,
                                srdIndex: hit.index,
                                kind: kindFromCategory(hit.category),
                                basePriceCopper: parseCostToCopper(hit.cost),
                              });
                            } else {
                              updateItem({ name: v, srdIndex: null });
                            }
                          }}
                          className={controlClass}
                        />
                      </Field>
                      <LabeledSelect
                        label="Kind"
                        value={KIND_LABELS[item.kind]}
                        options={KIND_OPTIONS}
                        onChange={(label) => updateItem({ kind: kindForLabel(label) })}
                      />
                      <LabeledNumber
                        label="Price (cp)"
                        hint={`= ${formatCoins(item.basePriceCopper)}`}
                        min={0}
                        value={item.basePriceCopper}
                        onChange={(v) => updateItem({ basePriceCopper: Math.max(0, v ?? 0) })}
                      />
                      <LabeledNumber
                        label="Qty"
                        hint="−1 = ∞"
                        min={-1}
                        value={item.quantity}
                        onChange={(v) => updateItem({ quantity: v ?? -1 })}
                      />
                    </div>
                  )}
                />
              </div>
            </div>
          );
        }}
      />
    </div>
  );
}

/** A short "1.20× · Wealthy" descriptor for the economy slider. */
function economyLabel(factor: number): string {
  const word =
    factor >= 1.2 ? "Wealthy" : factor >= 1.05 ? "Prosperous" : factor <= 0.85 ? "Poor" : factor < 0.95 ? "Lean" : "Fair";
  return `${factor.toFixed(2)}× · ${word}`;
}

/** Resolve an item-kind label back to its enum code (defaults to GEAR). */
function kindForLabel(label: string): ItemKind {
  return ITEM_KINDS.find((k) => KIND_LABELS[k] === label) ?? "GEAR";
}

/** Resolve a shop-type label back to its enum code (defaults to GENERAL). */
function shopTypeForLabel(label: string): ShopType {
  return SHOP_TYPE_ORDER.find((t) => SHOP_TYPE_LABELS[t] === label) ?? "GENERAL";
}
