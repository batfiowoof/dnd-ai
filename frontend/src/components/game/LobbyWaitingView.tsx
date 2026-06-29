"use client";

import { useSessionStore } from "@/store/sessionStore";
import { Button, Panel, D20Mark, cn } from "@/components/ui";
import Portrait from "@/components/Portrait";

interface LobbyWaitingViewProps {
  sessionId: string;
  joinCode: string | null;
  isCreator: boolean;
  username: string | null;
  loading: boolean;
  copied: boolean;
  onCopy: () => void;
  onStart: () => void;
  onKick: (playerId: string) => void;
}

/**
 * The WAITING lobby screen: invite code, session info + connection dot, the party roster
 * (with host badge and host-only kick), the AI-DM card, and the host-only start button.
 * Subscribes to the session store for the live player list / host / connection state.
 */
export default function LobbyWaitingView({
  sessionId,
  joinCode,
  isCreator,
  username,
  loading,
  copied,
  onCopy,
  onStart,
  onKick,
}: LobbyWaitingViewProps) {
  const players = useSessionStore((s) => s.players);
  const createdBy = useSessionStore((s) => s.createdBy);
  const connected = useSessionStore((s) => s.connected);

  const humanPlayers = players.filter((p) => p.role === "PLAYER");

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
              onClick={onCopy}
              data-spotlight=""
              className="spotlight tabular inline-block cursor-pointer rounded-lg border border-border-accent bg-bg-elevated px-6 py-3 text-3xl font-bold tracking-[0.3em] text-accent transition hover:border-accent hover:shadow-[0_0_24px_var(--color-accent-glow)]"
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
                      onClick={() => onKick(p.id)}
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
            onClick={onStart}
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
      </Panel>
    </main>
  );
}
