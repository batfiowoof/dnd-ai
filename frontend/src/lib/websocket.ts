import { Client, IFrame, IMessage } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { EquipSlot, ItemKind, ItemSubtype, RollMode, TravelPace } from "@/types";

const WS_URL = "http://localhost:8080/ws";

export function createStompClient(
  token: string,
  onConnect: () => void,
  onError?: (frame: IFrame) => void
): Client {
  const client = new Client({
    webSocketFactory: () => new SockJS(WS_URL) as unknown as WebSocket,
    connectHeaders: {
      Authorization: `Bearer ${token}`,
    },
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect,
    onStompError:
      onError ??
      ((frame) => {
        console.error("STOMP error:", frame.headers["message"], frame.body);
      }),
  });

  client.activate();
  return client;
}

export function subscribeToSession(
  client: Client,
  sessionId: string,
  onMessage: (msg: unknown) => void
) {
  return client.subscribe(`/topic/game/${sessionId}`, (message: IMessage) => {
    try {
      onMessage(JSON.parse(message.body));
    } catch {
      console.error("Failed to parse WS message:", message.body);
    }
  });
}

export function subscribeToDm(
  client: Client,
  sessionId: string,
  onMessage: (msg: unknown) => void
) {
  return client.subscribe(
    `/topic/game/${sessionId}/dm`,
    (message: IMessage) => {
      try {
        onMessage(JSON.parse(message.body));
      } catch {
        console.error("Failed to parse DM message:", message.body);
      }
    }
  );
}

export function subscribeToErrors(
  client: Client,
  onError: (error: string) => void
) {
  return client.subscribe("/user/queue/errors", (message: IMessage) => {
    try {
      // Backend sends a sanitized `WsError` ({ code, message }); `error` is the legacy key.
      const data = JSON.parse(message.body);
      onError(data.message ?? data.error ?? GENERIC_WS_ERROR);
    } catch {
      // Never surface a raw, non-JSON frame to the user.
      console.error("Failed to parse WS error frame:", message.body);
      onError(GENERIC_WS_ERROR);
    }
  });
}

const GENERIC_WS_ERROR = "Something went wrong. Please try again.";

export function sendAction(client: Client, sessionId: string, action: string) {
  client.publish({
    destination: `/app/game/${sessionId}/action`,
    body: JSON.stringify({ action }),
  });
}

/**
 * Set out to a route-connected location at the given pace (out-of-combat travel). Pass
 * {@code destinationSubregion} for a local hop between subregions within the current region.
 */
export function sendTravel(
  client: Client,
  sessionId: string,
  payload: { destinationRegion?: string; destinationSubregion?: string; pace: TravelPace }
) {
  client.publish({
    destination: `/app/game/${sessionId}/travel`,
    body: JSON.stringify(payload),
  });
}

/** Pass for the current collaborative round (no action). */
export function sendPass(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/pass`,
    body: JSON.stringify({}),
  });
}

export function sendRoll(
  client: Client,
  sessionId: string,
  payload: { label: string; notation: string; mode?: RollMode }
) {
  client.publish({
    destination: `/app/game/${sessionId}/roll`,
    body: JSON.stringify({ mode: "NORMAL", ...payload }),
  });
}

export function sendCast(
  client: Client,
  sessionId: string,
  payload: {
    spellLevel: number;
    spellName?: string;
    attackNotation?: string;
    ritual?: boolean;
  }
) {
  client.publish({
    destination: `/app/game/${sessionId}/cast`,
    body: JSON.stringify(payload),
  });
}

/** Set the caster's prepared leveled spells (a subset of their known spells). */
export function sendPrepareSpells(
  client: Client,
  sessionId: string,
  spells: string[]
) {
  client.publish({
    destination: `/app/game/${sessionId}/prepare`,
    body: JSON.stringify({ spells }),
  });
}

export function sendUseItem(client: Client, sessionId: string, itemName: string) {
  client.publish({
    destination: `/app/game/${sessionId}/use-item`,
    body: JSON.stringify({ itemName }),
  });
}

export function sendHpChange(client: Client, sessionId: string, amount: number) {
  client.publish({
    destination: `/app/game/${sessionId}/hp`,
    body: JSON.stringify({ amount }),
  });
}

/* ── Inventory management & rest ──────────────────────────────── */

export function sendAddItem(
  client: Client,
  sessionId: string,
  payload: { name: string; qty: number; kind: ItemKind; subtype?: ItemSubtype | null }
) {
  client.publish({
    destination: `/app/game/${sessionId}/inventory/add`,
    body: JSON.stringify(payload),
  });
}

export function sendDropItem(client: Client, sessionId: string, name: string) {
  client.publish({
    destination: `/app/game/${sessionId}/inventory/drop`,
    body: JSON.stringify({ name }),
  });
}

export function sendEquipItem(
  client: Client,
  sessionId: string,
  name: string,
  slot: EquipSlot | null
) {
  client.publish({
    destination: `/app/game/${sessionId}/inventory/equip`,
    body: JSON.stringify({ name, slot }),
  });
}

export function sendAttuneItem(client: Client, sessionId: string, name: string) {
  client.publish({
    destination: `/app/game/${sessionId}/attunement/attune`,
    body: JSON.stringify({ name }),
  });
}

export function sendEndAttunement(client: Client, sessionId: string, name: string) {
  client.publish({
    destination: `/app/game/${sessionId}/attunement/end`,
    body: JSON.stringify({ name }),
  });
}

export function sendShopBuy(
  client: Client,
  sessionId: string,
  payload: { shopKey: string; itemRef: string; qty: number }
) {
  client.publish({
    destination: `/app/game/${sessionId}/shop/buy`,
    body: JSON.stringify(payload),
  });
}

export function sendShopSell(
  client: Client,
  sessionId: string,
  payload: { shopKey: string; name: string; qty: number }
) {
  client.publish({
    destination: `/app/game/${sessionId}/shop/sell`,
    body: JSON.stringify(payload),
  });
}

export function sendLongRest(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/rest`,
    body: JSON.stringify({}),
  });
}

export function sendShortRest(client: Client, sessionId: string, hitDice: number) {
  client.publish({
    destination: `/app/game/${sessionId}/short-rest`,
    body: JSON.stringify({ hitDice }),
  });
}

/* ── Combat ───────────────────────────────────────────────────── */

/** @param lair fight the monster in its lair, enabling lair actions on initiative count 20 */
export function sendStartEncounter(
  client: Client,
  sessionId: string,
  enemies: string[],
  lair = false
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/start`,
    body: JSON.stringify({ enemies, lair }),
  });
}

export function sendCombatAttack(
  client: Client,
  sessionId: string,
  targetEnemyId: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/attack`,
    body: JSON.stringify({ targetEnemyId }),
  });
}

/** Phase 2 of an attack or damaging spell: roll the held damage (server resolves from the pending). */
export function sendCombatResolveDamage(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/resolve-damage`,
    body: JSON.stringify({}),
  });
}

export function sendCombatUseItem(
  client: Client,
  sessionId: string,
  itemName: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/use-item`,
    body: JSON.stringify({ itemName }),
  });
}

export function sendCombatCast(
  client: Client,
  sessionId: string,
  payload: {
    spellName: string;
    spellLevel: number;
    targetIds: string[];
    /** AoE origin cell — sent for area spells so the server computes the hit set. */
    originX?: number;
    originY?: number;
  }
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/cast`,
    body: JSON.stringify(payload),
  });
}

/* ── Tactical movement & defensive actions (Phase B) ──────────────
   None advance the turn; each triggers a COMBAT_TURN refresh carrying
   the updated grid/tokens. */

export function sendCombatMove(
  client: Client,
  sessionId: string,
  x: number,
  y: number
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/move`,
    body: JSON.stringify({ x, y }),
  });
}

export function sendCombatDash(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/dash`,
    body: JSON.stringify({}),
  });
}

export function sendCombatDisengage(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/disengage`,
    body: JSON.stringify({}),
  });
}

export function sendCombatDodge(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/dodge`,
    body: JSON.stringify({}),
  });
}

export function sendCombatEndTurn(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/end-turn`,
    body: JSON.stringify({}),
  });
}

/* ── Bonus actions (Phase B): off-hand attack + class abilities ──── */

/** Off-hand (two-weapon) attack — spends the bonus action, resolves in one shot. */
export function sendCombatOffHandAttack(
  client: Client,
  sessionId: string,
  targetEnemyId: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/bonus/off-hand-attack`,
    body: JSON.stringify({ targetEnemyId }),
  });
}

/** Second Wind (Fighter): heal 1d10 + level as a bonus action. */
export function sendCombatSecondWind(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/bonus/second-wind`,
    body: JSON.stringify({}),
  });
}

/** Cunning Action (Rogue): dash / disengage / hide as a bonus action. */
export function sendCombatCunningAction(
  client: Client,
  sessionId: string,
  action: "dash" | "disengage" | "hide"
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/bonus/cunning-action`,
    body: JSON.stringify({ action }),
  });
}

/** Spend your action to stabilize a dying ally (DC 10 Medicine check, server-rolled). */
export function sendCombatStabilize(
  client: Client,
  sessionId: string,
  targetPlayerId: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/stabilize`,
    body: JSON.stringify({ targetPlayerId }),
  });
}

export function sendEndCombat(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/end`,
    body: JSON.stringify({}),
  });
}

/* ── Reactions (Feature 4): reaction spells, hold, ready ──────────── */

/** Answer a reaction prompt: "SHIELD" | "ABSORB" | "DECLINE". */
export function sendCombatReaction(
  client: Client,
  sessionId: string,
  choice: "SHIELD" | "ABSORB" | "DECLINE",
  promptId?: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/reaction`,
    body: JSON.stringify({ choice, promptId }),
  });
}

/** Toggle holding your reaction for a spell (suppresses auto opportunity attacks). */
export function sendCombatHoldReaction(
  client: Client,
  sessionId: string,
  hold: boolean
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/reaction/hold`,
    body: JSON.stringify({ hold }),
  });
}

/** Ready an attack against an enemy — fires as a reaction when it enters your reach. */
export function sendCombatReady(
  client: Client,
  sessionId: string,
  targetEnemyId: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/ready`,
    body: JSON.stringify({ targetEnemyId }),
  });
}

/** Subscribe to this player's private reaction prompts (pushed only to the targeted player). */
export function subscribeToReactions(
  client: Client,
  onPrompt: (msg: unknown) => void
) {
  return client.subscribe("/user/queue/reaction", (message: IMessage) => {
    try {
      onPrompt(JSON.parse(message.body));
    } catch {
      console.error("Failed to parse reaction prompt frame:", message.body);
    }
  });
}

/* ── Reroll (Features 5 & 6): Heroic Inspiration / Lucky reroll ────── */

/** Answer a reroll prompt: spend "INSPIRATION" / "LUCK", or "KEEP" the original roll. */
export function sendReroll(
  client: Client,
  sessionId: string,
  resource: "INSPIRATION" | "LUCK" | "KEEP",
  promptId?: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/roll/reroll`,
    body: JSON.stringify({ resource, promptId }),
  });
}

/** Subscribe to this player's private reroll prompts (pushed only to the rolling player). */
export function subscribeToReroll(
  client: Client,
  onPrompt: (msg: unknown) => void
) {
  return client.subscribe("/user/queue/reroll", (message: IMessage) => {
    try {
      onPrompt(JSON.parse(message.body));
    } catch {
      console.error("Failed to parse reroll prompt frame:", message.body);
    }
  });
}
