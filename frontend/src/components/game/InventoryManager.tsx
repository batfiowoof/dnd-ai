"use client";

import { useMemo, useState } from "react";
import type {
  EquipSlot,
  InventoryItem,
  ItemKind,
  ItemSubtype,
  MagicItemSummary,
  PlayerRuntimeState,
} from "@/types";
import { Modal, Button, cn } from "@/components/ui";
import {
  ITEM_KINDS,
  KIND_LABELS,
  ITEM_SUBTYPES,
  SUBTYPE_LABELS,
  SLOT_LABELS,
  SLOT_SHORT,
  allowedSlotsFor,
  subtypeFromKind,
} from "@/lib/itemKinds";
import { RARITY_BADGE, RARITY_LABELS, magicFor } from "@/lib/magicItems";

/** The SRD attunement cap (mirrors backend MAX_ATTUNEMENTS). */
const MAX_ATTUNEMENTS = 3;

/** A small rarity chip — colour is always paired with the rarity word for accessibility. */
function RarityChip({ magic }: { magic: MagicItemSummary }) {
  return (
    <span
      className={cn(
        "shrink-0 rounded border px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wider",
        RARITY_BADGE[magic.rarity]
      )}
    >
      {RARITY_LABELS[magic.rarity]}
      {magic.requiresAttunement ? " · Attune" : ""}
    </span>
  );
}

interface InventoryManagerProps {
  open: boolean;
  onClose: () => void;
  state: PlayerRuntimeState | null;
  connected: boolean;
  onDrop: (name: string) => void;
  onEquip: (name: string, slot: EquipSlot | null) => void;
  onAdd: (item: {
    name: string;
    qty: number;
    kind: ItemKind;
    subtype?: ItemSubtype | null;
  }) => void;
  /** Magic-item catalog indexed by lowercased name (see lib/magicItems); empty until fetched. */
  magicByName?: Record<string, MagicItemSummary>;
  onAttune: (name: string) => void;
  onEndAttunement: (name: string) => void;
}

/**
 * Body-silhouette placement of the eight slots (null cells are spacers). Read top→bottom:
 * head, weapons flanking the neck, chest, a ring by the hands, feet.
 */
const SLOT_LAYOUT: (EquipSlot | null)[][] = [
  [null, "HEAD", null],
  ["MAIN_HAND", "NECK", "OFF_HAND"],
  [null, "CHEST", null],
  ["RING", "HANDS", null],
  [null, "FEET", null],
];

/**
 * In-session inventory: a paper-doll of equip slots plus a backpack of loose items. Items are
 * dragged from the backpack onto a legal slot (or tap-to-select then tap a slot); a slot holds
 * one item, and equipping into an occupied slot returns the previous item to the backpack. All
 * mutations are emitted to the backend, which broadcasts the updated state.
 */
export default function InventoryManager({
  open,
  onClose,
  state,
  connected,
  onDrop,
  onEquip,
  onAdd,
  magicByName = {},
  onAttune,
  onEndAttunement,
}: InventoryManagerProps) {
  const [newName, setNewName] = useState("");
  const [newQty, setNewQty] = useState(1);
  const [newKind, setNewKind] = useState<ItemKind>("GEAR");
  const [newSubtype, setNewSubtype] = useState<ItemSubtype>("OTHER");

  // The backpack item currently picked for tap-to-equip (name), and the in-flight drag.
  const [selected, setSelected] = useState<string | null>(null);
  const [dragName, setDragName] = useState<string | null>(null);
  const [overSlot, setOverSlot] = useState<EquipSlot | null>(null);

  const items = useMemo(() => state?.inventory ?? [], [state]);
  const bySlot = useMemo(() => {
    const m = new Map<EquipSlot, InventoryItem>();
    for (const it of items) if (it.slot) m.set(it.slot, it);
    return m;
  }, [items]);
  const backpack = useMemo(() => items.filter((it) => !it.slot), [items]);

  // Attunement: the set currently attuned, plus every held item that requires attunement.
  const attuned = useMemo(
    () => new Set((state?.attunedItems ?? []).map((n) => n.toLowerCase())),
    [state]
  );
  const magic = (name: string) => magicFor(name, magicByName);
  const attunable = useMemo(
    () => items.filter((it) => magic(it.name)?.requiresAttunement),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [items, magicByName]
  );
  const atCap = attuned.size >= MAX_ATTUNEMENTS;

  const findItem = (name: string | null) =>
    name ? items.find((it) => it.name === name) ?? null : null;

  // Item driving target highlighting: whatever is being dragged, else the tapped selection.
  const activeItem = findItem(dragName ?? selected);
  const activeSlots = activeItem ? allowedSlotsFor(activeItem) : [];

  function handleAdd() {
    const name = newName.trim();
    if (!name || !connected) return;
    onAdd({ name, qty: Math.max(1, newQty), kind: newKind, subtype: newSubtype });
    setNewName("");
    setNewQty(1);
    setNewKind("GEAR");
    setNewSubtype("OTHER");
  }

  function handleKindChange(kind: ItemKind) {
    setNewKind(kind);
    setNewSubtype(subtypeFromKind(kind));
  }

  function equipInto(slot: EquipSlot) {
    const item = findItem(selected);
    if (!connected || !item || !allowedSlotsFor(item).includes(slot)) return;
    onEquip(item.name, slot);
    setSelected(null);
  }

  function handleSlotClick(slot: EquipSlot) {
    if (!connected) return;
    if (selected) {
      equipInto(slot);
      return;
    }
    const occupant = bySlot.get(slot);
    if (occupant) onEquip(occupant.name, null); // tap a filled slot with nothing held → unequip
  }

  function handleSlotDrop(e: React.DragEvent, slot: EquipSlot) {
    e.preventDefault();
    const name = e.dataTransfer.getData("text/plain") || dragName;
    const item = findItem(name);
    if (connected && item && allowedSlotsFor(item).includes(slot)) {
      onEquip(item.name, slot);
    }
    setDragName(null);
    setOverSlot(null);
  }

  return (
    <Modal open={open} onClose={onClose} title="Equipment & Inventory" size="lg">
      <div className="space-y-4">
        <p className="text-xs text-text-muted">
          Drag an item onto a slot, or tap an item then a slot. Tap a filled slot to unequip.
        </p>

        <div className="grid gap-4 sm:grid-cols-2">
          {/* ── Paper-doll ─────────────────────────────── */}
          <div
            className="rounded-xl border border-border bg-bg-elevated/60 p-3"
            aria-label="Equipment slots"
          >
            <div className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-text-muted">
              Equipped
            </div>
            <div className="mx-auto grid max-w-[18rem] grid-cols-3 gap-2">
              {SLOT_LAYOUT.flatMap((row, r) =>
                row.map((slot, c) => {
                  if (!slot) return <div key={`${r}-${c}`} aria-hidden />;
                  const occupant = bySlot.get(slot);
                  const targeting = !!activeItem;
                  const valid = activeSlots.includes(slot);
                  return (
                    <button
                      key={slot}
                      type="button"
                      disabled={!connected}
                      onClick={() => handleSlotClick(slot)}
                      onDragOver={(e) => {
                        if (activeItem && valid) {
                          e.preventDefault();
                          setOverSlot(slot);
                        }
                      }}
                      onDragLeave={() => setOverSlot((s) => (s === slot ? null : s))}
                      onDrop={(e) => handleSlotDrop(e, slot)}
                      draggable={!!occupant && connected}
                      onDragStart={(e) => {
                        if (occupant) {
                          e.dataTransfer.setData("text/plain", occupant.name);
                          setDragName(occupant.name);
                        }
                      }}
                      onDragEnd={() => {
                        setDragName(null);
                        setOverSlot(null);
                      }}
                      aria-label={
                        occupant
                          ? `${SLOT_LABELS[slot]}: ${occupant.name} (tap to unequip)`
                          : `${SLOT_LABELS[slot]} slot, empty`
                      }
                      className={cn(
                        "flex min-h-[68px] flex-col items-center justify-center gap-0.5 rounded-lg border px-2 py-2 text-center transition disabled:cursor-not-allowed",
                        occupant
                          ? "border-accent/50 bg-accent-dark/20 text-text hover:border-accent"
                          : "border-dashed border-border text-text-muted",
                        targeting && valid && "border-accent ring-2 ring-accent/60",
                        overSlot === slot && valid && "bg-accent/20",
                        targeting && !valid && "opacity-40"
                      )}
                    >
                      {occupant ? (
                        <>
                          <span className="line-clamp-2 text-xs font-medium leading-tight">
                            {occupant.name}
                          </span>
                          <span className="font-mono text-[9px] uppercase tracking-wider text-accent-light">
                            {SLOT_LABELS[slot]}
                          </span>
                        </>
                      ) : (
                        <span className="font-mono text-[10px] uppercase tracking-wider">
                          {SLOT_SHORT[slot]}
                        </span>
                      )}
                    </button>
                  );
                })
              )}
            </div>
          </div>

          {/* ── Backpack ───────────────────────────────── */}
          <div className="rounded-xl border border-border bg-bg-elevated/60 p-3">
            <div className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-text-muted">
              Backpack
            </div>
            {backpack.length === 0 ? (
              <p className="py-6 text-center text-sm text-text-muted">
                Nothing loose in your pack.
              </p>
            ) : (
              <ul className="max-h-[16rem] space-y-1.5 overflow-y-auto pr-0.5">
                {backpack.map((item, i) => {
                  const equippable = allowedSlotsFor(item).length > 0;
                  const isSelected = selected === item.name;
                  return (
                    <li
                      key={`${item.name}-${item.kind}-${i}`}
                      draggable={equippable && connected}
                      onDragStart={(e) => {
                        e.dataTransfer.setData("text/plain", item.name);
                        setDragName(item.name);
                      }}
                      onDragEnd={() => {
                        setDragName(null);
                        setOverSlot(null);
                      }}
                      className={cn(
                        "flex items-center gap-2 rounded-lg border bg-bg px-3 py-2 transition",
                        isSelected ? "border-accent ring-1 ring-accent/60" : "border-border",
                        equippable && connected && "cursor-grab"
                      )}
                    >
                      <button
                        type="button"
                        disabled={!connected || !equippable}
                        onClick={() =>
                          setSelected((s) => (s === item.name ? null : item.name))
                        }
                        className="min-w-0 flex-1 text-left disabled:cursor-default"
                        aria-pressed={isSelected}
                        aria-label={
                          equippable
                            ? `Select ${item.name} to equip`
                            : `${item.name} (not equippable)`
                        }
                      >
                        <div className="flex items-center gap-2">
                          <span className="truncate text-sm text-text">{item.name}</span>
                          {item.qty > 1 && (
                            <span className="tabular text-xs text-text-muted">×{item.qty}</span>
                          )}
                          {magic(item.name) && <RarityChip magic={magic(item.name)!} />}
                        </div>
                        <span className="text-[10px] uppercase tracking-wider text-text-muted">
                          {item.subtype && item.subtype !== "OTHER"
                            ? SUBTYPE_LABELS[item.subtype]
                            : KIND_LABELS[item.kind]}
                        </span>
                      </button>

                      <button
                        type="button"
                        disabled={!connected}
                        onClick={() => onDrop(item.name)}
                        className="rounded-md border border-border px-2 py-1 text-xs font-semibold text-text-muted transition hover:border-danger hover:text-danger disabled:opacity-40"
                      >
                        Drop
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>

        {/* ── Attunement ───────────────────────────────── */}
        {attunable.length > 0 && (
          <div className="border-t border-border pt-4">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-xs font-semibold uppercase tracking-wider text-text-muted">
                Attunement
              </span>
              <span
                className={cn(
                  "tabular rounded border px-1.5 py-0.5 text-[10px] font-semibold",
                  atCap ? "border-gold/60 text-gold" : "border-border text-text-muted"
                )}
                aria-label={`${attuned.size} of ${MAX_ATTUNEMENTS} attunement slots used`}
              >
                {attuned.size} / {MAX_ATTUNEMENTS} attuned
              </span>
            </div>
            <ul className="space-y-1.5">
              {attunable.map((item, i) => {
                const m = magic(item.name)!;
                const isAttuned = attuned.has(item.name.toLowerCase());
                const blocked = !isAttuned && atCap;
                const reason = blocked
                  ? `Attuned to ${MAX_ATTUNEMENTS} items already — end another attunement first.`
                  : undefined;
                return (
                  <li
                    key={`attune-${item.name}-${i}`}
                    className="flex items-center gap-2 rounded-lg border border-border bg-bg px-3 py-2"
                  >
                    <span className="min-w-0 flex-1 truncate text-sm text-text">{item.name}</span>
                    <RarityChip magic={m} />
                    <Button
                      size="sm"
                      variant={isAttuned ? "outline" : "primary"}
                      disabled={!connected || blocked}
                      title={reason}
                      onClick={() =>
                        isAttuned ? onEndAttunement(item.name) : onAttune(item.name)
                      }
                    >
                      {isAttuned ? "End Attunement" : "Attune"}
                    </Button>
                  </li>
                );
              })}
            </ul>
            {atCap && (
              <p className="mt-1.5 text-[11px] text-text-muted">
                You&rsquo;re attuned to the maximum of {MAX_ATTUNEMENTS} items. End an attunement to
                free a slot.
              </p>
            )}
          </div>
        )}

        {/* ── Add item ─────────────────────────────────── */}
        <div className="border-t border-border pt-4">
          <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
            Add Item
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <input
              type="text"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder="Item name"
              className="min-w-0 flex-1 rounded-lg border border-border bg-bg-elevated px-3 py-2 text-sm text-text placeholder-text-muted outline-none focus:border-accent focus:ring-1 focus:ring-accent"
            />
            <input
              type="number"
              min={1}
              value={newQty}
              onChange={(e) => setNewQty(Number(e.target.value))}
              aria-label="Quantity"
              className="w-16 rounded-lg border border-border bg-bg-elevated px-2 py-2 text-center text-sm text-text outline-none focus:border-accent"
            />
            <select
              value={newKind}
              onChange={(e) => handleKindChange(e.target.value as ItemKind)}
              aria-label="Item kind"
              className="rounded-lg border border-border bg-bg-elevated px-2 py-2 text-sm text-text"
            >
              {ITEM_KINDS.map((k) => (
                <option key={k} value={k}>
                  {KIND_LABELS[k]}
                </option>
              ))}
            </select>
            <select
              value={newSubtype}
              onChange={(e) => setNewSubtype(e.target.value as ItemSubtype)}
              aria-label="Equip slot type"
              className="rounded-lg border border-border bg-bg-elevated px-2 py-2 text-sm text-text"
            >
              {ITEM_SUBTYPES.map((st) => (
                <option key={st} value={st}>
                  {SUBTYPE_LABELS[st]}
                </option>
              ))}
            </select>
            <Button onClick={handleAdd} disabled={!newName.trim() || !connected} size="sm">
              Add
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
