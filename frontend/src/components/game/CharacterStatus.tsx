"use client";

import type { PlayerRuntimeState } from "@/types";
import { cn } from "@/components/ui";

interface CharacterStatusProps {
  state: PlayerRuntimeState;
  characterName?: string;
}

function hpColor(ratio: number): string {
  if (ratio > 0.5) return "bg-success";
  if (ratio > 0.25) return "bg-gold";
  return "bg-danger";
}

/**
 * Compact runtime panel for a player: HP bar (with temp HP), spell-slot pips per
 * level, inventory quick-list, and active conditions. Read-only; mutations happen
 * via the ActionBar / backend.
 */
export default function CharacterStatus({
  state,
  characterName,
}: CharacterStatusProps) {
  const ratio = state.maxHp > 0 ? state.currentHp / state.maxHp : 0;

  return (
    <div className="space-y-3 rounded-lg border border-border bg-bg-elevated p-3">
      {characterName && (
        <div className="truncate font-display text-sm font-bold text-text">
          {characterName}
        </div>
      )}

      {/* HP */}
      <div>
        <div className="mb-1 flex items-baseline justify-between text-[10px] uppercase tracking-wider text-text-muted">
          <span>Hit Points</span>
          <span className="tabular text-text">
            {state.currentHp}/{state.maxHp}
            {state.tempHp > 0 && (
              <span className="ml-1 text-gold">+{state.tempHp}</span>
            )}
          </span>
        </div>
        <div className="h-2 w-full overflow-hidden rounded-full bg-surface-light">
          <div
            className={cn("h-full rounded-full transition-all", hpColor(ratio))}
            style={{ width: `${Math.max(0, Math.min(100, ratio * 100))}%` }}
          />
        </div>
      </div>

      {/* Spell slots */}
      {state.spellSlots.length > 0 && (
        <div>
          <div className="mb-1 text-[10px] uppercase tracking-wider text-text-muted">
            Spell Slots
          </div>
          <div className="space-y-1">
            {state.spellSlots.map((slot) => (
              <div key={slot.level} className="flex items-center gap-2">
                <span className="w-6 text-[10px] tabular text-text-muted">
                  L{slot.level}
                </span>
                <div className="flex flex-wrap gap-1">
                  {Array.from({ length: slot.max }).map((_, i) => (
                    <span
                      key={i}
                      className={cn(
                        "h-2.5 w-2.5 rounded-full border",
                        i < slot.max - slot.used
                          ? "border-accent bg-accent"
                          : "border-border bg-transparent"
                      )}
                      title={i < slot.max - slot.used ? "available" : "spent"}
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Conditions */}
      {state.conditions.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {state.conditions.map((c) => (
            <span
              key={c}
              className="rounded bg-accent-dark/30 px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-accent-light"
            >
              {c}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
