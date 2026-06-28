import { Client, IFrame, IMessage } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { ItemKind, RollMode } from "@/types";

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
      const data = JSON.parse(message.body);
      onError(data.error ?? "Unknown error");
    } catch {
      onError(message.body);
    }
  });
}

export function sendAction(
  client: Client,
  sessionId: string,
  action: string
) {
  client.publish({
    destination: `/app/game/${sessionId}/action`,
    body: JSON.stringify({ action }),
  });
}

/** Pass for the current collaborative round (no action). */
export function sendPass(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/pass`,
    body: JSON.stringify({}),
  });
}

/**
 * Resolve the DM-requested ability check pending for the player. The player's only roll-mode
 * lever is spending Inspiration (grants advantage) — the DM decides situational advantage /
 * disadvantage. The backend rolls authoritatively and narrates the outcome.
 */
export function sendRollCheck(
  client: Client,
  sessionId: string,
  spendInspiration: boolean
) {
  client.publish({
    destination: `/app/game/${sessionId}/roll-check`,
    body: JSON.stringify({ spendInspiration }),
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
  payload: { spellLevel: number; spellName?: string; attackNotation?: string }
) {
  client.publish({
    destination: `/app/game/${sessionId}/cast`,
    body: JSON.stringify(payload),
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
  payload: { name: string; qty: number; kind: ItemKind }
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
  equipped: boolean
) {
  client.publish({
    destination: `/app/game/${sessionId}/inventory/equip`,
    body: JSON.stringify({ name, equipped }),
  });
}

export function sendLongRest(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/rest`,
    body: JSON.stringify({}),
  });
}

/* ── Combat ───────────────────────────────────────────────────── */

export function sendStartEncounter(
  client: Client,
  sessionId: string,
  enemies: string[]
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/start`,
    body: JSON.stringify({ enemies }),
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
  payload: { spellName: string; spellLevel: number; targetIds: string[] }
) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/cast`,
    body: JSON.stringify(payload),
  });
}

export function sendCombatEndTurn(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/end-turn`,
    body: JSON.stringify({}),
  });
}

export function sendEndCombat(client: Client, sessionId: string) {
  client.publish({
    destination: `/app/game/${sessionId}/combat/end`,
    body: JSON.stringify({}),
  });
}
