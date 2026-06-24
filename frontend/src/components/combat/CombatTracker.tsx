"use client";

import { useState } from "react";
import type { CombatStateDto, PlayerRuntimeState } from "@/types";
import { cn } from "@/components/ui";

interface CombatTrackerProps {
  combat: CombatStateDto;
  myPlayerId: string;
  myState: PlayerRuntimeState | null;
  isHost: boolean;
  connected: boolean;
  onAttack: (enemyId: string) => void;
  onUseItem: (itemName: string) => void;
  onEndTurn: () => void;
  onEndCombat: () => void;
}

/**
 * Combat overlay: round + initiative order, enemy HP bars (click a live enemy to
 * attack on your turn), and the active player's controls. While combat is active
 * the chat input and the narrative ActionBar are disabled in the parent.
 */
export default function CombatTracker({
  combat,
  myPlayerId,
  myState,
  isHost,
  connected,
  onAttack,
  onUseItem,
  onEndTurn,
  onEndCombat,
}: CombatTrackerProps) {
  const [itemMenu, setItemMenu] = useState(false);
  const isMyTurn =
    combat.active?.kind === "PLAYER" && combat.active.refId === myPlayerId;
  const usableItems = myState?.inventory.filter((i) => i.qty > 0) ?? [];

  return (
    <div className="border-b border-border-accent bg-accent-glow/40 p-3">
      {/* Header + initiative */}
      <div className="mb-2 flex items-center justify-between">
        <span className="font-display text-sm font-bold text-accent">
          ⚔ Combat — Round {combat.round}
        </span>
        {isHost && (
          <button
            type="button"
            onClick={onEndCombat}
            className="rounded border border-border px-2 py-0.5 text-[10px] uppercase tracking-wider text-text-muted transition hover:border-accent/60 hover:text-accent"
          >
            End Combat
          </button>
        )}
      </div>

      <div className="mb-3 flex flex-wrap gap-1">
        {combat.order.map((c, i) => (
          <span
            key={`${c.refId}-${i}`}
            className={cn(
              "rounded px-2 py-0.5 text-[10px] tabular transition",
              i === combat.activeIndex
                ? "bg-accent text-white"
                : c.kind === "ENEMY"
                  ? "bg-surface-light text-accent-light"
                  : "bg-surface-light text-text-muted"
            )}
            title={`Initiative ${c.initiative}`}
          >
            {c.name}
          </span>
        ))}
      </div>

      {/* Enemies */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {combat.enemies.map((e) => {
          const ratio = e.maxHp > 0 ? e.currentHp / e.maxHp : 0;
          const targetable = isMyTurn && e.alive && connected;
          return (
            <button
              key={e.id}
              type="button"
              disabled={!targetable}
              onClick={() => targetable && onAttack(e.id)}
              className={cn(
                "rounded-lg border p-2 text-left transition",
                !e.alive
                  ? "border-border bg-surface/40 opacity-50"
                  : targetable
                    ? "cursor-pointer border-accent/60 bg-surface hover:border-accent hover:shadow-[0_0_16px_var(--color-accent-glow)]"
                    : "border-border bg-surface"
              )}
              title={targetable ? `Attack ${e.name}` : e.name}
            >
              <div className="flex items-center justify-between">
                <span
                  className={cn(
                    "truncate text-xs font-semibold",
                    e.alive ? "text-text" : "text-text-muted line-through"
                  )}
                >
                  {e.name}
                </span>
                <span className="text-[9px] text-text-muted">AC {e.armorClass}</span>
              </div>
              <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-surface-light">
                <div
                  className={cn(
                    "h-full rounded-full transition-all",
                    ratio > 0.5 ? "bg-danger" : ratio > 0.25 ? "bg-gold" : "bg-accent-dark"
                  )}
                  style={{ width: `${Math.max(0, Math.min(100, ratio * 100))}%` }}
                />
              </div>
              <div className="mt-0.5 text-right text-[9px] tabular text-text-muted">
                {Math.max(0, e.currentHp)}/{e.maxHp}
              </div>
            </button>
          );
        })}
      </div>

      {/* Controls */}
      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        {isMyTurn ? (
          <>
            <span className="text-[10px] font-semibold uppercase tracking-wider text-gold">
              Your turn
            </span>
            <span className="text-[10px] text-text-muted">
              — click an enemy to attack
            </span>
            <div className="relative">
              <button
                type="button"
                disabled={!connected}
                onClick={() => setItemMenu((v) => !v)}
                className="rounded-md border border-border px-2.5 py-1.5 text-xs font-semibold text-text-muted transition hover:border-accent/60 hover:text-accent disabled:opacity-40"
              >
                🎒 Use Item ▾
              </button>
              {itemMenu && (
                <div className="absolute bottom-full left-0 z-20 mb-1 min-w-40 overflow-hidden rounded-lg border border-border-accent bg-surface shadow-[0_0_24px_var(--color-accent-glow)]">
                  {usableItems.length === 0 ? (
                    <p className="px-3 py-2 text-xs text-text-muted">
                      Inventory empty
                    </p>
                  ) : (
                    usableItems.map((item) => (
                      <button
                        key={`${item.name}-${item.kind}`}
                        type="button"
                        onClick={() => {
                          setItemMenu(false);
                          onUseItem(item.name);
                        }}
                        className="block w-full px-3 py-2 text-left text-xs text-text transition hover:bg-accent-glow hover:text-accent"
                      >
                        {item.name}
                        {item.qty > 1 && (
                          <span className="tabular text-text-muted"> ×{item.qty}</span>
                        )}
                      </button>
                    ))
                  )}
                </div>
              )}
            </div>
            <button
              type="button"
              disabled={!connected}
              onClick={onEndTurn}
              className="rounded-md border border-accent/60 px-2.5 py-1.5 text-xs font-semibold text-accent transition hover:bg-accent hover:text-white disabled:opacity-40"
            >
              End Turn
            </button>
          </>
        ) : (
          <span className="text-xs italic text-text-muted">
            Waiting for {combat.active?.name ?? "the next combatant"}…
          </span>
        )}
      </div>
    </div>
  );
}
