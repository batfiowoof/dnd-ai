"use client";

import { useEffect, useState, useRef, useCallback, use } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import {
  useStartSession,
  useKickPlayer,
  useEndSession,
} from "@/hooks/useSessionQueries";
import { useSessionStore } from "@/store/sessionStore";
import { sendAction, sendStartEncounter } from "@/lib/websocket";
import { useGameSocket } from "@/hooks/useGameSocket";
import { useGameActions } from "@/hooks/useGameActions";
import { useTravelMap } from "@/hooks/useTravelQueries";
import type { TravelPace } from "@/types";
import { getErrorMessage } from "@/lib/errors";
import {
  getPlayerId,
  getJoinCode,
  forgetSession,
} from "@/lib/sessionStorage";
import { Button, ConfirmDialog, useToast } from "@/components/ui";
import DiceRollModal from "@/components/dice/DiceRollModal";
import CharacterSheetDialog from "@/components/game/CharacterSheetDialog";
import InventoryManager from "@/components/game/InventoryManager";
import CombatActionModal from "@/components/combat/CombatActionModal";
import { uploadCombatMap, leaveSession } from "@/lib/api";
import { useLobbyData } from "@/components/game/hooks/useLobbyData";
import { useCombatInteraction } from "@/components/game/hooks/useCombatInteraction";
import { useCombatActionGate } from "@/components/game/hooks/useCombatActionGate";
import LobbyWaitingView from "@/components/game/LobbyWaitingView";
import GameRoomHeader from "@/components/game/GameRoomHeader";
import PlayersSidebar from "@/components/game/PlayersSidebar";
import CombatRegion from "@/components/game/CombatRegion";
import BattlefieldChatSplit from "@/components/game/BattlefieldChatSplit";
import TravelPanel from "@/components/travel/TravelPanel";
import GameLog from "@/components/game/GameLog";
import GameInputBar from "@/components/game/GameInputBar";

/* ════════════════════════════════════════════════════════════════
   Combined Lobby + Game page
   ─ WAITING  → lobby view  (join code, player list, start btn)
   ─ ACTIVE   → chat room   (AI Dungeon Master game)
   ─ FINISHED → read-only chat with "game over" banner
   ════════════════════════════════════════════════════════════════ */
export default function LobbyPage({
  params,
}: {
  params: Promise<{ sessionId: string }>;
}) {
  const { sessionId } = use(params);
  return (
    <RequireAuth>
      <LobbyContent sessionId={sessionId} />
    </RequireAuth>
  );
}

function LobbyContent({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const { username, getToken } = useAuth();
  const toast = useToast();

  /* ── live game state (Zustand — seeded by React Query + WebSocket) ──
     Sub-components subscribe to the store directly; the page reads only what it
     needs to orchestrate identity, modals, and the WAITING↔game switch. */
  const status = useSessionStore((s) => s.status);
  const players = useSessionStore((s) => s.players);
  const createdBy = useSessionStore((s) => s.createdBy);
  const connected = useSessionStore((s) => s.connected);
  const runtimeByPlayerId = useSessionStore((s) => s.runtimeByPlayerId);
  const setMyPlayerId = useSessionStore((s) => s.setMyPlayerId);
  const setCombatInitializing = useSessionStore((s) => s.setCombatInitializing);
  const hydrateFromGameState = useSessionStore((s) => s.hydrateFromGameState);
  const recap = useSessionStore((s) => s.recap);
  const recapPending = useSessionStore((s) => s.recapPending);

  /* ── purely-local UI state ──────────────────────────────────── */
  const [copied, setCopied] = useState(false);
  const [actionText, setActionText] = useState("");
  /** Pre-arm Inspiration for any check the AI DM rolls this turn (auto-rolls happen inline). */
  const [spendInspiration, setSpendInspiration] = useState(false);
  const [manageOpen, setManageOpen] = useState(false);
  const [sheetPlayerId, setSheetPlayerId] = useState<string | null>(null);
  const [leaveConfirm, setLeaveConfirm] = useState(false);
  const [endConfirm, setEndConfirm] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  /* ── mutations ──────────────────────────────────────────────── */
  const startMutation = useStartSession();
  const kickMutation = useKickPlayer();
  const endMutation = useEndSession();
  const loading = startMutation.isPending;

  /* ── reset per-session live state on mount / session change ─── */
  useEffect(() => {
    useSessionStore.getState().reset();
    return () => useSessionStore.getState().reset();
  }, [sessionId]);

  const playerId = getPlayerId(sessionId);
  const joinCode = getJoinCode(sessionId);
  const isCreator = username === createdBy;

  // Tell the store who "I" am so it routes MY own rolls to the big centre modal
  // while enemy / NPC / other-player rolls go to the compact map-docked feed.
  useEffect(() => {
    setMyPlayerId(playerId || null);
  }, [playerId, setMyPlayerId]);

  /* ── helpers ────────────────────────────────────────────────── */
  const scrollToBottom = useCallback(() => {
    setTimeout(() => {
      scrollRef.current?.scrollTo({
        top: scrollRef.current.scrollHeight,
        behavior: "smooth",
      });
    }, 50);
  }, []);

  /* ── load initial state (React Query → seed Zustand store) ──── */
  useLobbyData(sessionId, scrollToBottom);

  /* ── WebSocket: lifecycle + dispatch live in useGameSocket; the returned
     client ref powers the guarded send actions in useGameActions. ───────── */
  const clientRef = useGameSocket({
    sessionId,
    username,
    getToken,
    scrollToBottom,
    onError: toast.error,
  });
  const actions = useGameActions(clientRef, sessionId, connected);

  /* ── travel map ─────────────────────────────────────────────────
     The location graph loads once (React Query); the party's live position + clock come from
     the store. The left pane shows the travel map out of combat, the battle grid in combat. */
  const inGame = status === "ACTIVE" || status === "FINISHED";
  const { data: travelMap } = useTravelMap(sessionId, inGame);
  const hasCombatGrid = useSessionStore(
    (s) => s.combat?.status === "ACTIVE" && !!s.combat?.grid
  );
  const travelAvailable = (travelMap?.regions.length ?? 0) > 0;

  function handleTravel(destinationRegion: string, pace: TravelPace) {
    useSessionStore.getState().beginTravel();
    actions.travel(destinationRegion, pace);
  }

  function handleTravelLocal(destinationSubregion: string, pace: TravelPace) {
    useSessionStore.getState().beginTravel();
    actions.travelLocal(destinationSubregion, pace);
  }

  /* ── lobby actions ──────────────────────────────────────────── */
  async function handleStart() {
    if (!username) return;
    try {
      const gs = await startMutation.mutateAsync(sessionId);
      hydrateFromGameState(gs);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to start session"));
    }
  }

  async function handleKick(targetPlayerId: string) {
    if (!username) return;
    try {
      // Player list refreshes via the WebSocket PLAYER_LEFT event and the
      // players-query invalidation in the mutation's onSuccess.
      await kickMutation.mutateAsync({ sessionId, playerId: targetPlayerId });
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to remove player"));
    }
  }

  function copyCode() {
    if (!joinCode) return;
    navigator.clipboard.writeText(joinCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  /* ── game actions ───────────────────────────────────────────── */
  function handleSendAction() {
    if (!clientRef.current) return;
    sendAction(clientRef.current, sessionId, actionText.trim(), spendInspiration);
    // No optimistic local entry: the server echoes the action via DM_THINKING,
    // so every client (including this one) renders it once, in order.
    setActionText("");
    setSpendInspiration(false);
    scrollToBottom();
  }

  /* ── combat handlers ────────────────────────────────────────── */
  function handleStartEncounter(enemyKeys: string[]) {
    if (!clientRef.current || !connected) return;
    // Show the "preparing the battlefield" loader until COMBAT_START arrives (the LLM
    // scene generation can take a couple seconds). Safety-clear it if nothing lands.
    setCombatInitializing(true);
    setTimeout(() => setCombatInitializing(false), 15000);
    sendStartEncounter(clientRef.current, sessionId, enemyKeys);
  }

  // Hold combat actions back from the server until the player rolls/confirms (so DM narration
  // begins after the roll). Every send site is routed through this gate.
  const gate = useCombatActionGate({ actions, playerId });

  // Cast / AoE / target-selection sub-state-machine (its own hook). Casts funnel through the
  // gate's `gateCast` so single/multi-target/AoE/SELF spells all defer until the roll prompt.
  const {
    placingSpell,
    castingSpell,
    pickedTargets,
    handleBeginCast,
    handleCancelAoe,
    handleCastAoe,
    handleSelectTarget,
    handleConfirmCast,
    handleCancelCast,
  } = useCombatInteraction({
    clientRef,
    connected,
    playerId,
    combatCast: gate.gateCast,
  });

  /** Leave the session: best-effort backend leave, clear local keys, return to /play. */
  async function handleLeaveSession() {
    setLeaveConfirm(false);
    try {
      const token = await getToken();
      if (token) await leaveSession(token, sessionId);
    } catch {
      // Ignore — navigate away regardless so the user is never stuck.
    }
    forgetSession(sessionId);
    router.push("/play");
  }

  /** Host-only: end the adventure. Status flips to FINISHED and the recap streams in via WS. */
  async function handleEndSession() {
    setEndConfirm(false);
    try {
      await endMutation.mutateAsync(sessionId);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to end the session"));
    }
  }

  /** Host-only battle-map background upload (server broadcasts the refreshed grid). */
  async function handleUploadMap(file: File) {
    const token = await getToken();
    if (!token) throw new Error("Not authenticated");
    await uploadCombatMap(token, sessionId, file);
  }

  /* ── character-sheet dialog target (any player) ─────────────── */
  const myState = playerId ? runtimeByPlayerId[playerId] ?? null : null;
  const sheetPlayer = sheetPlayerId
    ? players.find((p) => p.id === sheetPlayerId)
    : null;
  const sheetState = sheetPlayerId ? runtimeByPlayerId[sheetPlayerId] : null;

  /* Resolve a chat log entry's author (matched by character name / username /
     id) so we can show their portrait next to the line. */
  const playerByName = useCallback(
    (nameOrId?: string) =>
      players.find(
        (p) =>
          p.id === nameOrId ||
          p.characterName === nameOrId ||
          p.username === nameOrId
      ) ?? null,
    [players]
  );

  /* Shared prop bundle for the two CombatRegion slots (tracker on top, map on the left).
     Attack / use-item go through the gate so they defer until the roll prompt. */
  const combatRegionProps = {
    playerId,
    isCreator,
    castingSpell,
    pickedTargets,
    placingSpell,
    onBeginCast: handleBeginCast,
    onSelectTarget: handleSelectTarget,
    onConfirmCast: handleConfirmCast,
    onCancelCast: handleCancelCast,
    onCancelAoe: handleCancelAoe,
    onCastAoe: handleCastAoe,
    onCombatAttack: gate.gateAttack,
    onCombatUseItem: gate.gateUseItem,
    onStabilize: actions.combatStabilize,
    onEndTurn: actions.combatEndTurn,
    onEndCombat: actions.endCombat,
    onDash: actions.combatDash,
    onDisengage: actions.combatDisengage,
    onDodge: actions.combatDodge,
    onMove: actions.combatMove,
    onUploadMap: handleUploadMap,
  };

  /* ════════════════════════════════════════════════════════════
     RENDER — WAITING (lobby)
     ════════════════════════════════════════════════════════════ */
  if (status === "WAITING") {
    return (
      <LobbyWaitingView
        sessionId={sessionId}
        joinCode={joinCode}
        isCreator={isCreator}
        username={username}
        loading={loading}
        copied={copied}
        onCopy={copyCode}
        onStart={handleStart}
        onKick={handleKick}
      />
    );
  }

  /* ════════════════════════════════════════════════════════════
     RENDER — ACTIVE / FINISHED (chat room)
     ════════════════════════════════════════════════════════════ */
  return (
    <div className="flex h-dvh flex-col">
      {/* Dice + combat-action animation overlays */}
      <DiceRollModal />
      <CombatActionModal
        pendingAction={gate.pendingAction}
        onRoll={gate.rollPending}
        onCancel={gate.cancelPending}
        onRollDamage={actions.combatResolveDamage}
      />
      <InventoryManager
        open={manageOpen}
        onClose={() => setManageOpen(false)}
        state={myState}
        connected={connected}
        onDrop={actions.dropItem}
        onEquip={actions.equipItem}
        onAdd={actions.addItem}
      />
      {sheetPlayer && sheetState && (
        <CharacterSheetDialog
          open={!!sheetPlayerId}
          onClose={() => setSheetPlayerId(null)}
          state={sheetState}
          characterName={sheetPlayer.characterName}
          imageUrl={sheetPlayer.imageUrl}
        />
      )}

      <GameRoomHeader
        playerId={playerId}
        isCreator={isCreator}
        onStartEncounter={handleStartEncounter}
        onEndSession={() => setEndConfirm(true)}
        onLeave={() => setLeaveConfirm(true)}
        onOpenSheet={setSheetPlayerId}
      />

      <ConfirmDialog
        open={leaveConfirm}
        title="Leave session?"
        message="You'll return to the session picker. You can rejoin later with the invite code."
        confirmLabel="Leave"
        cancelLabel="Stay"
        tone="danger"
        onConfirm={handleLeaveSession}
        onClose={() => setLeaveConfirm(false)}
      />

      <ConfirmDialog
        open={endConfirm}
        title="End the adventure?"
        message="This ends the session for everyone and writes a recap of the story so far, which you can carry into a follow-up adventure. This can't be undone."
        confirmLabel="End Adventure"
        cancelLabel="Keep Playing"
        onConfirm={handleEndSession}
        onClose={() => setEndConfirm(false)}
      />

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar — players */}
        <PlayersSidebar onOpenSheet={setSheetPlayerId} />

        {/* Main game area — tracker spans the top; battlefield (left) + chat (right) below. */}
        <div className="flex min-h-0 flex-1 flex-col">
          <CombatRegion part="tracker" {...combatRegionProps} />

          <BattlefieldChatSplit
            alsoShowMap={travelAvailable && !hasCombatGrid}
            map={
              hasCombatGrid ? (
                <CombatRegion part="map" {...combatRegionProps} />
              ) : (
                <TravelPanel
                  map={travelMap}
                  connected={connected}
                  onTravel={handleTravel}
                  onTravelLocal={handleTravelLocal}
                />
              )
            }
            chat={
              <>
                <GameLog playerByName={playerByName} scrollRef={scrollRef} />

                {/* Input — only when ACTIVE */}
                {status === "ACTIVE" && (
                  <GameInputBar
                    playerId={playerId}
                    actionText={actionText}
                    setActionText={setActionText}
                    spendInspiration={spendInspiration}
                    setSpendInspiration={setSpendInspiration}
                    onSend={handleSendAction}
                    onPass={actions.pass}
                    onRoll={actions.roll}
                    onAttack={actions.attack}
                    onCast={actions.cast}
                    onUseItem={actions.useItem}
                    onLongRest={actions.longRest}
                    onShortRest={actions.shortRest}
                    onManage={() => setManageOpen(true)}
                  />
                )}

                {/* Finished banner + end-of-session recap */}
                {status === "FINISHED" && (
                  <div className="border-t border-border bg-surface p-4">
                    <p className="text-center text-sm text-text-muted">
                      This adventure has ended.
                    </p>

                    {(recapPending || recap) && (
                      <div className="mx-auto mt-3 max-w-2xl rounded-lg border border-gold/30 bg-gold/5 p-4">
                        <h3 className="mb-2 font-display text-xs font-bold uppercase tracking-wider text-gold">
                          The Story So Far
                        </h3>
                        {recap ? (
                          <p className="whitespace-pre-wrap text-sm leading-relaxed text-text">
                            {recap}
                          </p>
                        ) : (
                          <p className="text-sm italic text-text-muted">
                            Chronicling your adventure…
                          </p>
                        )}
                      </div>
                    )}

                    <div className="mt-3 flex justify-center gap-2">
                      {isCreator && (
                        <Button
                          onClick={() =>
                            router.push(`/play?continueFrom=${sessionId}`)
                          }
                        >
                          Continue this adventure
                        </Button>
                      )}
                      <Button
                        onClick={() => router.push("/play")}
                        variant="outline"
                      >
                        New Adventure
                      </Button>
                    </div>
                  </div>
                )}
              </>
            }
          />
        </div>
      </div>
    </div>
  );
}
