"use client";

import { useEffect, useState, useRef, useCallback, use } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import type {
  DmResponseDto,
  GameStateDto,
  PlayerDto,
  TurnEventDto,
} from "@/types";
import {
  getGameState,
  getSessionHistory,
  getSessionPlayers,
  startSession,
  kickPlayer,
} from "@/lib/api";
import {
  createStompClient,
  subscribeToSession,
  subscribeToErrors,
  sendAction,
} from "@/lib/websocket";
import type { Client } from "@stomp/stompjs";

/* ─── Chat log entry ─────────────────────────────────────────── */
interface LogEntry {
  id: string;
  type: "action" | "dm" | "system";
  playerName?: string;
  text: string;
  turnNumber: number;
}

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

  /* ── shared state ───────────────────────────────────────────── */
  const [players, setPlayers] = useState<PlayerDto[]>([]);
  const [status, setStatus] = useState<string>("WAITING");
  const [createdBy, setCreatedBy] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [connected, setConnected] = useState(false);

  /* ── lobby state ────────────────────────────────────────────── */
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState(false);

  /* ── game state ─────────────────────────────────────────────── */
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [currentTurnPlayerId, setCurrentTurnPlayerId] = useState<string | null>(
    null
  );
  const [turnNumber, setTurnNumber] = useState(0);
  const [actionText, setActionText] = useState("");

  const scrollRef = useRef<HTMLDivElement>(null);
  const clientRef = useRef<Client | null>(null);

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

  /* ── load initial state ─────────────────────────────────────── */
  useEffect(() => {
    async function loadInitial() {
      try {
        const state = await getGameState(sessionId);
        setPlayers(state.players);
        setStatus(state.status);
        setCreatedBy(state.createdBy);
        setCurrentTurnPlayerId(state.currentTurnPlayerId);
        setTurnNumber(state.turnNumber);

        if (state.status === "ACTIVE" || state.status === "FINISHED") {
          const history = await getSessionHistory(sessionId);
          const entries: LogEntry[] = [];
          history.forEach((h: TurnEventDto) => {
            entries.push({
              id: `${h.id}-action`,
              type: "action",
              playerName: h.playerName,
              text: h.action,
              turnNumber: h.turnNumber,
            });
            if (h.dmResponse) {
              entries.push({
                id: `${h.id}-dm`,
                type: "dm",
                text: h.dmResponse,
                turnNumber: h.turnNumber,
              });
            }
          });
          setLogs(entries);
          scrollToBottom();
        }
      } catch {
        setError("Failed to load session");
      }
    }
    loadInitial();
  }, [sessionId, scrollToBottom]);

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
        setConnected(true);

        subscribeToSession(client!, sessionId, (msg: unknown) => {
          const data = msg as Record<string, unknown>;

          /* DM response (has dmNarration field) */
          if ("dmNarration" in data) {
            const dm = data as unknown as DmResponseDto;
            setLogs((prev) => {
              const actionId = `ws-action-${dm.turnNumber}-${dm.playerId}`;
              const dmId = `ws-dm-${dm.turnNumber}-${dm.playerId}`;
              if (prev.some((e) => e.id === dmId)) return prev;

              const playerName =
                (data as Record<string, unknown>).playerName as
                  | string
                  | undefined;

              const newEntries: LogEntry[] = [];
              if (!prev.some((e) => e.id === actionId)) {
                newEntries.push({
                  id: actionId,
                  type: "action",
                  playerName: playerName ?? "Player",
                  text: dm.playerAction,
                  turnNumber: dm.turnNumber,
                });
              }
              newEntries.push({
                id: dmId,
                type: "dm",
                text: dm.dmNarration,
                turnNumber: dm.turnNumber,
              });
              return [...prev, ...newEntries];
            });
            setCurrentTurnPlayerId(dm.nextTurnPlayerId);
            setTurnNumber(dm.turnNumber + 1);
            scrollToBottom();
            return;
          }

          /* Session lifecycle events */
          if (data.type === "TURN_CHANGE") {
            setCurrentTurnPlayerId(data.nextPlayerId as string);
            setTurnNumber(Number(data.turnNumber));
          }

          if (data.type === "PLAYER_JOINED" || data.type === "PLAYER_LEFT") {
            const gs = data.gameState as GameStateDto | undefined;
            if (gs) {
              setPlayers(gs.players);
              setStatus(gs.status);
              setCreatedBy(gs.createdBy);
            }
          }

          if (data.type === "GAME_STARTED") {
            const gs = data.gameState as GameStateDto | undefined;
            if (gs) {
              setPlayers(gs.players);
              setCurrentTurnPlayerId(gs.currentTurnPlayerId);
              setTurnNumber(gs.turnNumber);
              setCreatedBy(gs.createdBy);
              setStatus("ACTIVE");
              setLogs((prev) => [
                ...prev,
                {
                  id: "system-start",
                  type: "system",
                  text: "The adventure begins... The Dungeon Master awaits your actions.",
                  turnNumber: 0,
                },
              ]);
              scrollToBottom();
            }
          }

          if (data.type === "GAME_ENDED") {
            setStatus("FINISHED");
            setLogs((prev) => [
              ...prev,
              {
                id: "system-end",
                type: "system",
                text: "The adventure has come to an end.",
                turnNumber: turnNumber,
              },
            ]);
            scrollToBottom();
          }
        });

        subscribeToErrors(client!, (err) => {
          setError(err);
          setTimeout(() => setError(""), 5000);
        });
      },
      () => {
        setConnected(false);
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

  /* ── polling fallback (lobby only) ──────────────────────────── */
  useEffect(() => {
    if (status !== "WAITING") return;
    const interval = setInterval(async () => {
      try {
        const list = await getSessionPlayers(sessionId);
        setPlayers(list);
      } catch {
        /* ignore */
      }
    }, 3000);
    return () => clearInterval(interval);
  }, [sessionId, status]);

  /* ── lobby actions ──────────────────────────────────────────── */
  async function handleStart() {
    if (!username) return;
    setLoading(true);
    setError("");
    try {
      const token = await getToken();
      if (!token) throw new Error("Not authenticated");
      const gs = await startSession(token, sessionId);
      setStatus(gs.status);
      setPlayers(gs.players);
      setCurrentTurnPlayerId(gs.currentTurnPlayerId);
      setTurnNumber(gs.turnNumber);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to start session");
    } finally {
      setLoading(false);
    }
  }

  async function handleKick(targetPlayerId: string) {
    if (!username) return;
    try {
      const token = await getToken();
      if (!token) throw new Error("Not authenticated");
      await kickPlayer(token, sessionId, targetPlayerId);
      // The player list will update via WebSocket PLAYER_LEFT event,
      // but also refetch to be safe
      const list = await getSessionPlayers(sessionId);
      setPlayers(list);
    } catch (e: unknown) {
      setError(
        e instanceof Error ? e.message : "Failed to remove player"
      );
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

    setLogs((prev) => [
      ...prev,
      {
        id: `local-${Date.now()}`,
        type: "action",
        playerName: username ?? "You",
        text: actionText.trim(),
        turnNumber,
      },
    ]);
    setActionText("");
    scrollToBottom();
  }

  const humanPlayers = players.filter((p) => p.role === "PLAYER");
  const currentPlayer = humanPlayers.find(
    (p) => p.id === currentTurnPlayerId
  );

  /* ════════════════════════════════════════════════════════════
     RENDER — WAITING (lobby)
     ════════════════════════════════════════════════════════════ */
  if (status === "WAITING") {
    return (
      <main className="flex min-h-screen items-center justify-center p-4">
        <div className="w-full max-w-lg rounded-xl border border-border-accent bg-surface p-8">
          <h1 className="mb-6 text-center text-2xl font-bold text-accent">
            Game Lobby
          </h1>

          {/* Join Code */}
          {joinCode && (
            <div className="mb-6 text-center">
              <p className="mb-1 text-xs uppercase tracking-widest text-text-muted">
                Join Code
              </p>
              <button
                onClick={copyCode}
                className="inline-block rounded-lg bg-bg px-6 py-3 font-mono text-3xl font-bold tracking-[0.3em] text-accent transition hover:bg-surface-light"
              >
                {joinCode}
              </button>
              <p className="mt-1 text-xs text-text-muted">
                {copied ? "Copied!" : "Click to copy"}
              </p>
            </div>
          )}

          {/* Session info */}
          <div className="mb-6 text-center">
            <p className="text-xs text-text-muted">
              Session: {sessionId.slice(0, 8)}...
            </p>
            <p className="text-xs text-text-muted">
              Status: <span className="text-accent">{status}</span>
            </p>
            {createdBy && (
              <p className="text-xs text-text-muted">
                Created by: <span className="text-text">{createdBy}</span>
                {isCreator && (
                  <span className="ml-1 text-accent">(you)</span>
                )}
              </p>
            )}
            <div className="mt-1 flex items-center justify-center gap-2">
              <span
                className={`h-2 w-2 rounded-full ${
                  connected ? "bg-green-500" : "bg-red-500"
                }`}
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
                  className="flex items-center justify-between rounded-lg border border-border bg-bg px-4 py-2.5"
                >
                  <div>
                    <span className="text-sm font-medium">{p.username}</span>
                    <span className="ml-2 text-sm text-text-muted">
                      {p.characterName}
                    </span>
                    {p.username === createdBy && (
                      <span className="ml-2 rounded bg-accent-dark/30 px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-accent">
                        Host
                      </span>
                    )}
                  </div>
                  {isCreator &&
                    p.username !== username &&
                    p.role === "PLAYER" && (
                      <button
                        onClick={() => handleKick(p.id)}
                        className="rounded px-2 py-1 text-xs text-text-muted transition hover:bg-accent-dark/30 hover:text-accent"
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
          <div className="mb-6 flex items-center gap-2 rounded-lg border border-border bg-bg px-4 py-2.5">
            <span className="text-accent">&#9876;</span>
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
            <button
              onClick={handleStart}
              disabled={loading || humanPlayers.length < 1}
              className="w-full rounded-lg bg-accent px-4 py-3 text-sm font-semibold text-white transition hover:bg-accent-dark disabled:opacity-50"
            >
              {loading ? "Starting..." : "Start Adventure"}
            </button>
          )}

          {!isCreator && (
            <p className="text-center text-sm text-text-muted">
              Waiting for the host to start the adventure...
            </p>
          )}

          {error && (
            <p className="mt-4 rounded-lg bg-accent-dark/20 px-3 py-2 text-center text-sm text-accent">
              {error}
            </p>
          )}
        </div>
      </main>
    );
  }

  /* ════════════════════════════════════════════════════════════
     RENDER — ACTIVE / FINISHED (chat room)
     ════════════════════════════════════════════════════════════ */
  return (
    <div className="flex h-screen flex-col">
      {/* Header */}
      <header className="flex items-center justify-between border-b border-border bg-surface px-4 py-3">
        <div className="flex items-center gap-3">
          <h1 className="text-lg font-bold text-accent">D&D AI</h1>
          {status === "FINISHED" && (
            <span className="rounded bg-accent-dark/30 px-2 py-0.5 text-[10px] uppercase tracking-wider text-accent">
              Finished
            </span>
          )}
        </div>
        <div className="flex items-center gap-4">
          <span className="text-xs text-text-muted">
            Turn {turnNumber}
            {currentPlayer && (
              <> &middot; {currentPlayer.characterName}&apos;s turn</>
            )}
          </span>
          <span
            className={`h-2 w-2 rounded-full ${
              connected ? "bg-green-500" : "bg-red-500"
            }`}
          />
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar — players */}
        <aside className="hidden w-52 flex-shrink-0 border-r border-border bg-surface p-4 md:flex md:flex-col">
          <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-text-muted">
            Players
          </h2>
          <div className="space-y-2">
            {humanPlayers.map((p) => (
              <div
                key={p.id}
                className={`rounded px-2 py-1.5 text-xs ${
                  p.id === currentTurnPlayerId
                    ? "border border-accent bg-accent-glow text-accent"
                    : "text-text-muted"
                }`}
              >
                <div className="font-medium">{p.characterName}</div>
                <div className="text-[10px] opacity-60">
                  {p.username}
                  {p.username === createdBy && " (host)"}
                </div>
              </div>
            ))}
          </div>

          {/* AI DM indicator in sidebar */}
          <div className="mt-auto border-t border-border pt-3">
            <div className="flex items-center gap-2 text-xs text-accent">
              <span>&#9876;</span>
              <span className="font-medium">Dungeon Master</span>
            </div>
            <div className="text-[10px] text-text-muted">AI &middot; Ollama</div>
          </div>
        </aside>

        {/* Main chat area */}
        <div className="flex flex-1 flex-col">
          {/* Log */}
          <div
            ref={scrollRef}
            className="flex-1 overflow-y-auto p-4 space-y-3"
          >
            {logs.map((entry) => (
              <div key={entry.id}>
                {entry.type === "action" && (
                  <div className="flex gap-2">
                    <span className="text-xs font-semibold text-accent">
                      {entry.playerName}:
                    </span>
                    <span className="text-sm text-text">{entry.text}</span>
                  </div>
                )}
                {entry.type === "dm" && (
                  <div className="ml-4 rounded-lg border border-border-accent bg-accent-glow px-4 py-3">
                    <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-accent">
                      Dungeon Master
                    </span>
                    <p className="text-sm leading-relaxed text-text">
                      {entry.text}
                    </p>
                  </div>
                )}
                {entry.type === "system" && (
                  <p className="text-center text-xs italic text-text-muted">
                    {entry.text}
                  </p>
                )}
              </div>
            ))}
            {logs.length === 0 && (
              <p className="text-center text-sm text-text-muted">
                Waiting for the adventure to begin...
              </p>
            )}
          </div>

          {/* Error */}
          {error && (
            <div className="mx-4 mb-2 rounded-lg bg-accent-dark/20 px-3 py-2 text-center text-xs text-accent">
              {error}
            </div>
          )}

          {/* Input — only when ACTIVE */}
          {status === "ACTIVE" && (
            <form
              onSubmit={handleSubmit}
              className="border-t border-border bg-surface p-4"
            >
              <div className="flex gap-2">
                <input
                  type="text"
                  value={actionText}
                  onChange={(e) => setActionText(e.target.value)}
                  placeholder={
                    isMyTurn
                      ? "Describe your action..."
                      : "Waiting for your turn..."
                  }
                  disabled={!isMyTurn || !connected}
                  className="flex-1 rounded-lg border border-border bg-bg px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent disabled:opacity-40"
                />
                <button
                  type="submit"
                  disabled={!isMyTurn || !connected || !actionText.trim()}
                  className="rounded-lg bg-accent px-6 py-2.5 text-sm font-semibold text-white transition hover:bg-accent-dark disabled:opacity-40"
                >
                  Send
                </button>
              </div>
              {isMyTurn && (
                <p className="mt-1 text-xs text-accent">
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
              <button
                onClick={() => router.push("/")}
                className="mt-2 rounded-lg border border-accent px-4 py-2 text-sm text-accent transition hover:bg-accent hover:text-white"
              >
                New Adventure
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
