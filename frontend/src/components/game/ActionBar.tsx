"use client";

import { useState } from "react";
import type { PlayerRuntimeState, SpellSummary } from "@/types";
import { cn } from "@/components/ui";

interface ActionBarProps {
  state: PlayerRuntimeState | null;
  isMyTurn: boolean;
  connected: boolean;
  onAttack: () => void;
  onCast: (spellLevel: number, spellName?: string, ritual?: boolean) => void;
  onUseItem: (itemName: string) => void;
  /** Spell catalog (level + ritual flags) — drives the ritual cast section. */
  spellCatalog?: SpellSummary[];
  /** Open the spell-preparation dialog — only when the class prepares (preparedMax > 0). */
  onPrepare?: () => void;
  /** Open the shop panel — only provided when a shop is reachable at the party's current location. */
  onShop?: () => void;
  /** Management actions — available regardless of whose turn it is. */
  onLongRest?: () => void;
  onShortRest?: (hitDice: number) => void;
  onManage?: () => void;
}

/**
 * Mechanical action menu shown on the player's turn (coexists with the free-text
 * chat). Buttons emit intents; the backend resolves rolls and resource changes.
 */
export default function ActionBar({
  state,
  isMyTurn,
  connected,
  onAttack,
  onCast,
  onUseItem,
  spellCatalog,
  onPrepare,
  onLongRest,
  onShortRest,
  onManage,
  onShop,
}: ActionBarProps) {
  const [openMenu, setOpenMenu] = useState<"cast" | "item" | null>(null);
  const disabled = !isMyTurn || !connected;

  const availableSlots =
    state?.spellSlots.filter((s) => s.used < s.max) ?? [];
  const usableItems = state?.inventory.filter((i) => i.qty > 0) ?? [];
  const cantrips = state?.cantrips ?? [];
  // Only PREPARED leveled spells can be cast from a slot.
  const preparedSpells = state?.preparedSpells ?? [];
  // Leveled spells are cast from the lowest slot still available.
  const lowestSlot = availableSlots.length > 0 ? availableSlots[0].level : null;

  // Ritual-tagged known spells can be cast with no slot (out of combat). Keyed by lowercased name.
  const ritualByName = new Map(
    (spellCatalog ?? []).filter((s) => s.ritual).map((s) => [s.name.toLowerCase(), s])
  );
  const ritualSpells = (state?.knownSpells ?? []).filter((n) =>
    ritualByName.has(n.toLowerCase())
  );

  function close() {
    setOpenMenu(null);
  }

  const btn =
    "rounded-md border px-2.5 py-1.5 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-40";

  return (
    <div className="relative flex flex-wrap items-center gap-1.5">
      <span className="text-[10px] font-semibold uppercase tracking-wider text-text-muted">
        Actions
      </span>

      {/* Attack */}
      <button
        type="button"
        disabled={disabled}
        onClick={() => {
          close();
          onAttack();
        }}
        className={cn(btn, "border-accent/60 text-accent hover:bg-accent hover:text-white")}
      >
        ⚔ Attack
      </button>

      {/* Cast Spell */}
      <div className="relative">
        <button
          type="button"
          disabled={disabled}
          onClick={() => setOpenMenu(openMenu === "cast" ? null : "cast")}
          aria-haspopup="true"
          aria-expanded={openMenu === "cast"}
          className={cn(btn, "border-border text-text-muted hover:border-accent/60 hover:text-accent")}
        >
          ✦ Cast Spell ▾
        </button>
        {openMenu === "cast" && (
          <Menu>
            <div className="max-h-72 overflow-y-auto">
              {/* Cantrips — no slot cost */}
              <SectionLabel>Cantrips</SectionLabel>
              {cantrips.length === 0 ? (
                <MenuItem
                  onClick={() => {
                    close();
                    onCast(0);
                  }}
                >
                  Cantrip <span className="text-text-muted">(no slot)</span>
                </MenuItem>
              ) : (
                cantrips.map((name) => (
                  <MenuItem
                    key={name}
                    onClick={() => {
                      close();
                      onCast(0, name);
                    }}
                  >
                    {name}
                  </MenuItem>
                ))
              )}

              {/* Prepared leveled spells — consume the lowest available slot */}
              {preparedSpells.length > 0 && (
                <>
                  <SectionLabel>
                    Spells{" "}
                    <span className="text-text-muted normal-case">
                      (uses a slot)
                    </span>
                  </SectionLabel>
                  {preparedSpells.map((name) => (
                    <MenuItem
                      key={name}
                      disabled={lowestSlot === null}
                      onClick={() => {
                        if (lowestSlot === null) return;
                        close();
                        onCast(lowestSlot, name);
                      }}
                    >
                      {name}
                      {lowestSlot !== null && (
                        <span className="tabular text-text-muted">
                          {" "}
                          · L{lowestSlot}
                        </span>
                      )}
                    </MenuItem>
                  ))}
                </>
              )}

              {/* Ritual-tagged spells — cast with no slot (works even with no slots left) */}
              {ritualSpells.length > 0 && (
                <>
                  <SectionLabel>
                    Rituals{" "}
                    <span className="text-text-muted normal-case">(no slot)</span>
                  </SectionLabel>
                  {ritualSpells.map((name) => {
                    const meta = ritualByName.get(name.toLowerCase());
                    return (
                      <MenuItem
                        key={`ritual-${name}`}
                        onClick={() => {
                          close();
                          onCast(meta?.level ?? 1, name, true);
                        }}
                      >
                        {name}
                        <span className="text-text-muted"> · ⟳ ritual</span>
                      </MenuItem>
                    );
                  })}
                </>
              )}

              {/* Generic cast at a chosen slot level */}
              <SectionLabel>Cast at slot level</SectionLabel>
              {availableSlots.length === 0 ? (
                <p className="px-3 py-2 text-xs text-text-muted">
                  No spell slots
                </p>
              ) : (
                availableSlots.map((s) => (
                  <MenuItem
                    key={s.level}
                    onClick={() => {
                      close();
                      onCast(s.level);
                    }}
                  >
                    Level {s.level}{" "}
                    <span className="tabular text-text-muted">
                      ({s.max - s.used}/{s.max})
                    </span>
                  </MenuItem>
                ))
              )}
            </div>
          </Menu>
        )}
      </div>

      {/* Use Item */}
      <div className="relative">
        <button
          type="button"
          disabled={disabled}
          onClick={() => setOpenMenu(openMenu === "item" ? null : "item")}
          aria-haspopup="true"
          aria-expanded={openMenu === "item"}
          className={cn(btn, "border-border text-text-muted hover:border-accent/60 hover:text-accent")}
        >
          🎒 Use Item ▾
        </button>
        {openMenu === "item" && (
          <Menu>
            {usableItems.length === 0 ? (
              <p className="px-3 py-2 text-xs text-text-muted">Inventory empty</p>
            ) : (
              usableItems.map((item) => (
                <MenuItem
                  key={`${item.name}-${item.kind}`}
                  onClick={() => {
                    close();
                    onUseItem(item.name);
                  }}
                >
                  {item.name}
                  {item.qty > 1 && (
                    <span className="tabular text-text-muted"> ×{item.qty}</span>
                  )}
                </MenuItem>
              ))
            )}
          </Menu>
        )}
      </div>

      {/* Management — not turn-gated */}
      {onManage && (
        <button
          type="button"
          disabled={!connected}
          onClick={() => {
            close();
            onManage();
          }}
          className={cn(btn, "border-border text-text-muted hover:border-accent/60 hover:text-accent")}
        >
          🎒 Manage
        </button>
      )}
      {onShop && (
        <button
          type="button"
          disabled={!connected}
          title="Buy and sell with the merchant at your current location."
          onClick={() => {
            close();
            onShop();
          }}
          className={cn(btn, "border-gold/40 text-gold hover:bg-gold hover:text-bg")}
        >
          🪙 Shop
        </button>
      )}
      {onPrepare && (state?.preparedMax ?? 0) > 0 && (
        <button
          type="button"
          disabled={!connected}
          title="Choose which leveled spells you have prepared today."
          onClick={() => {
            close();
            onPrepare();
          }}
          className={cn(btn, "border-gold/40 text-gold hover:bg-gold hover:text-bg")}
        >
          ✦ Prepare
          <span className="ml-1 tabular opacity-80">
            {preparedSpells.length}/{state?.preparedMax ?? 0}
          </span>
        </button>
      )}
      {onShortRest && (
        <button
          type="button"
          disabled={!connected}
          title="Take an hour to spend your remaining Hit Dice and recover some HP."
          onClick={() => {
            close();
            onShortRest(state?.hitDiceRemaining ?? 0);
          }}
          className={cn(btn, "border-gold/30 text-gold/90 hover:bg-gold hover:text-bg")}
        >
          ☀ Short Rest
          {state && state.hitDiceTotal > 0 && (
            <span className="ml-1 tabular opacity-80">
              {state.hitDiceRemaining}/{state.hitDiceTotal} HD
            </span>
          )}
        </button>
      )}
      {onLongRest && (
        <button
          type="button"
          disabled={!connected}
          title="Sleep 8 hours to restore HP and spell slots and ease exhaustion."
          onClick={() => {
            close();
            onLongRest();
          }}
          className={cn(btn, "border-gold/50 text-gold hover:bg-gold hover:text-bg")}
        >
          ☾ Long Rest
        </button>
      )}

      {!isMyTurn && (
        <span className="text-[10px] italic text-text-muted">
          (actions on your turn)
        </span>
      )}
    </div>
  );
}

function Menu({ children }: { children: React.ReactNode }) {
  return (
    <div className="absolute bottom-full left-0 z-20 mb-1 min-w-44 overflow-hidden rounded-lg border border-border-accent bg-surface shadow-[0_0_24px_var(--color-accent-glow)]">
      {children}
    </div>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="sticky top-0 bg-surface px-3 pb-1 pt-2 text-[10px] font-semibold uppercase tracking-wider text-gold">
      {children}
    </div>
  );
}

function MenuItem({
  children,
  onClick,
  disabled = false,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="block w-full px-3 py-2 text-left text-xs text-text transition hover:bg-accent-glow hover:text-accent disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-text"
    >
      {children}
    </button>
  );
}
