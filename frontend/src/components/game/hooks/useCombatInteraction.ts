"use client";

import { useEffect, useState } from "react";
import type { RefObject } from "react";
import type { Client } from "@stomp/stompjs";
import type { SpellSummary } from "@/types";
import type { PlacingSpell } from "@/components/combat/BattleMap";
import { targetCap } from "@/lib/combat";
import { useSessionStore } from "@/store/sessionStore";

interface CombatCastPayload {
  spellName: string;
  spellLevel: number;
  targetIds: string[];
  originX?: number;
  originY?: number;
}

/**
 * The in-combat cast / AoE-placement / target-selection sub-state-machine.
 *
 * Owns the three pieces of transient targeting state (`placingSpell`, `castingSpell`,
 * `pickedTargets`) plus the handlers that drive them, and the two cancel effects:
 *  - abandon any in-flight placement/selection when the player's turn passes or combat ends;
 *  - Escape cancels an in-flight cast / AoE placement.
 *
 * Casts are dispatched through `combatCast` (the guarded WS send from useGameActions). The
 * `clientRef`/`connected` guard is preserved verbatim so non-connected clicks are no-ops.
 */
export function useCombatInteraction({
  clientRef,
  connected,
  playerId,
  combatCast,
}: {
  clientRef: RefObject<Client | null>;
  connected: boolean;
  playerId: string | null;
  combatCast: (payload: CombatCastPayload) => void;
}) {
  /** AoE spell awaiting an origin click on the battle map (null = not placing). */
  const [placingSpell, setPlacingSpell] = useState<PlacingSpell | null>(null);
  // Single/multi-target spell awaiting target selection on the grid (AoE uses placingSpell).
  const [castingSpell, setCastingSpell] = useState<SpellSummary | null>(null);
  const [pickedTargets, setPickedTargets] = useState<string[]>([]);

  const combat = useSessionStore((s) => s.combat);
  const inCombat = combat?.status === "ACTIVE";
  const combatIsMyTurn =
    inCombat &&
    combat?.active?.kind === "PLAYER" &&
    combat.active.refId === playerId;

  function handleCombatCast(
    spellName: string,
    spellLevel: number,
    targetIds: string[]
  ) {
    combatCast({ spellName, spellLevel, targetIds });
  }

  function handleCancelAoe() {
    setPlacingSpell(null);
  }

  /** Commit an AoE cast at the clicked origin (server computes the hit set). */
  function handleCastAoe(
    spellName: string,
    spellLevel: number,
    x: number,
    y: number
  ) {
    if (!clientRef.current || !connected) return;
    combatCast({
      spellName,
      spellLevel,
      targetIds: [],
      originX: x,
      originY: y,
    });
    setPlacingSpell(null);
  }

  /**
   * Pick a spell to cast: SELF casts immediately, AoE enters on-map placement, and a
   * single/multi-target spell enters grid targeting (click tokens on the BattleMap).
   */
  function handleBeginCast(spell: SpellSummary) {
    if (!clientRef.current || !connected) return;
    if (spell.targetType === "SELF") {
      if (playerId) handleCombatCast(spell.name, spell.level, [playerId]);
      return;
    }
    if (spell.aoeShape && spell.aoeSize > 0) {
      setPlacingSpell({
        name: spell.name,
        level: spell.level,
        aoeShape: spell.aoeShape,
        aoeSize: spell.aoeSize,
      });
      return;
    }
    setCastingSpell(spell);
    setPickedTargets([]);
  }

  /** A target token was clicked on the grid (or a card): cast now if single-target, else toggle. */
  function handleSelectTarget(refId: string) {
    if (!castingSpell) return;
    const cap = targetCap(castingSpell);
    if (cap <= 1) {
      handleCombatCast(castingSpell.name, castingSpell.level, [refId]);
      setCastingSpell(null);
      setPickedTargets([]);
      return;
    }
    setPickedTargets((prev) =>
      prev.includes(refId)
        ? prev.filter((x) => x !== refId)
        : prev.length >= cap
          ? prev
          : [...prev, refId]
    );
  }

  /** Commit a multi-target cast once the player has picked their targets. */
  function handleConfirmCast() {
    if (!castingSpell || pickedTargets.length === 0) return;
    handleCombatCast(castingSpell.name, castingSpell.level, pickedTargets);
    setCastingSpell(null);
    setPickedTargets([]);
  }

  function handleCancelCast() {
    setCastingSpell(null);
    setPickedTargets([]);
  }

  // Abandon any in-flight AoE placement / target selection when the turn passes or combat ends.
  useEffect(() => {
    if (!combatIsMyTurn) {
      setPlacingSpell(null);
      setCastingSpell(null);
      setPickedTargets([]);
    }
  }, [combatIsMyTurn]);

  // Escape cancels an in-flight spell cast / AoE placement.
  useEffect(() => {
    if (!castingSpell && !placingSpell) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        setCastingSpell(null);
        setPickedTargets([]);
        setPlacingSpell(null);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [castingSpell, placingSpell]);

  return {
    placingSpell,
    castingSpell,
    pickedTargets,
    handleBeginCast,
    handleCancelAoe,
    handleCastAoe,
    handleSelectTarget,
    handleConfirmCast,
    handleCancelCast,
  };
}
