"use client";

import { useEffect, useState, useRef, useCallback, use } from "react";
import type {
  DmResponseDto,
  GameStateDto,
  PlayerDto,
  TurnEventDto,
} from "@/types";
import {
  useSessionHistory,
  useSessionPlayers,
} from "@/hooks/useSessionQueries";
import {
  createStompClient,
  subscribeToSession,
  subscribeToErrors,
  sendAction,
} from "@/lib/websocket";
import type { Client } from "@stomp/stompjs";
import { Button, Brand, D20Mark, cn } from "@/components/ui";

interface LogEntry {
  id: string;
  type: "action" | "dm" | "system";
  playerName?: string;
  text: string;
  turnNumber: number;
}

export default function GamePage({
  params,
}: {
  params: Promise<{ sessionId: string }>;
}) {
  const { sessionId } = use(params);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [players, setPlayers] = useState<PlayerDto[]>([]);
  const [currentTurnPlayerId, setCurrentTurnPlayerId] = useState<string | null>(
    null
  );
  const [turnNumber, setTurnNumber] = useState(0);
  const [actionText, setActionText] = useState("");
  const [error, setError] = useState("");
  const [connected, setConnected] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const clientRef = useRef<Client | null>(null);

  const username =
    typeof window !== "undefined"
      ? localStorage.getItem("dnd-username") ?? ""
      : "";
  const playerId =
    typeof window !== "undefined"
      ? localStorage.getItem(`dnd-playerId-${sessionId}`) ?? ""
      : "";

  const isMyTurn = currentTurnPlayerId === playerId;

  const scrollToBottom = useCallback(() => {
    setTimeout(() => {
      scrollRef.current?.scrollTo({
        top: scrollRef.current.scrollHeight,
        behavior: "smooth",
      });
    }, 50);
  }, []);

  // Load history + players on mount (React Query)
  const historyQuery = useSessionHistory(sessionId, true);
  const playersQuery = useSessionPlayers(sessionId);

  useEffect(() => {
    if (playersQuery.data) setPlayers(playersQuery.data);
  }, [playersQuery.data]);

  useEffect(() => {
    if (!historyQuery.data) return;
    const entries: LogEntry[] = [];
    historyQuery.data.forEach((h: TurnEventDto) => {
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
  }, [historyQuery.data, scrollToBottom]);

  useEffect(() => {
    if (historyQuery.isError || playersQuery.isError) {
      setError("Failed to load game history");
    }
  }, [historyQuery.isError, playersQuery.isError]);

  // WebSocket connection
  useEffect(() => {
    if (!username) return;

    const client = createStompClient(
      username,
      () => {
        setConnected(true);

        subscribeToSession(client, sessionId, (msg: unknown) => {
          const data = msg as Record<string, unknown>;

          // DM Response (has dmNarration field)
          if ("dmNarration" in data) {
            const dm = data as unknown as DmResponseDto;
            setLogs((prev) => {
              const actionId = `ws-action-${dm.turnNumber}-${dm.playerId}`;
              const dmId = `ws-dm-${dm.turnNumber}-${dm.playerId}`;
              // Avoid duplicates
              if (prev.some((e) => e.id === dmId)) return prev;

              const playerName =
                (data as Record<string, unknown>).playerName as string | undefined;

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

          // Session events
          if (data.type === "TURN_CHANGE") {
            setCurrentTurnPlayerId(data.nextPlayerId as string);
            setTurnNumber(Number(data.turnNumber));
          }
          if (data.type === "PLAYER_JOINED" || data.type === "PLAYER_LEFT") {
            const gs = data.gameState as GameStateDto | undefined;
            if (gs) setPlayers(gs.players);
          }
          if (data.type === "GAME_STARTED") {
            const gs = data.gameState as GameStateDto | undefined;
            if (gs) {
              setPlayers(gs.players);
              setCurrentTurnPlayerId(gs.currentTurnPlayerId);
              setTurnNumber(gs.turnNumber);
              setLogs((prev) => [
                ...prev,
                {
                  id: "system-start",
                  type: "system",
                  text: "The adventure begins...",
                  turnNumber: 0,
                },
              ]);
              scrollToBottom();
            }
          }
        });

        subscribeToErrors(client, (err) => {
          setError(err);
          setTimeout(() => setError(""), 5000);
        });
      },
      () => {
        setConnected(false);
      }
    );

    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, [sessionId, username, scrollToBottom]);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!actionText.trim() || !clientRef.current || !isMyTurn) return;

    sendAction(clientRef.current, sessionId, actionText.trim());

    // Optimistic local entry
    setLogs((prev) => [
      ...prev,
      {
        id: `local-${Date.now()}`,
        type: "action",
        playerName: username,
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

  return (
    <div className="flex h-dvh flex-col">
      {/* Header */}
      <header className="flex items-center justify-between border-b border-border bg-surface/80 px-4 py-3 backdrop-blur-sm">
        <Brand size="sm" />
        <div className="flex items-center gap-4">
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
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar */}
        <aside className="hidden w-48 flex-shrink-0 border-r border-border bg-surface/60 p-4 md:block">
          <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-text-muted">
            Players
          </h2>
          <div className="space-y-2">
            {humanPlayers.map((p) => (
              <div
                key={p.id}
                className={cn(
                  "rounded-lg px-2.5 py-1.5 text-xs transition",
                  p.id === currentTurnPlayerId
                    ? "border border-gold/60 bg-gold-muted text-gold"
                    : "border border-transparent text-text-muted"
                )}
              >
                <div className="font-medium">{p.characterName}</div>
                <div className="text-[10px] opacity-60">{p.username}</div>
              </div>
            ))}
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
              <div key={entry.id} className="animate-rise">
                {entry.type === "action" && (
                  <div className="flex gap-2">
                    <span className="text-xs font-semibold text-gold">
                      {entry.playerName}:
                    </span>
                    <span className="text-sm text-text">{entry.text}</span>
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
            <div className="mx-4 mb-2 rounded-lg border border-accent-dark/40 bg-accent-dark/20 px-3 py-2 text-center text-xs text-accent-light">
              {error}
            </div>
          )}

          {/* Input */}
          <form
            onSubmit={handleSubmit}
            className="border-t border-border bg-surface/80 p-4 backdrop-blur-sm"
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
        </div>
      </div>
    </div>
  );
}
