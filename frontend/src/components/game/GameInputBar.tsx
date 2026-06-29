"use client";

import { useEffect, useState } from "react";
import { useSessionStore } from "@/store/sessionStore";
import { Button, D20Mark } from "@/components/ui";
import QuickRollBar from "@/components/dice/QuickRollBar";
import ActionBar from "@/components/game/ActionBar";

interface GameInputBarProps {
  /** The local player's id (from session storage), or null. */
  playerId: string | null;
  actionText: string;
  setActionText: (text: string) => void;
  spendInspiration: boolean;
  setSpendInspiration: (v: boolean) => void;
  /** Dispatch the current action (page owns the WS send + field clearing + scroll). */
  onSend: () => void;
  onPass: () => void;
  onRoll: (notation: string, label: string) => void;
  onAttack: () => void;
  onCast: (spellLevel: number, spellName?: string) => void;
  onUseItem: (itemName: string) => void;
  onLongRest: () => void;
  onManage: () => void;
}

/**
 * The narrative input bar (ACTIVE sessions, out of combat): the out-of-combat ActionBar +
 * QuickRollBar, the collaborative round-collection indicator + Pass, the action textarea
 * with mode-aware placeholder, the Inspiration pre-arm checkbox, and the "it's your turn"
 * hint. Subscribes to the store for turn/combat state; owns the collaborative countdown.
 */
export default function GameInputBar({
  playerId,
  actionText,
  setActionText,
  spendInspiration,
  setSpendInspiration,
  onSend,
  onPass,
  onRoll,
  onAttack,
  onCast,
  onUseItem,
  onLongRest,
  onManage,
}: GameInputBarProps) {
  const status = useSessionStore((s) => s.status);
  const connected = useSessionStore((s) => s.connected);
  const dmThinking = useSessionStore((s) => s.dmThinking);
  const turnMode = useSessionStore((s) => s.turnMode);
  const round = useSessionStore((s) => s.round);
  const currentTurnPlayerId = useSessionStore((s) => s.currentTurnPlayerId);
  const runtimeByPlayerId = useSessionStore((s) => s.runtimeByPlayerId);
  const combat = useSessionStore((s) => s.combat);

  const inCombat = combat?.status === "ACTIVE";
  const isMyTurn = currentTurnPlayerId === playerId;
  const myState = playerId ? runtimeByPlayerId[playerId] ?? null : null;

  // Narrative input permission, branched by mode (combat uses combat actions).
  const canType =
    status === "ACTIVE" &&
    connected &&
    !inCombat &&
    !dmThinking &&
    (turnMode === "INITIATIVE" ? isMyTurn : true);

  // Local countdown ticker for the collaborative round window.
  const [roundSeconds, setRoundSeconds] = useState(0);
  useEffect(() => {
    if (!round?.open) {
      setRoundSeconds(0);
      return;
    }
    setRoundSeconds(round.secondsLeft);
    const id = setInterval(
      () => setRoundSeconds((s) => Math.max(0, s - 1)),
      1000
    );
    return () => clearInterval(id);
  }, [round?.open, round?.secondsLeft]);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    // Gating is mode-aware (canType): initiative requires your turn; collaborative
    // and freeform let you act now. The page's onSend also guards on the live client.
    if (!actionText.trim() || !canType) return;
    onSend();
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="border-t border-border bg-surface/80 p-4 backdrop-blur-sm"
    >
      <div className="mb-2 space-y-2">
        {!inCombat && (
          <ActionBar
            state={myState}
            isMyTurn={canType}
            connected={connected}
            onAttack={onAttack}
            onCast={onCast}
            onUseItem={onUseItem}
            onLongRest={onLongRest}
            onManage={onManage}
          />
        )}
        <QuickRollBar onRoll={onRoll} disabled={!connected || inCombat} />
      </div>

      {/* Collaborative round-collection indicator + Pass */}
      {turnMode === "COLLABORATIVE" && !inCombat && round?.open && (
        <div className="mb-2 flex items-center justify-between gap-3 rounded-lg border border-gold/40 bg-gold-muted px-3 py-2 text-xs">
          <span className="flex items-center gap-2 text-gold">
            <D20Mark className="h-3.5 w-3.5 animate-spin" />
            <span className="font-medium">Round collecting…</span>
            <span className="tabular text-text-muted">
              {round.submitted}/{round.total} submitted &middot;{" "}
              {roundSeconds}s
            </span>
          </span>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={!connected}
            onClick={onPass}
          >
            Pass
          </Button>
        </div>
      )}

      <div className="flex gap-2">
        <input
          type="text"
          value={actionText}
          onChange={(e) => setActionText(e.target.value)}
          placeholder={
            inCombat
              ? "In combat — use combat actions above"
              : turnMode === "INITIATIVE" && !isMyTurn
                  ? "Waiting for your turn..."
                  : dmThinking
                    ? "The DM is responding…"
                    : turnMode === "COLLABORATIVE"
                      ? "Describe your action for this round..."
                      : "Describe your action..."
          }
          disabled={!canType}
          className="flex-1 rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent disabled:opacity-40"
        />
        <Button
          type="submit"
          disabled={!canType || !actionText.trim()}
          size="lg"
        >
          {turnMode === "COLLABORATIVE" && round?.open ? "Add" : "Send"}
        </Button>
      </div>
      {/* Inspiration is now pre-armed before acting (the DM auto-rolls any check inline). */}
      {myState?.inspiration && (
        <label className="mt-1.5 inline-flex cursor-pointer items-center gap-1.5 text-xs text-gold">
          <input
            type="checkbox"
            checked={spendInspiration}
            onChange={(e) => setSpendInspiration(e.target.checked)}
            disabled={!canType}
            className="accent-gold"
          />
          Spend Inspiration on this action (advantage on any check)
        </label>
      )}
      {turnMode === "INITIATIVE" && isMyTurn && !inCombat && (
        <p className="mt-1.5 text-xs font-medium text-gold">
          It&apos;s your turn!
        </p>
      )}
    </form>
  );
}
