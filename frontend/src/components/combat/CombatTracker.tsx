"use client";

import { useEffect, useMemo, useState } from "react";
import type { CombatStateDto, PlayerRuntimeState, SpellSummary } from "@/types";
import { Button, Tooltip, ConfirmDialog, cn } from "@/components/ui";
import { useSessionStore } from "@/store/sessionStore";
import { conditionMeta, conditionChipClasses } from "@/lib/conditions";
import { bandMeta } from "@/lib/health";
import {
  isAllyTargeting,
  targetCap,
  weaponRangeFeet,
  spellMechanics,
} from "@/lib/combat";
import { gridDistanceFeet } from "@/lib/dnd5e";
import DeathSaveTrack, {
  StatusBadge,
  deriveDeathStatus,
} from "@/components/combat/DeathSaveTrack";

export interface AllyTarget {
  id: string;
  name: string;
  currentHp: number;
  maxHp: number;
}

interface CombatTrackerProps {
  combat: CombatStateDto;
  myPlayerId: string;
  myState: PlayerRuntimeState | null;
  /** The local player's walking speed in feet (for the movement budget readout). */
  mySpeed: number;
  isHost: boolean;
  connected: boolean;
  spells: SpellSummary[];
  allies: AllyTarget[];
  /** Per-player runtime — drives ally death-save pips / badges. */
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
  onAttack: (enemyId: string) => void;
  /** Choose a spell to cast — the page routes it (self / AoE placement / grid targeting). */
  onBeginCast: (spell: SpellSummary) => void;
  /** The single/multi-target spell currently awaiting target selection on the grid, or null. */
  castingSpell: SpellSummary | null;
  /** Targets picked so far (multi-target spells), shared with the BattleMap. */
  pickedTargets: string[];
  /** Select/toggle a target (also reachable by clicking enemy/ally cards as a fallback). */
  onSelectTarget: (refId: string) => void;
  /** Commit a multi-target cast. */
  onConfirmCast: () => void;
  /** Abandon the in-flight cast. */
  onCancelCast: () => void;
  /** Name of the AoE spell currently being placed on the map (drives the banner), or null. */
  placingSpellName: string | null;
  /** Abandon AoE placement. */
  onCancelAoe: () => void;
  onUseItem: (itemName: string) => void;
  onStabilize: (targetPlayerId: string) => void;
  onEndTurn: () => void;
  onEndCombat: () => void;
  /* ── Tactical movement / defensive actions (Phase B) ── */
  onDash: () => void;
  onDisengage: () => void;
  onDodge: () => void;
}

/** A castable known spell: the player's chosen name resolved to its catalog metadata. */
interface Castable extends SpellSummary {
  /** True when this spell heals/buffs allies (so it targets the party, not enemies). */
  allyTargeting: boolean;
}

/** One action-economy pip: filled when available, hollow + struck when spent. */
function EconomyPip({ label, spent }: { label: string; spent: boolean }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded px-1 py-0.5 font-semibold",
        spent ? "text-text-muted/60 line-through" : "text-gold"
      )}
      title={spent ? `${label} used` : `${label} available`}
    >
      <span
        className={cn(
          "inline-block h-1.5 w-1.5 rounded-full",
          spent ? "bg-text-muted/40" : "bg-gold"
        )}
      />
      {label}
    </span>
  );
}

/** Inline row of condition chips (code + tooltip) for an enemy/ally card. */
function ConditionChips({ conditions }: { conditions?: string[] | null }) {
  if (!conditions || conditions.length === 0) return null;
  return (
    <div className="mt-1 flex flex-wrap gap-1">
      {conditions.map((name) => {
        const meta = conditionMeta(name);
        return (
          <span
            key={name}
            title={`${meta.label} — ${meta.hint}`}
            className={cn(
              "rounded border px-1 py-px text-[8px] font-semibold uppercase tracking-wide",
              conditionChipClasses(meta.tone)
            )}
          >
            {meta.label}
          </span>
        );
      })}
    </div>
  );
}

/**
 * Combat overlay: round + initiative order, enemy HP bars, party HP bars, and the active
 * player's controls. On your turn you can attack (click an enemy), cast a prepared spell
 * (pick a spell, then click valid targets — enemies for offence, allies for healing/buffs),
 * use an item, or end your turn.
 */
export default function CombatTracker({
  combat,
  myPlayerId,
  myState,
  mySpeed,
  isHost,
  connected,
  spells,
  allies,
  runtimeByPlayerId,
  onAttack,
  onBeginCast,
  castingSpell,
  pickedTargets,
  onSelectTarget,
  onConfirmCast,
  onCancelCast,
  placingSpellName,
  onCancelAoe,
  onUseItem,
  onStabilize,
  onEndTurn,
  onEndCombat,
  onDash,
  onDisengage,
  onDodge,
}: CombatTrackerProps) {
  const [itemMenu, setItemMenu] = useState(false);
  const [spellMenu, setSpellMenu] = useState(false);
  const [endTurnConfirm, setEndTurnConfirm] = useState(false);

  const isMyTurn =
    combat.active?.kind === "PLAYER" && combat.active.refId === myPlayerId;
  const usableItems = myState?.inventory.filter((i) => i.qty > 0) ?? [];

  // My token's action-economy flags (drive the toggled Dash/Disengage/Dodge state).
  const myToken = combat.grid?.tokens[myPlayerId] ?? null;

  // Combat is "busy" while queued action animations are still playing or the DM is narrating the
  // beat — hold the controls so the turn doesn't snap back before the player can follow it.
  const queueLength = useSessionStore((s) => s.combatActionQueue.length);
  const combatNarrating = useSessionStore((s) => s.combatNarrating);
  const clearCombatNarrating = useSessionStore((s) => s.clearCombatNarrating);
  const busy = queueLength > 0 || combatNarrating;

  // 5E action economy for this turn (drives the tracker + disables spent actions).
  const actionSpent = !!myToken?.actionUsed;
  const bonusSpent = !!myToken?.bonusActionUsed;
  const reactionSpent = myToken ? !myToken.reactionAvailable : false;
  const moveUsed = myToken?.movementUsedFeet ?? 0;
  const moveBudget = mySpeed + (myToken?.dashed ? mySpeed : 0);
  const myAttackRange = weaponRangeFeet(myState?.inventory);

  // Safety net: if the final DM_NARRATION is ever lost, don't strand the controls behind the
  // narration gate forever — release it after a generous timeout.
  useEffect(() => {
    if (!combatNarrating) return;
    const t = setTimeout(clearCombatNarrating, 15000);
    return () => clearTimeout(t);
  }, [combatNarrating, clearCombatNarrating]);

  const initiativeByEnemyId: Record<string, number> = {};
  combat.order.forEach((c) => {
    if (c.kind === "ENEMY") initiativeByEnemyId[c.refId] = c.initiative;
  });

  // Resolve the player's known spells (cantrips + leveled) to catalog metadata, keeping
  // only those they can pay for right now (cantrips are free; leveled need a matching slot).
  const castable = useMemo<Castable[]>(() => {
    if (!myState) return [];
    const byName = new Map(spells.map((s) => [s.name.toLowerCase(), s]));
    const hasSlot = (level: number) =>
      myState.spellSlots.some((s) => s.level === level && s.used < s.max);
    const known = [...(myState.cantrips ?? []), ...(myState.knownSpells ?? [])];
    const seen = new Set<string>();
    const out: Castable[] = [];
    for (const name of known) {
      const meta = byName.get(name.toLowerCase());
      if (!meta || seen.has(meta.name)) continue;
      seen.add(meta.name);
      if (meta.level > 0 && !hasSlot(meta.level)) continue;
      out.push({
        ...meta,
        allyTargeting:
          meta.effectType === "HEAL" ||
          meta.effectType === "BUFF" ||
          meta.targetType === "ALLY" ||
          meta.targetType === "SELF",
      });
    }
    return out.sort((a, b) => a.level - b.level || a.name.localeCompare(b.name));
  }, [myState, spells]);

  function beginCast(spell: Castable) {
    setSpellMenu(false);
    onBeginCast(spell);
  }

  // Target selection now happens on the battle map; cards mirror it as an accessible fallback.
  const casting = castingSpell;
  const picked = pickedTargets;
  const castingAlly = !!casting && isAllyTargeting(casting);
  const targetingEnemies = !!casting && !castingAlly;
  const targetingAllies = !!casting && castingAlly;

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
              "inline-flex items-center gap-1 rounded px-2 py-0.5 text-[10px] tabular transition",
              i === combat.activeIndex
                ? "bg-accent text-white"
                : c.kind === "ENEMY"
                  ? "bg-surface-light text-accent-light"
                  : "bg-surface-light text-text-muted"
            )}
            title={`Initiative ${c.initiative}`}
          >
            <span
              className={cn(
                "inline-flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[9px] font-bold",
                i === combat.activeIndex ? "bg-white/25 text-white" : "bg-bg/60 text-gold"
              )}
            >
              {c.initiative}
            </span>
            {c.name}
          </span>
        ))}
      </div>

      {/* Enemies */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {combat.enemies.map((e) => {
          const band = bandMeta(e.healthBand);
          // Gate the card-attack fallback on weapon range, matching the battle map.
          const myTok = combat.grid?.tokens[myPlayerId];
          const eTok = combat.grid?.tokens[e.id];
          const inAttackRange =
            !myTok || !eTok
              ? true
              : gridDistanceFeet(myTok.x, myTok.y, eTok.x, eTok.y) <= myAttackRange;
          const attackable =
            isMyTurn &&
            e.alive &&
            connected &&
            !casting &&
            !actionSpent &&
            inAttackRange;
          const selectable = targetingEnemies && e.alive && connected;
          const chosen = picked.includes(e.id);
          return (
            <button
              key={e.id}
              type="button"
              disabled={!attackable && !selectable}
              onClick={() =>
                selectable ? onSelectTarget(e.id) : attackable && onAttack(e.id)
              }
              className={cn(
                "rounded-lg border p-2 text-left transition",
                !e.alive
                  ? "border-border bg-surface/40 opacity-50"
                  : chosen
                    ? "border-accent bg-accent-glow shadow-[0_0_16px_var(--color-accent-glow)]"
                    : attackable || selectable
                      ? "cursor-pointer border-accent/60 bg-surface hover:border-accent hover:shadow-[0_0_16px_var(--color-accent-glow)]"
                      : "border-border bg-surface"
              )}
              title={
                selectable
                  ? `Target ${e.name}`
                  : attackable
                    ? `Attack ${e.name}`
                    : e.name
              }
            >
              <div className="flex items-center justify-between">
                <span
                  className={cn(
                    "truncate text-xs font-semibold",
                    e.alive ? "text-text" : "text-text-muted line-through"
                  )}
                >
                  {chosen ? "🎯 " : ""}
                  {e.name}
                </span>
                <span className="flex items-center gap-1 text-[9px] text-text-muted">
                  {initiativeByEnemyId[e.id] !== undefined && (
                    <span
                      className="tabular inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-bg/60 px-1 font-bold text-gold"
                      title={`Initiative ${initiativeByEnemyId[e.id]}`}
                    >
                      {initiativeByEnemyId[e.id]}
                    </span>
                  )}
                  AC {e.armorClass}
                </span>
              </div>
              <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-surface-light">
                <div
                  className={cn("h-full rounded-full transition-all", band.barClass)}
                  style={{ width: `${Math.round(band.fill * 100)}%` }}
                />
              </div>
              <div className="mt-0.5 text-right text-[9px] font-semibold uppercase tracking-wide text-text-muted">
                {e.alive ? band.label : "Dead"}
              </div>
              {e.alive && <ConditionChips conditions={e.conditions} />}
            </button>
          );
        })}
      </div>

      {/* Party (targetable when casting a heal/buff) */}
      {allies.length > 0 && (
        <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-3">
          {allies.map((a) => {
            const ratio = a.maxHp > 0 ? a.currentHp / a.maxHp : 0;
            const selectable = targetingAllies && connected;
            const chosen = picked.includes(a.id);
            const rs = runtimeByPlayerId[a.id];
            const status = deriveDeathStatus(rs);
            const canStabilize =
              status === "DYING" &&
              isMyTurn &&
              connected &&
              !casting &&
              !actionSpent &&
              !busy;
            return (
              <div key={a.id} className="flex flex-col gap-1">
                <button
                  type="button"
                  disabled={!selectable}
                  onClick={() => selectable && onSelectTarget(a.id)}
                  className={cn(
                    "rounded-lg border p-2 text-left transition",
                    chosen
                      ? "border-emerald-400 bg-emerald-400/10 shadow-[0_0_16px_rgba(52,211,153,0.25)]"
                      : selectable
                        ? "cursor-pointer border-emerald-400/50 bg-surface hover:border-emerald-400"
                        : status === "DYING"
                          ? "border-danger/40 bg-surface/60"
                          : "border-border bg-surface/60"
                  )}
                  title={selectable ? `Target ${a.name}` : a.name}
                >
                  <div className="flex items-center justify-between gap-1">
                    <span className="truncate text-xs font-semibold text-text">
                      {chosen ? "✚ " : ""}
                      {a.name}
                      {a.id === myPlayerId ? " (you)" : ""}
                    </span>
                    {status && <StatusBadge status={status} />}
                  </div>
                  <ConditionChips conditions={rs?.conditions} />
                  {status ? (
                    <div className="mt-1.5 flex items-center justify-between gap-2">
                      {status === "DEAD" ? (
                        <span className="text-[9px] uppercase tracking-wider text-text-muted">
                          Beyond saving
                        </span>
                      ) : (
                        <DeathSaveTrack
                          successes={rs?.deathSaveSuccesses ?? 0}
                          failures={rs?.deathSaveFailures ?? 0}
                        />
                      )}
                      <span className="tabular text-[9px] text-text-muted">
                        0/{a.maxHp}
                      </span>
                    </div>
                  ) : (
                    <>
                      <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-surface-light">
                        <div
                          className={cn(
                            "h-full rounded-full transition-all",
                            ratio > 0.5
                              ? "bg-emerald-500"
                              : ratio > 0.25
                                ? "bg-gold"
                                : "bg-danger"
                          )}
                          style={{
                            width: `${Math.max(0, Math.min(100, ratio * 100))}%`,
                          }}
                        />
                      </div>
                      <div className="mt-0.5 text-right text-[9px] tabular text-text-muted">
                        {Math.max(0, a.currentHp)}/{a.maxHp}
                      </div>
                    </>
                  )}
                </button>
                {canStabilize && (
                  <Tooltip
                    placement="top"
                    className="w-full"
                    content={
                      <span className="block max-w-[12rem] text-xs text-text-muted">
                        <span className="font-semibold text-gold">
                          Stabilize
                        </span>{" "}
                        — DC 10 Medicine check to stabilize {a.name}. Uses your
                        action.
                      </span>
                    }
                  >
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      fullWidth
                      disabled={!connected}
                      onClick={() => onStabilize(a.id)}
                    >
                      ✚ Stabilize
                    </Button>
                  </Tooltip>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Controls */}
      <div className="relative mt-3 flex flex-wrap items-center gap-1.5">
        {busy ? (
          <span className="flex items-center gap-2 text-xs italic text-text-muted">
            <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-accent" />
            {queueLength > 0
              ? "Resolving combat…"
              : "The DM is narrating…"}
          </span>
        ) : !isMyTurn ? (
          <span className="text-xs italic text-text-muted">
            Waiting for {combat.active?.name ?? "the next combatant"}…
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
              <div className="absolute bottom-full left-0 z-30 mb-1 max-h-[50vh] w-72 max-w-[calc(100vw-2rem)] overflow-y-auto rounded-lg border border-border-accent bg-surface shadow-[0_0_24px_var(--color-accent-glow)]">
                {castable.map((s) => {
                  const bonus = s.castingTime === "Bonus Action";
                  const disabled = bonus ? bonusSpent : actionSpent;
                  return (
                    <button
                      key={s.name}
                      type="button"
                      disabled={disabled}
                      onClick={() => beginCast(s)}
                      title={s.summary || `${s.range} · ${s.castingTime}`}
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
                <div className="absolute bottom-full left-0 z-20 mb-1 min-w-40 overflow-hidden rounded-lg border border-border-accent bg-surface shadow-[0_0_24px_var(--color-accent-glow)]">
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
    </div>
  );
}
