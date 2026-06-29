"use client";

import { useEffect, useRef } from "react";
import type { Client } from "@stomp/stompjs";
import {
  createStompClient,
  subscribeToSession,
  subscribeToErrors,
} from "@/lib/websocket";
import { useSessionStore } from "@/store/sessionStore";
import { playSound } from "@/lib/sound";
import type {
  DmResponseDto,
  GameStateDto,
  DiceRollEvent,
  PlayerStateEvent,
  CombatActionEvent,
  CombatLifecycleEvent,
  RoundStatusEvent,
} from "@/types";

interface UseGameSocketArgs {
  sessionId: string;
  username: string | null | undefined;
  getToken: () => Promise<string | null>;
  /** Scroll the chat to the bottom after a message that adds visible content. */
  scrollToBottom: () => void;
  /** Surface a server error frame (already sanitized by the backend `WsError`). */
  onError: (message: string) => void;
}

/**
 * Owns the STOMP lifecycle for a game session: connect, subscribe to the session topic + the
 * per-user error queue, route every inbound message to the Zustand store, and tear down on unmount.
 * Returns a ref to the live client so callers (see `useGameActions`) can publish. Previously this
 * lived as a ~140-line effect inside the lobby page.
 */
export function useGameSocket({
  sessionId,
  username,
  getToken,
  scrollToBottom,
  onError,
}: UseGameSocketArgs) {
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    if (!username) return;

    let client: Client | null = null;
    let cancelled = false;

    getToken().then((token) => {
      if (cancelled || !token) return;

      client = createStompClient(
        token,
        () => {
          useSessionStore.getState().setConnected(true);
          subscribeToSession(client!, sessionId, (msg) =>
            dispatchMessage(msg, scrollToBottom)
          );
          subscribeToErrors(client!, onError);
        },
        () => {
          useSessionStore.getState().setConnected(false);
        }
      );

      clientRef.current = client;
    });

    return () => {
      cancelled = true;
      if (client) client.deactivate();
      clientRef.current = null;
    };
  }, [sessionId, username, getToken, scrollToBottom, onError]);

  return clientRef;
}

/**
 * Routes a raw inbound WebSocket message to the matching store action. The canonical DM response is
 * untyped (no `type`, carries `dmNarration`); everything else is discriminated on `type`.
 */
function dispatchMessage(msg: unknown, scrollToBottom: () => void) {
  const data = msg as Record<string, unknown>;
  const s = useSessionStore.getState();
  const type = data.type as string | undefined;

  // Canonical DM response — untyped, carries dmNarration + nextTurnPlayerId.
  // Checked before the switch (DM_NARRATION also has a dmNarration field).
  if (!type && "dmNarration" in data) {
    const dm = data as unknown as DmResponseDto;
    const playerName = data.playerName as string | undefined;
    s.applyDmResponse(dm, playerName);
    scrollToBottom();
    return;
  }

  switch (type) {
    case "DM_THINKING":
      s.beginDmTurn({
        turnNumber: Number(data.turnNumber),
        playerId: String(data.playerId ?? "dm"),
        playerName: data.playerName as string | undefined,
        action: data.action as string | undefined,
      });
      scrollToBottom();
      break;
    case "DM_CHUNK":
      s.appendDmChunk({
        turnNumber: Number(data.turnNumber),
        playerId: String(data.playerId),
        delta: String(data.delta),
      });
      scrollToBottom();
      break;
    case "DM_NARRATION":
      s.applyDmNarration({
        turnNumber: Number(data.turnNumber),
        playerId: String(data.playerId),
        dmNarration: String(data.dmNarration),
      });
      scrollToBottom();
      break;
    case "TURN_CHANGE":
      s.setTurnChange(data.nextPlayerId as string, Number(data.turnNumber));
      playSound("turn");
      break;
    case "PLAYER_JOINED":
    case "PLAYER_LEFT": {
      const gs = data.gameState as GameStateDto | undefined;
      if (gs) s.applyPlayerEvent(gs);
      break;
    }
    case "GAME_STARTED": {
      const gs = data.gameState as GameStateDto | undefined;
      if (gs) {
        s.applyGameStarted(gs);
        scrollToBottom();
      }
      break;
    }
    case "GAME_ENDED":
      s.applyGameEnded();
      scrollToBottom();
      break;
    case "DICE_ROLL":
      s.applyDiceRoll(data as unknown as DiceRollEvent);
      scrollToBottom();
      break;
    case "PLAYER_STATE":
      s.applyPlayerState((data as unknown as PlayerStateEvent).state);
      break;
    case "COMBAT_START":
    case "COMBAT_TURN":
    case "COMBAT_END":
      s.applyCombatLifecycle(data as unknown as CombatLifecycleEvent);
      if (type === "COMBAT_START") playSound("combatStart");
      else if (type === "COMBAT_TURN") playSound("turn");
      else playSound(data.victory ? "victory" : "defeat");
      scrollToBottom();
      break;
    case "COMBAT_ACTION":
      s.applyCombatAction(data as unknown as CombatActionEvent);
      break;
    case "ROUND_STATUS":
      s.applyRoundStatus(data as unknown as RoundStatusEvent);
      break;
    case "SYSTEM": {
      // Neutral room line (e.g. "X gains Inspiration!").
      const text = data.text as string | undefined;
      if (text) {
        s.addLog({
          id: `system-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          type: "system",
          text,
          turnNumber: s.turnNumber,
        });
        scrollToBottom();
      }
      break;
    }
  }
}
