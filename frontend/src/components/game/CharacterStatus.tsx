"use client";

import type { PlayerRuntimeState } from "@/types";
import { cn, HpBar } from "@/components/ui";
import { conditionMeta, conditionChipClasses } from "@/lib/conditions";
import { EXHAUSTION_EFFECTS } from "@/lib/dnd5e";
import DeathSaveTrack, {
  StatusBadge,
  deriveDeathStatus,
} from "@/components/combat/DeathSaveTrack";

interface CharacterStatusProps {
  state: PlayerRuntimeState;
  characterName?: string;
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
  const death = deriveDeathStatus(state);
  const exhaustion = state.exhaustionLevel ?? 0;
  // Cumulative effects up to the current level, for the badge tooltip (color is never the only signal).
  const exhaustionTooltip = EXHAUSTION_EFFECTS.slice(1, exhaustion + 1)
    .map((e, i) => `${i + 1}. ${e}`)
    .join(" · ");

  return (
    <div className="space-y-3 rounded-lg border border-border bg-bg-elevated p-3">
      {characterName && (
        <div className="flex items-center justify-between gap-2">
          <div className="truncate font-display text-sm font-bold text-text">
            {characterName}
          </div>
          {death && <StatusBadge status={death} />}
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
        {death ? (
          /* At 0 HP the bar reads as empty anyway — surface the death-save state instead. */
          <div className="flex items-center justify-between gap-2 rounded-md border border-border bg-surface/60 px-2 py-1.5">
            {!characterName && <StatusBadge status={death} />}
            {death === "DEAD" ? (
              <span className="text-[10px] uppercase tracking-wider text-text-muted">
                Beyond saving
              </span>
            ) : (
              <DeathSaveTrack
                successes={state.deathSaveSuccesses}
                failures={state.deathSaveFailures}
              />
            )}
            <span className="tabular text-[10px] text-text-muted">0 HP</span>
          </div>
        ) : (
          <HpBar current={state.currentHp} max={state.maxHp} />
        )}
      </div>

      {/* Hit Dice (spent on a short rest) */}
      {state.hitDiceTotal > 0 && (
        <div className="flex items-baseline justify-between text-[10px] uppercase tracking-wider text-text-muted">
          <span>Hit Dice</span>
          <span className="tabular text-text">
            {state.hitDiceRemaining}/{state.hitDiceTotal}
          </span>
        </div>
      )}

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

      {/* Exhaustion + conditions + concentration */}
      {(exhaustion > 0 || state.conditions.length > 0 || state.concentratingSpell) && (
        <div className="flex flex-wrap gap-1">
          {exhaustion > 0 && (
            <span
              title={`Exhaustion level ${exhaustion} — ${exhaustionTooltip}. A long rest eases it by one level.`}
              className={cn(
                "rounded border px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider",
                exhaustion >= 4
                  ? "border-accent bg-accent/15 text-accent"
                  : "border-accent/50 bg-accent/10 text-accent"
              )}
            >
              ⚠ Exhaustion {exhaustion}
            </span>
          )}
          {state.concentratingSpell && (
            <span
              title={`Concentrating on ${state.concentratingSpell}`}
              className="rounded border border-gold/40 bg-gold-muted px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-gold"
            >
              ◈ {state.concentratingSpell}
            </span>
          )}
          {state.conditions.map((c) => {
            const meta = conditionMeta(c);
            return (
              <span
                key={c}
                title={`${meta.label} — ${meta.hint}`}
                className={cn(
                  "rounded border px-1.5 py-0.5 text-[10px] uppercase tracking-wider",
                  conditionChipClasses(meta.tone)
                )}
              >
                {meta.label}
              </span>
            );
          })}
        </div>
      )}
    </div>
  );
}
