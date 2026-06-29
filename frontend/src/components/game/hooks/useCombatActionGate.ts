"use client";

import { useCallback, useState } from "react";
import type { SpellSummary } from "@/types";
import { useSessionStore } from "@/store/sessionStore";
import { useCombatSpells } from "@/hooks/useCombatReference";

interface CombatCastPayload {
  spellName: string;
  spellLevel: number;
  targetIds: string[];
  originX?: number;
  originY?: number;
}

/** A player combat action held back from the server until they roll / confirm. */
export interface PendingCombatAction {
  kind: "attack" | "cast" | "useItem";
  /** Headline shown in the roll prompt, e.g. "Attack Baboon" / "Cast Fire Bolt". */
  label: string;
  /** Resolved target names for the prompt subtext. */
  targetNames: string[];
  /** True → the player rolls a d20 (weapon / attack-roll spell); false → "Cast"/"Use". */
  isAttackRoll: boolean;
  /** Fire the real WebSocket send. */
  send: () => void;
}

interface GateActions {
  combatAttack: (enemyId: string) => void;
  combatCast: (payload: CombatCastPayload) => void;
  combatUseItem: (itemName: string) => void;
}

/** A damaging single-target attack-roll spell (no save) — the player rolls a d20 for it. */
function isAttackRollSpell(spell: SpellSummary | undefined): boolean {
  return (
    !!spell &&
    !spell.saveAbility &&
    spell.effectType === "DAMAGE" &&
    (spell.targetType === "ENEMY" || spell.targetType === "ANY")
  );
}

/**
 * Holds the player's combat action back from the server until they roll/confirm, so DM
 * narration only begins after the roll. Every send site (attack, cast, AoE, SELF, use-item)
 * is routed through here: instead of firing the WS immediately, it stashes a
 * {@link PendingCombatAction} (carrying the real `send` thunk) that the CombatActionModal
 * surfaces as a "Roll d20" / "Cast" / "Use" gate. `rollPending` runs the stashed send.
 *
 * Gating only applies on the player's own turn; off-turn calls fall through to a direct send
 * (defensive — the UI already blocks off-turn actions).
 */
export function useCombatActionGate({
  actions,
  playerId,
}: {
  actions: GateActions;
  playerId: string | null;
}) {
  const [pendingAction, setPendingAction] = useState<PendingCombatAction | null>(
    null
  );

  const combat = useSessionStore((s) => s.combat);
  const spells = useCombatSpells(combat?.status === "ACTIVE").data ?? [];

  const isMyTurn =
    combat?.status === "ACTIVE" &&
    combat.active?.kind === "PLAYER" &&
    combat.active.refId === playerId;

  // refId → display name, across every combatant (players + enemies are all in `order`).
  const nameOf = useCallback(
    (refId: string) =>
      combat?.order.find((c) => c.refId === refId)?.name ?? refId,
    [combat]
  );

  const gateAttack = useCallback(
    (enemyId: string) => {
      if (!isMyTurn) return actions.combatAttack(enemyId);
      const name = nameOf(enemyId);
      setPendingAction({
        kind: "attack",
        label: `Attack ${name}`,
        targetNames: [name],
        isAttackRoll: true,
        send: () => actions.combatAttack(enemyId),
      });
    },
    [actions, isMyTurn, nameOf]
  );

  const gateCast = useCallback(
    (payload: CombatCastPayload) => {
      if (!isMyTurn) return actions.combatCast(payload);
      const spell = spells.find((s) => s.name === payload.spellName);
      const targetNames = payload.targetIds.map(nameOf);
      setPendingAction({
        kind: "cast",
        label: `Cast ${payload.spellName}`,
        targetNames,
        isAttackRoll: isAttackRollSpell(spell),
        send: () => actions.combatCast(payload),
      });
    },
    [actions, isMyTurn, nameOf, spells]
  );

  const gateUseItem = useCallback(
    (itemName: string) => {
      if (!isMyTurn) return actions.combatUseItem(itemName);
      setPendingAction({
        kind: "useItem",
        label: `Use ${itemName}`,
        targetNames: [],
        isAttackRoll: false,
        send: () => actions.combatUseItem(itemName),
      });
    },
    [actions, isMyTurn]
  );

  const rollPending = useCallback(() => {
    setPendingAction((prev) => {
      prev?.send();
      return null;
    });
  }, []);

  const cancelPending = useCallback(() => setPendingAction(null), []);

  return {
    pendingAction,
    gateAttack,
    gateCast,
    gateUseItem,
    rollPending,
    cancelPending,
  };
}
