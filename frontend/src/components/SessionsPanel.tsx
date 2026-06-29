"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import {
  useUserSessions,
  useLeaveSession,
  useDeleteSession,
} from "@/hooks/useSessionQueries";
import type { SessionSummary } from "@/types";
import { Panel, Button, Spinner, useToast, cn } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { rememberSession } from "@/lib/sessionStorage";
import { STATUS_STYLES, STATUS_LABEL } from "@/lib/sessionStatus";

export default function SessionsPanel({ className }: { className?: string }) {
  const router = useRouter();
  const sessionsQuery = useUserSessions();
  const leaveMutation = useLeaveSession();
  const deleteMutation = useDeleteSession();
  const toast = useToast();

  const [confirm, setConfirm] = useState<{
    id: string;
    action: "leave" | "delete";
  } | null>(null);

  const sessions = sessionsQuery.data ?? [];

  function rejoin(s: SessionSummary) {
    // Restore the per-session keys the lobby/game pages read, so rejoin works
    // even on a fresh device where localStorage was never populated.
    rememberSession(s.sessionId, {
      playerId: s.myPlayerId,
      joinCode: s.joinCode,
    });
    router.push(`/lobby/${s.sessionId}`);
  }

  async function runConfirmed() {
    if (!confirm) return;
    try {
      if (confirm.action === "leave") {
        await leaveMutation.mutateAsync(confirm.id);
      } else {
        await deleteMutation.mutateAsync(confirm.id);
      }
      setConfirm(null);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Action failed"));
    }
  }

  if (sessionsQuery.isLoading) {
    return (
      <Panel className={cn("p-5", className)}>
        <span className="inline-flex items-center gap-2 text-sm text-text-muted">
          <Spinner className="text-accent" /> Loading your adventures...
        </span>
      </Panel>
    );
  }

  if (sessions.length === 0) {
    return (
      <Panel className={cn("p-5 text-center", className)}>
        <h2 className="mb-1 font-display text-sm font-bold uppercase tracking-wider text-text-muted">
          Your Adventures
        </h2>
        <p className="text-xs text-text-muted">
          No adventures yet — create or join one below.
        </p>
      </Panel>
    );
  }

  return (
    <Panel className={cn("p-5", className)}>
      <h2 className="mb-3 font-display text-sm font-bold uppercase tracking-wider text-gold">
        Your Adventures
      </h2>
      <ul className="space-y-2">
        {sessions.map((s) => {
          const pending =
            (leaveMutation.isPending || deleteMutation.isPending) &&
            confirm?.id === s.sessionId;
          const isConfirming = confirm?.id === s.sessionId;
          return (
            <li
              key={s.sessionId}
              className="rounded-lg border border-border bg-bg-elevated p-3 transition hover:border-accent/40"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span
                      className={cn(
                        "rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider",
                        STATUS_STYLES[s.status]
                      )}
                    >
                      {STATUS_LABEL[s.status]}
                    </span>
                    {s.isCreator && (
                      <span className="text-[10px] uppercase tracking-wider text-text-muted">
                        Host
                      </span>
                    )}
                  </div>
                  <p className="mt-1 truncate text-sm font-medium text-text">
                    {s.title}
                  </p>
                  <p className="mt-0.5 text-[11px] text-text-muted">
                    {s.playerCount} player{s.playerCount === 1 ? "" : "s"}
                    {"  ·  "}
                    <span className="tabular">{s.joinCode}</span>
                    {"  ·  "}
                    {new Date(s.createdAt).toLocaleDateString()}
                  </p>
                </div>
                <Button size="sm" onClick={() => rejoin(s)}>
                  {s.status === "FINISHED" ? "View" : "Rejoin"}
                </Button>
              </div>

              {/* Manage row */}
              <div className="mt-2 flex items-center justify-end gap-2 border-t border-border pt-2">
                {isConfirming ? (
                  <>
                    <span className="mr-auto text-[11px] text-text-muted">
                      {confirm.action === "delete"
                        ? "Delete this session for everyone?"
                        : "Leave this session?"}
                    </span>
                    <button
                      onClick={() => setConfirm(null)}
                      disabled={pending}
                      className="cursor-pointer text-[11px] text-text-muted transition hover:text-text"
                    >
                      Cancel
                    </button>
                    <Button
                      size="sm"
                      variant="danger"
                      loading={pending}
                      onClick={runConfirmed}
                    >
                      Confirm
                    </Button>
                  </>
                ) : s.isCreator ? (
                  <button
                    onClick={() =>
                      setConfirm({ id: s.sessionId, action: "delete" })
                    }
                    className="cursor-pointer text-[11px] text-accent transition hover:text-accent-light hover:underline"
                  >
                    Delete
                  </button>
                ) : (
                  <button
                    onClick={() =>
                      setConfirm({ id: s.sessionId, action: "leave" })
                    }
                    className="cursor-pointer text-[11px] text-text-muted transition hover:text-accent"
                  >
                    Leave
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
}
