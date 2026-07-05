"use client";

import { useEffect, useMemo, useState } from "react";
import type { CombatStateDto, PlayerRuntimeState, SpellSummary } from "@/types";
import { useSessionStore } from "@/store/sessionStore";
import { bandMeta } from "@/lib/health";
import {
  isAllyTargeting,
  weaponRangeFeet,
  weaponMasteryFor,
  resolveCastable,
} from "@/lib/combat";
import { gridDistanceFeet } from "@/lib/grid";
import { deriveDeathStatus } from "@/components/combat/DeathSaveTrack";
import InitiativeRow from "@/components/combat/InitiativeRow";
import EnemyCard from "@/components/combat/EnemyCard";
import AllyCard from "@/components/combat/AllyCard";
import CombatControls from "@/components/combat/CombatControls";

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
  /* ── Bonus actions ── */
  /** Lowercased class name, to gate class bonus actions (fighter / rogue). */
  myClass: string;
  onOffHandAttack: (enemyId: string) => void;
  onSecondWind: () => void;
  onCunningAction: (action: "dash" | "disengage" | "hide") => void;
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
  myClass,
  onOffHandAttack,
  onSecondWind,
  onCunningAction,
}: CombatTrackerProps) {
  const isMyTurn =
    combat.active?.kind === "PLAYER" && combat.active.refId === myPlayerId;
  const usableItems = myState?.inventory.filter((i) => i.qty > 0) ?? [];

  // Off-hand attack "mode": armed from the controls, then the next enemy click resolves it.
  const [offHandMode, setOffHandMode] = useState(false);
  const hasOffHandWeapon = !!myState?.inventory.some(
    (i) => i.kind === "WEAPON" && i.slot === "OFF_HAND"
  );

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
  const myMastery = weaponMasteryFor(myState?.inventory, myClass);

  // Off-hand mode only makes sense on your turn while a bonus action is available.
  useEffect(() => {
    if (!isMyTurn || bonusSpent) setOffHandMode(false);
  }, [isMyTurn, bonusSpent]);

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

  // Resolve the player's known spells to catalog metadata, keeping only those they can pay for.
  const castable = useMemo(
    () => resolveCastable(myState, spells),
    [myState, spells]
  );

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

      <InitiativeRow order={combat.order} activeIndex={combat.activeIndex} />

      {/* Enemies */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {combat.enemies.map((e) => {
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
            inAttackRange &&
            (offHandMode ? !bonusSpent && hasOffHandWeapon : !actionSpent);
          const selectable = targetingEnemies && e.alive && connected;
          return (
            <EnemyCard
              key={e.id}
              enemy={e}
              band={bandMeta(e.healthBand)}
              initiative={initiativeByEnemyId[e.id]}
              attackable={attackable}
              selectable={selectable}
              chosen={picked.includes(e.id)}
              onAttack={() => {
                if (offHandMode) {
                  onOffHandAttack(e.id);
                  setOffHandMode(false);
                } else {
                  onAttack(e.id);
                }
              }}
              onSelect={() => onSelectTarget(e.id)}
            />
          );
        })}
      </div>

      {/* Party (targetable when casting a heal/buff) */}
      {allies.length > 0 && (
        <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-3">
          {allies.map((a) => {
            const selectable = targetingAllies && connected;
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
              <AllyCard
                key={a.id}
                ally={a}
                runtime={rs}
                status={status}
                selectable={selectable}
                chosen={picked.includes(a.id)}
                canStabilize={canStabilize}
                isMe={a.id === myPlayerId}
                connected={connected}
                onSelect={() => onSelectTarget(a.id)}
                onStabilize={() => onStabilize(a.id)}
              />
            );
          })}
        </div>
      )}

      {/* Controls */}
      <CombatControls
        busy={busy}
        queueLength={queueLength}
        isMyTurn={isMyTurn}
        activeName={combat.active?.name}
        connected={connected}
        actionSpent={actionSpent}
        bonusSpent={bonusSpent}
        reactionSpent={reactionSpent}
        moveBudget={moveBudget}
        moveUsed={moveUsed}
        myToken={myToken}
        placingSpellName={placingSpellName}
        spells={spells}
        onCancelAoe={onCancelAoe}
        casting={casting}
        castingAlly={castingAlly}
        picked={picked}
        onConfirmCast={onConfirmCast}
        onCancelCast={onCancelCast}
        castable={castable}
        onBeginCast={onBeginCast}
        usableItems={usableItems}
        onUseItem={onUseItem}
        onDash={onDash}
        onDisengage={onDisengage}
        onDodge={onDodge}
        myClass={myClass}
        weaponMastery={myMastery}
        offHandMode={offHandMode}
        hasOffHandWeapon={hasOffHandWeapon}
        onToggleOffHand={() => setOffHandMode((v) => !v)}
        onSecondWind={onSecondWind}
        onCunningAction={onCunningAction}
        onEndTurn={onEndTurn}
      />
    </div>
  );
}
