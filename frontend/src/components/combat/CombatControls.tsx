import { useState } from "react";
import type { InventoryItem, SpellSummary, Token } from "@/types";
import { Button, Tooltip, ConfirmDialog, cn } from "@/components/ui";
import { targetCap, spellMechanics, type Castable } from "@/lib/combat";
import { EconomyPip } from "@/components/combat/atoms";
import SpellTooltip from "@/components/combat/SpellTooltip";

interface CombatControlsProps {
  /** Queued animations / DM narration still playing — hold the controls. */
  busy: boolean;
  queueLength: number;
  isMyTurn: boolean;
  /** Name of the active combatant (for the "Waiting for …" line). */
  activeName: string | undefined;
  connected: boolean;
  /* ── Action economy ── */
  actionSpent: boolean;
  bonusSpent: boolean;
  reactionSpent: boolean;
  moveBudget: number;
  moveUsed: number;
  /** My token (drives the Dash/Disengage/Dodge toggled state). */
  myToken: Token | null;
  /* ── AoE placement ── */
  placingSpellName: string | null;
  /** All catalog spells (to resolve the placing spell's mechanics line). */
  spells: SpellSummary[];
  onCancelAoe: () => void;
  /* ── Single/multi-target casting ── */
  casting: SpellSummary | null;
  castingAlly: boolean;
  picked: string[];
  onConfirmCast: () => void;
  onCancelCast: () => void;
  /* ── Cast / item menus ── */
  castable: Castable[];
  onBeginCast: (spell: SpellSummary) => void;
  usableItems: InventoryItem[];
  onUseItem: (itemName: string) => void;
  /* ── Movement / defensive actions ── */
  onDash: () => void;
  onDisengage: () => void;
  onDodge: () => void;
  onEndTurn: () => void;
}

/**
 * The active player's control ladder: busy / not-my-turn / placing-AoE / casting / default
 * (action-economy pips + Cast menu + Use-Item menu + Dash/Disengage/Dodge + End Turn).
 */
export default function CombatControls({
  busy,
  queueLength,
  isMyTurn,
  activeName,
  connected,
  actionSpent,
  bonusSpent,
  reactionSpent,
  moveBudget,
  moveUsed,
  myToken,
  placingSpellName,
  spells,
  onCancelAoe,
  casting,
  castingAlly,
  picked,
  onConfirmCast,
  onCancelCast,
  castable,
  onBeginCast,
  usableItems,
  onUseItem,
  onDash,
  onDisengage,
  onDodge,
  onEndTurn,
}: CombatControlsProps) {
  const [itemMenu, setItemMenu] = useState(false);
  const [spellMenu, setSpellMenu] = useState(false);
  const [endTurnConfirm, setEndTurnConfirm] = useState(false);

  function beginCast(spell: Castable) {
    setSpellMenu(false);
    onBeginCast(spell);
  }

  return (
    <>
      <div className="relative mt-3 flex flex-wrap items-center gap-1.5">
        {busy ? (
          <span className="flex items-center gap-2 text-xs italic text-text-muted">
            <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-accent" />
            {queueLength > 0 ? "Resolving combat…" : "The DM is narrating…"}
          </span>
        ) : !isMyTurn ? (
          <span className="text-xs italic text-text-muted">
            Waiting for {activeName ?? "the next combatant"}…
          </span>
        ) : placingSpellName ? (
          <>
            <span className="text-[10px] font-semibold uppercase tracking-wider text-gold">
              ✨ {placingSpellName}
            </span>
            {(() => {
              const ps = spells.find((s) => s.name === placingSpellName);
              return ps ? (
                <span className="text-[10px] text-gold/90" title={ps.summary}>
                  {spellMechanics(ps)}
                </span>
              ) : null;
            })()}
            <span className="text-[10px] text-text-muted">
              — click a square on the battle map to aim
            </span>
            <button
              type="button"
              onClick={onCancelAoe}
              className="rounded-md border border-border px-2.5 py-1.5 text-xs font-semibold text-text-muted transition hover:border-accent/60 hover:text-accent"
            >
              Cancel
            </button>
          </>
        ) : casting ? (
          <>
            <span className="text-[10px] font-semibold uppercase tracking-wider text-gold">
              ✨ {casting.name}
            </span>
            <span className="text-[10px] text-gold/90" title={casting.summary}>
              {spellMechanics(casting)}
            </span>
            <span className="text-[10px] text-text-muted">
              {castingAlly
                ? "— click an ally on the map"
                : "— click a target on the map"}
              {targetCap(casting) > 1 ? ` (up to ${targetCap(casting)})` : ""}
            </span>
            {targetCap(casting) > 1 && (
              <button
                type="button"
                disabled={!connected || picked.length === 0}
                onClick={onConfirmCast}
                className="rounded-md border border-accent/60 px-2.5 py-1.5 text-xs font-semibold text-accent transition hover:bg-accent hover:text-white disabled:opacity-40"
              >
                Cast ({picked.length})
              </button>
            )}
            <button
              type="button"
              onClick={onCancelCast}
              className="rounded-md border border-border px-2.5 py-1.5 text-xs font-semibold text-text-muted transition hover:border-accent/60 hover:text-accent"
            >
              Cancel
            </button>
          </>
        ) : (
          <>
            {/* Action-economy tracker */}
            <div className="mr-1 flex items-center gap-1.5 text-[10px] uppercase tracking-wider">
              <EconomyPip label="Action" spent={actionSpent} />
              <EconomyPip label="Bonus" spent={bonusSpent} />
              <EconomyPip label="React" spent={reactionSpent} />
              <span
                className="tabular text-text-muted"
                title="Movement used / available this turn"
              >
                {Math.max(0, moveBudget - moveUsed)}/{moveBudget} ft
              </span>
            </div>

            {/* Cast Spell (the menu is rendered at row level below so it stays in-bounds) */}
            <button
              type="button"
              disabled={!connected || castable.length === 0}
              onClick={() => setSpellMenu((v) => !v)}
              className="rounded-md border border-border px-2.5 py-1.5 text-xs font-semibold text-text-muted transition hover:border-accent/60 hover:text-accent disabled:opacity-40"
              title={castable.length === 0 ? "No spells available" : "Cast a spell"}
            >
              ✨ Cast Spell ▾
            </button>
            {/* Spell menu — anchored to the full-width controls row + size-capped so it can
                never overflow the panel / viewport. */}
            {spellMenu && (
              <div className="absolute top-full left-0 z-30 mt-1 max-h-[50vh] w-72 max-w-[calc(100vw-2rem)] overflow-y-auto rounded-lg border border-border-accent bg-surface shadow-[0_0_24px_var(--color-accent-glow)]">
                {castable.map((s) => {
                  const bonus = s.castingTime === "Bonus Action";
                  const disabled = bonus ? bonusSpent : actionSpent;
                  return (
                    <SpellTooltip key={s.name} spell={s} placement="left" className="w-full">
                      <button
                        type="button"
                        disabled={disabled}
                        onClick={() => beginCast(s)}
                        className="block w-full border-b border-border/50 px-3 py-2 text-left text-xs text-text transition last:border-b-0 hover:bg-accent-glow hover:text-accent disabled:opacity-40 disabled:hover:bg-transparent"
                      >
                        <div className="flex items-center gap-1">
                          <span className="font-semibold">{s.name}</span>
                          {bonus && <span className="text-gold" title="Bonus action">⚡</span>}
                          <span className="ml-auto text-[9px] uppercase tracking-wide text-text-muted">
                            {s.level === 0 ? "Cantrip" : `L${s.level}`}
                          </span>
                        </div>
                        <div className="mt-0.5 text-[10px] text-gold/90">
                          {spellMechanics(s)}
                        </div>
                      </button>
                    </SpellTooltip>
                  );
                })}
              </div>
            )}

            {/* Use Item */}
            <div className="relative">
              <button
                type="button"
                disabled={!connected || actionSpent}
                onClick={() => setItemMenu((v) => !v)}
                title={actionSpent ? "Action already used" : "Use an item"}
                className="rounded-md border border-border px-2.5 py-1.5 text-xs font-semibold text-text-muted transition hover:border-accent/60 hover:text-accent disabled:opacity-40"
              >
                🎒 Use Item ▾
              </button>
              {itemMenu && (
                <div className="absolute top-full left-0 z-20 mt-1 max-h-[50vh] min-w-40 overflow-y-auto rounded-lg border border-border-accent bg-surface shadow-[0_0_24px_var(--color-accent-glow)]">
                  {usableItems.length === 0 ? (
                    <p className="px-3 py-2 text-xs text-text-muted">Inventory empty</p>
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

            {/* Movement / defensive actions — don't end the turn */}
            <Tooltip
              placement="top"
              content={
                <span className="block max-w-[12rem] text-xs text-text-muted">
                  <span className="font-semibold text-gold">Dash</span> — double
                  your movement this turn.
                </span>
              }
            >
              <Button
                type="button"
                size="sm"
                variant={myToken?.dashed ? "primary" : "ghost"}
                disabled={!connected || !!myToken?.dashed || actionSpent}
                onClick={onDash}
              >
                🏃 Dash
              </Button>
            </Tooltip>

            <Tooltip
              placement="top"
              content={
                <span className="block max-w-[12rem] text-xs text-text-muted">
                  <span className="font-semibold text-gold">Disengage</span> —
                  your movement won&apos;t provoke opportunity attacks.
                </span>
              }
            >
              <Button
                type="button"
                size="sm"
                variant={myToken?.disengaged ? "primary" : "ghost"}
                disabled={!connected || !!myToken?.disengaged || actionSpent}
                onClick={onDisengage}
              >
                🛡 Disengage
              </Button>
            </Tooltip>

            <Tooltip
              placement="top"
              content={
                <span className="block max-w-[12rem] text-xs text-text-muted">
                  <span className="font-semibold text-gold">Dodge</span> —
                  attacks against you have disadvantage until your next turn.
                </span>
              }
            >
              <Button
                type="button"
                size="sm"
                variant={myToken?.dodging ? "primary" : "ghost"}
                disabled={!connected || !!myToken?.dodging || actionSpent}
                onClick={onDodge}
                className={cn(myToken?.dodging && "!bg-gold !text-bg")}
              >
                ✦ Dodge
              </Button>
            </Tooltip>

            <button
              type="button"
              disabled={!connected}
              onClick={() => {
                // Guard against wasting an unused action/bonus action — confirm via dialog.
                if (!actionSpent || !bonusSpent) {
                  setEndTurnConfirm(true);
                } else {
                  onEndTurn();
                }
              }}
              className="rounded-md border border-accent/60 px-2.5 py-1.5 text-xs font-semibold text-accent transition hover:bg-accent hover:text-white disabled:opacity-40"
            >
              End Turn
            </button>
          </>
        )}
      </div>

      <ConfirmDialog
        open={endTurnConfirm}
        title="End your turn?"
        message={`You still have your ${
          !actionSpent && !bonusSpent
            ? "action and bonus action"
            : !actionSpent
              ? "action"
              : "bonus action"
        } unused. End your turn anyway?`}
        confirmLabel="End Turn"
        cancelLabel="Keep going"
        onConfirm={() => {
          setEndTurnConfirm(false);
          onEndTurn();
        }}
        onClose={() => setEndTurnConfirm(false)}
      />
    </>
  );
}
