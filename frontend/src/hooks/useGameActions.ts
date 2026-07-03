"use client";

import { useMemo } from "react";
import type { RefObject } from "react";
import type { Client } from "@stomp/stompjs";
import type { EquipSlot, ItemKind, ItemSubtype, TravelPace } from "@/types";
import {
  sendTravel,
  sendPass,
  sendRoll,
  sendCast,
  sendUseItem,
  sendAddItem,
  sendDropItem,
  sendEquipItem,
  sendShopBuy,
  sendShopSell,
  sendLongRest,
  sendShortRest,
  sendCombatAttack,
  sendCombatResolveDamage,
  sendCombatUseItem,
  sendCombatCast,
  sendCombatEndTurn,
  sendCombatMove,
  sendCombatDash,
  sendCombatDisengage,
  sendCombatDodge,
  sendCombatOffHandAttack,
  sendCombatSecondWind,
  sendCombatCunningAction,
  sendCombatStabilize,
  sendEndCombat,
} from "@/lib/websocket";

interface CombatCastPayload {
  spellName: string;
  spellLevel: number;
  targetIds: string[];
  originX?: number;
  originY?: number;
}

/**
 * Bundles the WebSocket "send" actions for a session into one set of guarded callbacks. Every action
 * is a no-op unless the client is connected, collapsing the ~20 near-identical
 * `if (!clientRef.current || !connected) return;` handlers the lobby page used to repeat by hand.
 */
export function useGameActions(
  clientRef: RefObject<Client | null>,
  sessionId: string,
  connected: boolean
) {
  return useMemo(() => {
    // Generic guarded runner: drops the call if we're not connected.
    const run =
      <A extends unknown[]>(fn: (client: Client, ...args: A) => void) =>
      (...args: A) => {
        const client = clientRef.current;
        if (!client || !connected) return;
        fn(client, ...args);
      };

    return {
      travel: run((c, destinationRegion: string, pace: TravelPace) =>
        sendTravel(c, sessionId, { destinationRegion, pace })
      ),
      travelLocal: run((c, destinationSubregion: string, pace: TravelPace) =>
        sendTravel(c, sessionId, { destinationSubregion, pace })
      ),
      pass: run((c) => sendPass(c, sessionId)),
      roll: run((c, notation: string, label: string) =>
        sendRoll(c, sessionId, { label, notation })
      ),
      attack: run((c) =>
        sendRoll(c, sessionId, { label: "Attack", notation: "1d20" })
      ),
      cast: run((c, spellLevel: number, spellName?: string) =>
        sendCast(c, sessionId, { spellLevel, spellName })
      ),
      useItem: run((c, itemName: string) =>
        sendUseItem(c, sessionId, itemName)
      ),
      addItem: run(
        (c, item: { name: string; qty: number; kind: ItemKind; subtype?: ItemSubtype | null }) =>
          sendAddItem(c, sessionId, item)
      ),
      dropItem: run((c, itemName: string) =>
        sendDropItem(c, sessionId, itemName)
      ),
      equipItem: run((c, itemName: string, slot: EquipSlot | null) =>
        sendEquipItem(c, sessionId, itemName, slot)
      ),
      shopBuy: run((c, payload: { shopKey: string; itemRef: string; qty: number }) =>
        sendShopBuy(c, sessionId, payload)
      ),
      shopSell: run((c, payload: { shopKey: string; name: string; qty: number }) =>
        sendShopSell(c, sessionId, payload)
      ),
      longRest: run((c) => sendLongRest(c, sessionId)),
      shortRest: run((c, hitDice: number) => sendShortRest(c, sessionId, hitDice)),
      combatAttack: run((c, enemyId: string) =>
        sendCombatAttack(c, sessionId, enemyId)
      ),
      combatResolveDamage: run((c) => sendCombatResolveDamage(c, sessionId)),
      combatUseItem: run((c, itemName: string) =>
        sendCombatUseItem(c, sessionId, itemName)
      ),
      combatCast: run((c, payload: CombatCastPayload) =>
        sendCombatCast(c, sessionId, payload)
      ),
      combatEndTurn: run((c) => sendCombatEndTurn(c, sessionId)),
      combatMove: run((c, x: number, y: number) =>
        sendCombatMove(c, sessionId, x, y)
      ),
      combatDash: run((c) => sendCombatDash(c, sessionId)),
      combatDisengage: run((c) => sendCombatDisengage(c, sessionId)),
      combatDodge: run((c) => sendCombatDodge(c, sessionId)),
      combatOffHandAttack: run((c, enemyId: string) =>
        sendCombatOffHandAttack(c, sessionId, enemyId)
      ),
      combatSecondWind: run((c) => sendCombatSecondWind(c, sessionId)),
      combatCunningAction: run((c, action: "dash" | "disengage" | "hide") =>
        sendCombatCunningAction(c, sessionId, action)
      ),
      combatStabilize: run((c, targetPlayerId: string) =>
        sendCombatStabilize(c, sessionId, targetPlayerId)
      ),
      endCombat: run((c) => sendEndCombat(c, sessionId)),
    };
  }, [clientRef, sessionId, connected]);
}
