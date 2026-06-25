"use client";

import { useEffect, useState, useRef, useCallback, use } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import type { DmResponseDto, GameStateDto } from "@/types";
import {
  useGameState,
  useSessionHistory,
  useSessionPlayers,
  useStartSession,
  useKickPlayer,
} from "@/hooks/useSessionQueries";
import { useSessionStates, useActiveCombat } from "@/hooks/usePlayerStateQueries";
import { useSessionStore } from "@/store/sessionStore";
import {
  createStompClient,
  subscribeToSession,
  subscribeToErrors,
  sendAction,
  sendRoll,
  sendCast,
  sendUseItem,
  sendAddItem,
  sendDropItem,
  sendEquipItem,
  sendLongRest,
  sendStartEncounter,
  sendCombatAttack,
  sendCombatUseItem,
  sendCombatEndTurn,
  sendEndCombat,
} from "@/lib/websocket";
import type { Client } from "@stomp/stompjs";
import type {
  DiceRollEvent,
  PlayerStateEvent,
  EnemyActionEvent,
  CombatLifecycleEvent,
  ItemKind,
  PlayerRuntimeState,
  PlayerDto,
} from "@/types";
import { Button, Panel, Brand, Alert, D20Mark, Tooltip, cn } from "@/components/ui";
import Portrait from "@/components/Portrait";
import DiceRollModal from "@/components/dice/DiceRollModal";
import QuickRollBar from "@/components/dice/QuickRollBar";
import ActionBar from "@/components/game/ActionBar";
import CharacterSheetDialog from "@/components/game/CharacterSheetDialog";
import InventoryManager from "@/components/game/InventoryManager";
import CombatTracker from "@/components/combat/CombatTracker";
import EnemyActionModal from "@/components/combat/EnemyActionModal";
import StartEncounterControl from "@/components/combat/StartEncounterControl";

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

  /* ── live game state (Zustand — seeded by React Query + WebSocket) ── */
  const players = useSessionStore((s) => s.players);
  const status = useSessionStore((s) => s.status);
  const createdBy = useSessionStore((s) => s.createdBy);
  const error = useSessionStore((s) => s.error);
  const connected = useSessionStore((s) => s.connected);
  const logs = useSessionStore((s) => s.logs);
  const dmThinking = useSessionStore((s) => s.dmThinking);
  const currentTurnPlayerId = useSessionStore((s) => s.currentTurnPlayerId);
  const turnNumber = useSessionStore((s) => s.turnNumber);
  const runtimeByPlayerId = useSessionStore((s) => s.runtimeByPlayerId);
  const combat = useSessionStore((s) => s.combat);
  const setError = useSessionStore((s) => s.setError);
  const hydrateFromGameState = useSessionStore((s) => s.hydrateFromGameState);
  const seedLogsFromHistory = useSessionStore((s) => s.seedLogsFromHistory);
  const setPlayers = useSessionStore((s) => s.setPlayers);
  const setRuntimeStates = useSessionStore((s) => s.setRuntimeStates);
  const setCombat = useSessionStore((s) => s.setCombat);

  /* ── purely-local UI state ──────────────────────────────────── */
  const [copied, setCopied] = useState(false);
  const [actionText, setActionText] = useState("");
  const [manageOpen, setManageOpen] = useState(false);
  const [sheetPlayerId, setSheetPlayerId] = useState<string | null>(null);

  const scrollRef = useRef<HTMLDivElement>(null);
  const clientRef = useRef<Client | null>(null);

  /* ── mutations ──────────────────────────────────────────────── */
  const startMutation = useStartSession();
  const kickMutation = useKickPlayer();
  const loading = startMutation.isPending;

  /* ── reset per-session live state on mount / session change ─── */
  useEffect(() => {
    useSessionStore.getState().reset();
    return () => useSessionStore.getState().reset();
  }, [sessionId]);

  const playerId =
    typeof window !== "undefined"
      ? localStorage.getItem(`dnd-playerId-${sessionId}`) ?? ""
      : "";

  const joinCode =
    typeof window !== "undefined"
      ? localStorage.getItem(`dnd-joinCode-${sessionId}`) ?? ""
      : "";

  const isMyTurn = currentTurnPlayerId === playerId;
  const isCreator = username === createdBy;

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
  const gameStateQuery = useGameState(sessionId);
  // Whether the session was already underway at load time — drives the
  // one-shot history fetch. Derived from the seed query (not live status) so a
  // WebSocket GAME_STARTED transition can't re-trigger it and clobber WS logs.
  const loadedStatus = gameStateQuery.data?.status;
  const historyQuery = useSessionHistory(
    sessionId,
    loadedStatus === "ACTIVE" || loadedStatus === "FINISHED"
  );

  useEffect(() => {
    if (gameStateQuery.data) hydrateFromGameState(gameStateQuery.data);
  }, [gameStateQuery.data, hydrateFromGameState]);

  useEffect(() => {
    if (gameStateQuery.isError) setError("Failed to load session");
  }, [gameStateQuery.isError, setError]);

  useEffect(() => {
    if (historyQuery.data) {
      seedLogsFromHistory(historyQuery.data);
      scrollToBottom();
    }
  }, [historyQuery.data, seedLogsFromHistory, scrollToBottom]);

  /* ── players poll (lobby WAITING only) → seed store ─────────── */
  const playersQuery = useSessionPlayers(sessionId, {
    poll: status === "WAITING",
  });
  useEffect(() => {
    if (playersQuery.data) setPlayers(playersQuery.data);
  }, [playersQuery.data, setPlayers]);

  /* ── runtime states (HP / slots / inventory) → seed store ─────
     Gated on the LIVE status (not the frozen loadedStatus): when the creator
     starts the game from the lobby, loadedStatus stays "WAITING", so keying the
     one-shot fetch off it left stats empty until the first PLAYER_STATE event
     (i.e. the first turn). Runtime state is seeded on join/create, so fetching
     the moment the session is ACTIVE is correct and clobber-free (staleTime ∞). */
  const statesQuery = useSessionStates(
    sessionId,
    status === "ACTIVE" ||
      status === "FINISHED" ||
      loadedStatus === "ACTIVE" ||
      loadedStatus === "FINISHED"
  );
  useEffect(() => {
    if (statesQuery.data) setRuntimeStates(statesQuery.data);
  }, [statesQuery.data, setRuntimeStates]);

  /* ── active combat (resume on reload) → seed store ──────────── */
  const combatQuery = useActiveCombat(
    sessionId,
    loadedStatus === "ACTIVE" || loadedStatus === "FINISHED"
  );
  useEffect(() => {
    if (combatQuery.data !== undefined) setCombat(combatQuery.data);
  }, [combatQuery.data, setCombat]);

  /* ── WebSocket ──────────────────────────────────────────────── */
  useEffect(() => {
    if (!username) return;

    let client: Client | null = null;
    let cancelled = false;

    getToken().then((token) => {
      if (cancelled || !token) return;

      client = createStompClient(
        token,
      () => {
        const store = useSessionStore.getState();
        store.setConnected(true);

        subscribeToSession(client!, sessionId, (msg: unknown) => {
          const data = msg as Record<string, unknown>;
          const s = useSessionStore.getState();
          const type = data.type as string | undefined;

          /* Canonical DM response — untyped, carries dmNarration + nextTurnPlayerId.
             Checked before the switch (DM_NARRATION also has a dmNarration field). */
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
              s.setTurnChange(
                data.nextPlayerId as string,
                Number(data.turnNumber)
              );
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
              s.applyPlayerState(
                (data as unknown as PlayerStateEvent).state
              );
              break;
            case "COMBAT_START":
            case "COMBAT_TURN":
            case "COMBAT_END":
              s.applyCombatLifecycle(data as unknown as CombatLifecycleEvent);
              scrollToBottom();
              break;
            case "ENEMY_ACTION":
              s.applyEnemyAction(data as unknown as EnemyActionEvent);
              break;
          }
        });

        subscribeToErrors(client!, (err) => {
          useSessionStore.getState().setError(err);
          setTimeout(() => useSessionStore.getState().setError(""), 5000);
        });
      },
      () => {
        useSessionStore.getState().setConnected(false);
      }
    );

      clientRef.current = client;
    });

    return () => {
      cancelled = true;
      if (client) {
        client.deactivate();
      }
      clientRef.current = null;
    };
  }, [sessionId, username, getToken, scrollToBottom]);

  /* ── lobby actions ──────────────────────────────────────────── */
  async function handleStart() {
    if (!username) return;
    setError("");
    try {
      const gs = await startMutation.mutateAsync(sessionId);
      hydrateFromGameState(gs);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to start session");
    }
  }

  async function handleKick(targetPlayerId: string) {
    if (!username) return;
    try {
      // Player list refreshes via the WebSocket PLAYER_LEFT event and the
      // players-query invalidation in the mutation's onSuccess.
      await kickMutation.mutateAsync({ sessionId, playerId: targetPlayerId });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to remove player");
      setTimeout(() => setError(""), 5000);
    }
  }

  function copyCode() {
    if (!joinCode) return;
    navigator.clipboard.writeText(joinCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  /* ── game actions ───────────────────────────────────────────── */
  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!actionText.trim() || !clientRef.current || !isMyTurn) return;

    sendAction(clientRef.current, sessionId, actionText.trim());

    // No optimistic local entry: the server echoes the action via DM_THINKING,
    // so every client (including this one) renders it once, in order.
    setActionText("");
    scrollToBottom();
  }

  function handleRoll(notation: string, label: string) {
    if (!clientRef.current || !connected) return;
    sendRoll(clientRef.current, sessionId, { label, notation });
  }

  function handleAttack() {
    if (!clientRef.current || !connected) return;
    sendRoll(clientRef.current, sessionId, {
      label: "Attack",
      notation: "1d20",
    });
  }

  function handleCast(spellLevel: number, spellName?: string) {
    if (!clientRef.current || !connected) return;
    sendCast(clientRef.current, sessionId, { spellLevel, spellName });
  }

  function handleUseItem(itemName: string) {
    if (!clientRef.current || !connected) return;
    sendUseItem(clientRef.current, sessionId, itemName);
  }

  /* ── inventory management & rest ────────────────────────────── */
  function handleAddItem(item: { name: string; qty: number; kind: ItemKind }) {
    if (!clientRef.current || !connected) return;
    sendAddItem(clientRef.current, sessionId, item);
  }
  function handleDropItem(itemName: string) {
    if (!clientRef.current || !connected) return;
    sendDropItem(clientRef.current, sessionId, itemName);
  }
  function handleEquipItem(itemName: string, equipped: boolean) {
    if (!clientRef.current || !connected) return;
    sendEquipItem(clientRef.current, sessionId, itemName, equipped);
  }
  function handleLongRest() {
    if (!clientRef.current || !connected) return;
    sendLongRest(clientRef.current, sessionId);
  }

  /* ── combat handlers ────────────────────────────────────────── */
  function handleStartEncounter(enemyKeys: string[]) {
    if (!clientRef.current || !connected) return;
    sendStartEncounter(clientRef.current, sessionId, enemyKeys);
  }
  function handleCombatAttack(enemyId: string) {
    if (!clientRef.current || !connected) return;
    sendCombatAttack(clientRef.current, sessionId, enemyId);
  }
  function handleCombatUseItem(itemName: string) {
    if (!clientRef.current || !connected) return;
    sendCombatUseItem(clientRef.current, sessionId, itemName);
  }
  function handleCombatEndTurn() {
    if (!clientRef.current || !connected) return;
    sendCombatEndTurn(clientRef.current, sessionId);
  }
  function handleEndCombat() {
    if (!clientRef.current || !connected) return;
    sendEndCombat(clientRef.current, sessionId);
  }

  const humanPlayers = players.filter((p) => p.role === "PLAYER");
  const currentPlayer = humanPlayers.find(
    (p) => p.id === currentTurnPlayerId
  );
  const myState = playerId ? runtimeByPlayerId[playerId] ?? null : null;
  const myPlayer = humanPlayers.find((p) => p.id === playerId);
  const inCombat = combat?.status === "ACTIVE";

  /* ── character-sheet dialog target (any player) ─────────────── */
  const sheetPlayer = sheetPlayerId
    ? humanPlayers.find((p) => p.id === sheetPlayerId)
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

  /* ════════════════════════════════════════════════════════════
     RENDER — WAITING (lobby)
     ════════════════════════════════════════════════════════════ */
  if (status === "WAITING") {
    return (
      <main className="flex min-h-dvh items-center justify-center p-4">
        <Panel corners className="w-full max-w-lg p-8 animate-rise">
          <h1 className="mb-1 text-center text-2xl font-bold text-accent">
            Game Lobby
          </h1>
          <p className="mb-6 text-center text-xs uppercase tracking-[0.25em] text-gold">
            Gather your party
          </p>

          {/* Join Code */}
          {joinCode && (
            <div className="mb-6 text-center">
              <p className="mb-2 text-xs uppercase tracking-widest text-text-muted">
                Join Code
              </p>
              <button
                onClick={copyCode}
                className="tabular inline-block cursor-pointer rounded-lg border border-border-accent bg-bg-elevated px-6 py-3 text-3xl font-bold tracking-[0.3em] text-accent transition hover:border-accent hover:shadow-[0_0_24px_var(--color-accent-glow)]"
              >
                {joinCode}
              </button>
              <p className="mt-2 text-xs text-text-muted">
                {copied ? "Copied!" : "Click to copy"}
              </p>
            </div>
          )}

          <hr className="ornament mb-6" />

          {/* Session info */}
          <div className="mb-6 text-center">
            <p className="text-xs text-text-muted">
              Session: <span className="tabular">{sessionId.slice(0, 8)}...</span>
            </p>
            {createdBy && (
              <p className="text-xs text-text-muted">
                Created by: <span className="text-text">{createdBy}</span>
                {isCreator && <span className="ml-1 text-gold">(you)</span>}
              </p>
            )}
            <div className="mt-1 flex items-center justify-center gap-2">
              <span
                className={cn(
                  "h-2 w-2 rounded-full",
                  connected ? "bg-success" : "bg-danger"
                )}
              />
              <span className="text-[10px] text-text-muted">
                {connected ? "Connected" : "Connecting..."}
              </span>
            </div>
          </div>

          {/* Players */}
          <div className="mb-6">
            <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-text-muted">
              Players ({humanPlayers.length})
            </h2>
            <div className="space-y-2">
              {humanPlayers.map((p) => (
                <div
                  key={p.id}
                  className="flex items-center justify-between rounded-lg border border-border bg-bg-elevated px-4 py-2.5"
                >
                  <div className="flex items-center gap-3">
                    <Portrait src={p.imageUrl} name={p.characterName} size="sm" />
                    <div>
                      <span className="text-sm font-medium">{p.username}</span>
                      <span className="ml-2 text-sm text-text-muted">
                        {p.characterName}
                      </span>
                      {p.username === createdBy && (
                        <span className="ml-2 rounded bg-gold-muted px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-gold">
                          Host
                        </span>
                      )}
                    </div>
                  </div>
                  {isCreator &&
                    p.username !== username &&
                    p.role === "PLAYER" && (
                      <button
                        onClick={() => handleKick(p.id)}
                        className="cursor-pointer rounded px-2 py-1 text-xs text-text-muted transition hover:bg-accent-dark/30 hover:text-accent"
                        title="Remove player"
                      >
                        Kick
                      </button>
                    )}
                </div>
              ))}
              {humanPlayers.length === 0 && (
                <p className="text-center text-sm text-text-muted">
                  Waiting for players...
                </p>
              )}
            </div>
          </div>

          {/* AI DM indicator */}
          <div className="mb-6 flex items-center gap-3 rounded-lg border border-border-accent bg-accent-glow px-4 py-2.5">
            <D20Mark className="h-5 w-5 flex-shrink-0 text-accent" />
            <div>
              <span className="text-sm font-medium text-accent">
                AI Dungeon Master
              </span>
              <span className="ml-2 text-xs text-text-muted">
                Powered by Ollama
              </span>
            </div>
          </div>

          {/* Start Button — only the creator can start */}
          {isCreator && (
            <Button
              onClick={handleStart}
              disabled={humanPlayers.length < 1}
              loading={loading}
              size="lg"
              fullWidth
            >
              {loading ? "Starting..." : "Start Adventure"}
            </Button>
          )}

          {!isCreator && (
            <p className="text-center text-sm text-text-muted">
              Waiting for the host to start the adventure...
            </p>
          )}

          {error && <Alert className="mt-4">{error}</Alert>}
        </Panel>
      </main>
    );
  }

  /* ════════════════════════════════════════════════════════════
     RENDER — ACTIVE / FINISHED (chat room)
     ════════════════════════════════════════════════════════════ */
  return (
    <div className="flex h-dvh flex-col">
      {/* Dice + combat-action animation overlays */}
      <DiceRollModal />
      <EnemyActionModal />
      <InventoryManager
        open={manageOpen}
        onClose={() => setManageOpen(false)}
        state={myState}
        connected={connected}
        onDrop={handleDropItem}
        onEquip={handleEquipItem}
        onAdd={handleAddItem}
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

      {/* Header */}
      <header className="flex items-center justify-between border-b border-border bg-surface/80 px-4 py-3 backdrop-blur-sm">
        <div className="flex items-center gap-3">
          <Brand size="sm" />
          {status === "FINISHED" && (
            <span className="rounded bg-gold-muted px-2 py-0.5 text-[10px] uppercase tracking-wider text-gold">
              Finished
            </span>
          )}
        </div>
        <div className="flex items-center gap-4">
          {isCreator && status === "ACTIVE" && !inCombat && (
            <StartEncounterControl
              connected={connected}
              onStart={handleStartEncounter}
            />
          )}
          <span className="text-xs text-text-muted">
            <span className="tabular text-gold">Turn {turnNumber}</span>
            {currentPlayer && (
              <> &middot; {currentPlayer.characterName}&apos;s turn</>
            )}
          </span>
          <span
            className={cn(
              "h-2 w-2 rounded-full",
              connected ? "bg-success" : "bg-danger"
            )}
            title={connected ? "Connected" : "Disconnected"}
          />

          {/* My character — always reachable (the sidebar is hidden below md) */}
          {myPlayer && (
            <AvatarTrigger
              player={myPlayer}
              state={myState}
              placement="bottom"
              onOpen={() => setSheetPlayerId(myPlayer.id)}
            />
          )}
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar — players */}
        <aside className="hidden w-56 flex-shrink-0 overflow-y-auto border-r border-border bg-surface/60 p-4 md:flex md:flex-col">
          <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-text-muted">
            Players
          </h2>
          <div className="space-y-2">
            {humanPlayers.map((p) => (
              <div
                key={p.id}
                className={cn(
                  "flex items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-xs transition",
                  p.id === currentTurnPlayerId
                    ? "border border-gold/60 bg-gold-muted text-gold"
                    : "border border-transparent text-text-muted"
                )}
              >
                <AvatarTrigger
                  player={p}
                  state={runtimeByPlayerId[p.id]}
                  active={p.id === currentTurnPlayerId}
                  onOpen={() => setSheetPlayerId(p.id)}
                />
                <div className="min-w-0 flex-1">
                  <div className="truncate font-medium">{p.characterName}</div>
                  <div className="truncate text-[10px] opacity-60">
                    {p.username}
                    {p.username === createdBy && " (host)"}
                  </div>
                  {runtimeByPlayerId[p.id] && (
                    <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-surface-light">
                      <div
                        className={cn(
                          "h-full rounded-full transition-all",
                          runtimeByPlayerId[p.id].currentHp /
                            Math.max(1, runtimeByPlayerId[p.id].maxHp) >
                            0.5
                            ? "bg-success"
                            : runtimeByPlayerId[p.id].currentHp /
                                  Math.max(1, runtimeByPlayerId[p.id].maxHp) >
                                0.25
                              ? "bg-gold"
                              : "bg-danger"
                        )}
                        style={{
                          width: `${Math.max(
                            0,
                            Math.min(
                              100,
                              (runtimeByPlayerId[p.id].currentHp /
                                Math.max(1, runtimeByPlayerId[p.id].maxHp)) *
                                100
                            )
                          )}%`,
                        }}
                      />
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>

          {/* Tip: hover an avatar for HP/AC, click for the full sheet. */}
          <p className="mt-4 text-[10px] leading-relaxed text-text-muted">
            Hover a portrait for HP &amp; AC · click for the full sheet.
          </p>

          {/* AI DM indicator in sidebar */}
          <div className="mt-auto border-t border-border pt-3">
            <div className="flex items-center gap-2 text-xs text-accent">
              <D20Mark className="h-4 w-4" />
              <span className="font-medium">Dungeon Master</span>
            </div>
            <div className="text-[10px] text-text-muted">AI &middot; Ollama</div>
          </div>
        </aside>

        {/* Main chat area */}
        <div className="flex flex-1 flex-col">
          {/* Combat overlay */}
          {inCombat && combat && (
            <CombatTracker
              combat={combat}
              myPlayerId={playerId}
              myState={myState}
              isHost={isCreator}
              connected={connected}
              onAttack={handleCombatAttack}
              onUseItem={handleCombatUseItem}
              onEndTurn={handleCombatEndTurn}
              onEndCombat={handleEndCombat}
            />
          )}

          {/* Log */}
          <div
            ref={scrollRef}
            className="flex-1 overflow-y-auto p-4 space-y-3"
          >
            {logs.map((entry) => (
              <div key={entry.id} className="animate-rise">
                {entry.type === "action" && (
                  <div className="flex items-start gap-2">
                    <Portrait
                      src={playerByName(entry.playerName)?.imageUrl}
                      name={entry.playerName}
                      size="xs"
                      className="mt-0.5"
                    />
                    <div className="flex flex-wrap gap-x-2">
                      <span className="text-xs font-semibold text-gold">
                        {entry.playerName}:
                      </span>
                      <span className="text-sm text-text">{entry.text}</span>
                    </div>
                  </div>
                )}
                {entry.type === "dm" && (
                  <div className="ml-4 rounded-lg border border-border-accent bg-accent-glow px-4 py-3 shadow-[0_0_24px_var(--color-accent-glow)]">
                    <span className="mb-1.5 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-accent">
                      <D20Mark className="h-3.5 w-3.5" />
                      Dungeon Master
                    </span>
                    <p className="text-sm leading-relaxed text-text">
                      {entry.text}
                    </p>
                  </div>
                )}
                {entry.type === "roll" && (
                  <div className="flex items-center justify-center gap-2 text-xs text-text-muted">
                    <D20Mark className="h-3.5 w-3.5 text-gold" />
                    <span>
                      <span className="font-medium text-gold">
                        {entry.playerName}
                      </span>{" "}
                      {entry.text}
                    </span>
                  </div>
                )}
                {entry.type === "system" && (
                  <p className="text-center text-xs italic text-text-muted">
                    {entry.text}
                  </p>
                )}
              </div>
            ))}
            {dmThinking && (
              <div className="ml-4 flex items-center gap-2 text-xs text-accent animate-rise">
                <D20Mark className="h-3.5 w-3.5 animate-spin" />
                <span className="italic">The Dungeon Master is weaving the tale</span>
                <span className="inline-flex gap-0.5">
                  <span className="h-1 w-1 animate-bounce rounded-full bg-accent [animation-delay:-0.3s]" />
                  <span className="h-1 w-1 animate-bounce rounded-full bg-accent [animation-delay:-0.15s]" />
                  <span className="h-1 w-1 animate-bounce rounded-full bg-accent" />
                </span>
              </div>
            )}
            {logs.length === 0 && !dmThinking && (
              <p className="text-center text-sm text-text-muted">
                Waiting for the adventure to begin...
              </p>
            )}
          </div>

          {/* Error */}
          {error && (
            <div className="mx-4 mb-2 rounded-lg border border-accent-dark/40 bg-accent-dark/20 px-3 py-2 text-center text-xs text-accent-light">
              {error}
            </div>
          )}

          {/* Input — only when ACTIVE */}
          {status === "ACTIVE" && (
            <form
              onSubmit={handleSubmit}
              className="border-t border-border bg-surface/80 p-4 backdrop-blur-sm"
            >
              <div className="mb-2 space-y-2">
                {!inCombat && (
                  <ActionBar
                    state={myState}
                    isMyTurn={isMyTurn}
                    connected={connected}
                    onAttack={handleAttack}
                    onCast={handleCast}
                    onUseItem={handleUseItem}
                    onLongRest={handleLongRest}
                    onManage={() => setManageOpen(true)}
                  />
                )}
                <QuickRollBar
                  onRoll={handleRoll}
                  disabled={!connected || inCombat}
                />
              </div>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={actionText}
                  onChange={(e) => setActionText(e.target.value)}
                  placeholder={
                    inCombat
                      ? "In combat — use combat actions above"
                      : isMyTurn
                        ? "Describe your action..."
                        : "Waiting for your turn..."
                  }
                  disabled={!isMyTurn || !connected || inCombat}
                  className="flex-1 rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent disabled:opacity-40"
                />
                <Button
                  type="submit"
                  disabled={!isMyTurn || !connected || !actionText.trim()}
                  size="lg"
                >
                  Send
                </Button>
              </div>
              {isMyTurn && (
                <p className="mt-1.5 text-xs font-medium text-gold">
                  It&apos;s your turn!
                </p>
              )}
            </form>
          )}

          {/* Finished banner */}
          {status === "FINISHED" && (
            <div className="border-t border-border bg-surface p-4 text-center">
              <p className="text-sm text-text-muted">
                This adventure has ended.
              </p>
              <Button
                onClick={() => router.push("/play")}
                variant="outline"
                className="mt-2"
              >
                New Adventure
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * Side-menu / header avatar. Hover (or focus) shows a quick HP + AC tooltip;
 * clicking opens the full character-sheet dialog. Works for any player.
 */
function AvatarTrigger({
  player,
  state,
  active = false,
  onOpen,
  placement = "right",
}: {
  player: PlayerDto;
  state?: PlayerRuntimeState | null;
  active?: boolean;
  onOpen: () => void;
  placement?: "top" | "right" | "bottom" | "left";
}) {
  return (
    <Tooltip
      placement={placement}
      content={
        <span className="block whitespace-nowrap text-xs">
          <span className="block font-display font-semibold text-text">
            {player.characterName}
          </span>
          {state ? (
            <span className="mt-0.5 block tabular text-text-muted">
              <span className="text-gold">HP</span> {state.currentHp}/
              {state.maxHp}
              {"    "}
              <span className="text-gold">AC</span> {state.armorClass}
            </span>
          ) : (
            <span className="mt-0.5 block text-text-muted">No stats yet</span>
          )}
        </span>
      }
    >
      <button
        type="button"
        onClick={onOpen}
        aria-label={`${player.characterName} character sheet`}
        className="cursor-pointer rounded-full transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
      >
        <Portrait
          src={player.imageUrl}
          name={player.characterName}
          size="sm"
          active={active}
        />
      </button>
    </Tooltip>
  );
}
