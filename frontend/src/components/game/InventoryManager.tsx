"use client";

import { useState } from "react";
import type { ItemKind, PlayerRuntimeState } from "@/types";
import { Modal, Button, cn } from "@/components/ui";

interface InventoryManagerProps {
  open: boolean;
  onClose: () => void;
  state: PlayerRuntimeState | null;
  connected: boolean;
  onDrop: (name: string) => void;
  onEquip: (name: string, equipped: boolean) => void;
  onAdd: (item: { name: string; qty: number; kind: ItemKind }) => void;
}

const ITEM_KINDS: ItemKind[] = [
  "WEAPON",
  "ARMOR",
  "POTION",
  "POTION_HEALING",
  "SCROLL",
  "GEAR",
];

const KIND_LABELS: Record<ItemKind, string> = {
  WEAPON: "Weapon",
  ARMOR: "Armor",
  POTION: "Potion",
  POTION_HEALING: "Healing Potion",
  SCROLL: "Scroll",
  GEAR: "Gear",
};

/**
 * In-session inventory editor: drop or equip existing items and add new ones.
 * All mutations are emitted to the backend, which broadcasts the updated state.
 */
export default function InventoryManager({
  open,
  onClose,
  state,
  connected,
  onDrop,
  onEquip,
  onAdd,
}: InventoryManagerProps) {
  const [newName, setNewName] = useState("");
  const [newQty, setNewQty] = useState(1);
  const [newKind, setNewKind] = useState<ItemKind>("GEAR");

  const items = state?.inventory ?? [];

  function handleAdd() {
    const name = newName.trim();
    if (!name || !connected) return;
    onAdd({ name, qty: Math.max(1, newQty), kind: newKind });
    setNewName("");
    setNewQty(1);
    setNewKind("GEAR");
  }

  return (
    <Modal open={open} onClose={onClose} title="Manage Inventory" size="md">
      <div className="space-y-4">
        {/* Item list */}
        {items.length === 0 ? (
          <p className="text-sm text-text-muted">Your inventory is empty.</p>
        ) : (
          <ul className="space-y-1.5">
            {items.map((item) => {
              const equippable = item.kind === "WEAPON" || item.kind === "ARMOR";
              return (
                <li
                  key={`${item.name}-${item.kind}`}
                  className="flex items-center gap-2 rounded-lg border border-border bg-bg-elevated px-3 py-2"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate text-sm text-text">
                        {item.name}
                      </span>
                      {item.qty > 1 && (
                        <span className="tabular text-xs text-text-muted">
                          ×{item.qty}
                        </span>
                      )}
                      {item.equipped && (
                        <span className="rounded bg-accent-dark/30 px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-accent-light">
                          Equipped
                        </span>
                      )}
                    </div>
                    <span className="text-[10px] uppercase tracking-wider text-text-muted">
                      {KIND_LABELS[item.kind]}
                    </span>
                  </div>

                  {equippable && (
                    <button
                      type="button"
                      disabled={!connected}
                      onClick={() => onEquip(item.name, !item.equipped)}
                      className={cn(
                        "rounded-md border px-2 py-1 text-xs font-semibold transition disabled:opacity-40",
                        item.equipped
                          ? "border-accent/60 text-accent hover:bg-accent hover:text-white"
                          : "border-border text-text-muted hover:border-accent/60 hover:text-accent"
                      )}
                    >
                      {item.equipped ? "Unequip" : "Equip"}
                    </button>
                  )}

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

        {/* Add item */}
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
              onChange={(e) => setNewKind(e.target.value as ItemKind)}
              aria-label="Item kind"
              className="rounded-lg border border-border bg-bg-elevated px-2 py-2 text-sm text-text"
            >
              {ITEM_KINDS.map((k) => (
                <option key={k} value={k}>
                  {KIND_LABELS[k]}
                </option>
              ))}
            </select>
            <Button
              onClick={handleAdd}
              disabled={!newName.trim() || !connected}
              size="sm"
            >
              Add
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
