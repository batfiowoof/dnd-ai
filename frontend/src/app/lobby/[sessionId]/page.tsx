"use client";

import { useEffect, useState, useRef, useCallback, use } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import {
  useStartSession,
  useKickPlayer,
  useEndSession,
  useGameState,
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
  rememberSession,
  forgetSession,
} from "@/lib/sessionStorage";
import { Button, ConfirmDialog, useToast } from "@/components/ui";
import DiceRollModal from "@/components/dice/DiceRollModal";
import CharacterSheetDialog from "@/components/game/CharacterSheetDialog";
import InventoryManager from "@/components/game/InventoryManager";
import ShopDialog from "@/components/game/ShopDialog";
import { useAvailableShops } from "@/hooks/useShopQueries";
import CombatActionModal from "@/components/combat/CombatActionModal";
import ReactionPromptModal from "@/components/combat/ReactionPromptModal";
import { uploadCombatMap, leaveSession, getMagicItems } from "@/lib/api";
import { buildMagicIndex } from "@/lib/magicItems";
import type { MagicItemSummary } from "@/types";
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
  const [shopOpen, setShopOpen] = useState(false);
  const [sheetPlayerId, setSheetPlayerId] = useState<string | null>(null);
  const [leaveConfirm, setLeaveConfirm] = useState(false);
  const [endConfirm, setEndConfirm] = useState(false);
  const [magicIndex, setMagicIndex] = useState<Record<string, MagicItemSummary>>({});

  const scrollRef = useRef<HTMLDivElement>(null);

  // Fetch the magic-item catalog once so the inventory can badge items by rarity/attunement.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const token = await getToken();
      if (!token) return;
      try {
        const items = await getMagicItems(token);
        if (!cancelled) setMagicIndex(buildMagicIndex(items));
      } catch {
        /* non-fatal — items simply won't be badged as magic */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [getToken]);

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

  const isCreator = username === createdBy;

  // Canonical game state (React Query cache, shared with useLobbyData) — used to recover the
  // join code when this device has none saved.
  const { data: gameState } = useGameState(sessionId);

  /* ── who am I in this session ────────────────────────────────────
     Prefer this device's saved identity, but self-heal from the loaded roster when it's
     missing — reaching /lobby/<id> via a shared/bookmarked URL, a new device, or cleared
     storage would otherwise strand the player, forcing a re-entry of the join code. Matching
     the authenticated username against the roster (and the join code from the game state)
     recovers identity transparently. */
  const storedPlayerId = getPlayerId(sessionId);
  const storedJoinCode = getJoinCode(sessionId);
  const playerId =
    storedPlayerId ||
    (username ? players.find((p) => p.username === username)?.id ?? "" : "");
  const joinCode = storedJoinCode || gameState?.joinCode || "";

  // Persist any recovered identity so later loads are instant and copy-code keeps working.
  useEffect(() => {
    if (playerId && (playerId !== storedPlayerId || joinCode !== storedJoinCode)) {
      rememberSession(sessionId, { playerId, joinCode });
    }
  }, [sessionId, playerId, joinCode, storedPlayerId, storedJoinCode]);

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

  /* ── shops ──────────────────────────────────────────────────────
     A shop is only reachable where it sits, so availability is keyed on the party's live location:
     travelling re-fetches and the "Shop" button appears/disappears accordingly. After a trade we
     re-fetch to reflect decremented limited stock (the purse updates live via PLAYER_STATE). */
  const currentRegion = useSessionStore((s) => s.currentRegion);
  const currentSubregion = useSessionStore((s) => s.currentSubregion);
  const { data: shopsData, refetch: refetchShops } = useAvailableShops(
    sessionId,
    inGame,
    currentRegion,
    currentSubregion
  );
  const availableShops = shopsData?.shops ?? [];

  const handleShopBuy = useCallback(
    (shopKey: string, itemRef: string, qty: number) => {
      actions.shopBuy({ shopKey, itemRef, qty });
      setTimeout(() => refetchShops(), 500);
    },
    [actions, refetchShops]
  );
  const handleShopSell = useCallback(
    (shopKey: string, name: string, qty: number) => {
      actions.shopSell({ shopKey, name, qty });
      setTimeout(() => refetchShops(), 500);
    },
    [actions, refetchShops]
  );

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

  /**
   * The host can't plainly leave an in-progress adventure — leaving must go through the
   * End-Adventure recap cycle (so the story is chronicled and can be handed to a follow-up
   * session). Route the host's Leave to the end confirmation while ACTIVE; everyone else
   * (and the host once FINISHED) gets the ordinary leave confirmation.
   */
  function handleLeaveClick() {
    if (isCreator && status === "ACTIVE") {
      setEndConfirm(true);
    } else {
      setLeaveConfirm(true);
    }
  }

  /** Leave the session: best-effort backend leave, clear local keys, return to /play. */
  async function handleLeaveSession() {
    setLeaveConfirm(false);
    // The backend forbids the creator from leaving (they end the session instead), so skip
    // the doomed call for the host and just navigate away.
    if (!isCreator) {
      try {
        const token = await getToken();
        if (token) await leaveSession(token, sessionId);
      } catch {
        // Ignore — navigate away regardless so the user is never stuck.
      }
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
    onOffHandAttack: actions.combatOffHandAttack,
    onSecondWind: actions.combatSecondWind,
    onCunningAction: actions.combatCunningAction,
    onHoldReaction: actions.combatHoldReaction,
    onReady: actions.combatReady,
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
      <ReactionPromptModal onReact={actions.combatReaction} />
      <InventoryManager
        open={manageOpen}
        onClose={() => setManageOpen(false)}
        state={myState}
        connected={connected}
        onDrop={actions.dropItem}
        onEquip={actions.equipItem}
        onAdd={actions.addItem}
        magicByName={magicIndex}
        onAttune={actions.attuneItem}
        onEndAttunement={actions.endAttunement}
      />
      <ShopDialog
        open={shopOpen}
        onClose={() => setShopOpen(false)}
        shops={availableShops}
        purseCopper={myState?.copper ?? 0}
        connected={connected}
        onBuy={handleShopBuy}
        onSell={handleShopSell}
      />
      {sheetPlayer && sheetState && (
        <CharacterSheetDialog
          open={!!sheetPlayerId}
          onClose={() => setSheetPlayerId(null)}
          state={sheetState}
          characterName={sheetPlayer.characterName}
          imageUrl={sheetPlayer.imageUrl}
          magicByName={magicIndex}
        />
      )}

      <GameRoomHeader
        playerId={playerId}
        isCreator={isCreator}
        onStartEncounter={handleStartEncounter}
        onEndSession={() => setEndConfirm(true)}
        onLeave={handleLeaveClick}
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
                    onShop={availableShops.length > 0 ? () => setShopOpen(true) : undefined}
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
