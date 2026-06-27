"use client";

import { useEffect, useState } from "react";
import { Modal, Button, cn } from "@/components/ui";
import type { RollMode, RollRequestEvent } from "@/types";

interface RollPromptModalProps {
  request: RollRequestEvent | null;
  connected: boolean;
  /** The local player has Inspiration to spend (renders the toggle when true). */
  hasInspiration: boolean;
  onRoll: (spendInspiration: boolean) => void;
}

/* ── icons (SVG, never emoji) ─────────────────────────────────── */
function ChevronUp({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      <path d="m6 15 6-6 6 6" />
    </svg>
  );
}

function ChevronDown({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

function Spark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="currentColor"
      className={className}
      aria-hidden="true"
    >
      <path d="M12 2l1.9 5.6a3 3 0 0 0 1.9 1.9L21.5 11l-5.7 1.9a3 3 0 0 0-1.9 1.9L12 20.5l-1.9-5.7a3 3 0 0 0-1.9-1.9L2.5 11l5.7-1.9a3 3 0 0 0 1.9-1.9L12 2z" />
    </svg>
  );
}

/**
 * Combine the DM's situational mode with spent Inspiration using the D&D cancellation rule:
 * any advantage + any disadvantage → normal. Display-only — the backend rolls authoritatively.
 */
function effectiveMode(dmMode: RollMode, spend: boolean): RollMode {
  const hasAdv = dmMode === "ADVANTAGE" || spend;
  const hasDis = dmMode === "DISADVANTAGE";
  if (hasAdv && hasDis) return "NORMAL";
  if (hasAdv) return "ADVANTAGE";
  if (hasDis) return "DISADVANTAGE";
  return "NORMAL";
}

/**
 * Shown when the DM calls for an ability check from the local player. The DM decides any
 * situational advantage/disadvantage; the player's only roll-mode lever is spending Inspiration
 * (when they hold it). The backend rolls authoritatively and narrates the outcome. Not
 * dismissible — the check must be rolled to continue.
 */
export default function RollPromptModal({
  request,
  connected,
  hasInspiration,
  onRoll,
}: RollPromptModalProps) {
  const [spend, setSpend] = useState(false);

  // Reset the Inspiration toggle whenever a new check arrives (identity = player+ability+dc),
  // so a previous prompt's choice never leaks into the next one.
  useEffect(() => {
    setSpend(false);
  }, [request?.playerId, request?.ability, request?.dc]);

  // Land focus on the primary CTA. Modal focuses its own panel synchronously on open, and a
  // child effect / autoFocus would be stolen back — defer to the next frame so this wins.
  // (Button doesn't forward refs, so target it by id.)
  useEffect(() => {
    if (!request) return;
    const id = requestAnimationFrame(() =>
      document.getElementById("roll-cta")?.focus()
    );
    return () => cancelAnimationFrame(id);
  }, [request]);

  if (!request) return null;

  const { dmMode, checkKind, targetLabel, reason } = request;
  const isContest = checkKind === "CONTEST";
  const isGroup = checkKind === "GROUP";

  const sign =
    request.suggestedModifier >= 0
      ? `+${request.suggestedModifier}`
      : `${request.suggestedModifier}`;
  const heading = request.skill
    ? `${request.ability} (${request.skill})`
    : `${request.ability} check`;

  const showToggle = hasInspiration;
  const eff = effectiveMode(dmMode, spend && showToggle);
  const effLabel =
    eff === "ADVANTAGE"
      ? "Advantage"
      : eff === "DISADVANTAGE"
        ? "Disadvantage"
        : "Normal";

  // Inline note shown when Inspiration is toggled on, covering the cancellation cases.
  const inspirationNote =
    dmMode === "DISADVANTAGE"
      ? "This cancels the DM's disadvantage to a normal roll — your Inspiration is still spent."
      : dmMode === "ADVANTAGE"
        ? "You already have advantage — spending Inspiration adds nothing (and is still spent)."
        : "You'll roll with advantage.";

  return (
    <Modal
      open={!!request}
      onClose={() => {}}
      dismissible={false}
      hideClose
      size="sm"
      title="The DM calls for a roll"
    >
      <div className="space-y-4">
        {/* Group / Contest framing banner */}
        {isGroup && (
          <div className="rounded-lg border border-gold/40 bg-gold-muted px-3 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-gold">
              Party check
            </p>
            <p className="mt-0.5 text-xs leading-relaxed text-text-muted">
              The whole party rolls and shares the outcome — the group succeeds
              if at least half of you do.
            </p>
          </div>
        )}
        {isContest && (
          <div className="rounded-lg border border-border-accent bg-bg-elevated px-3 py-2">
            <p className="text-sm font-semibold text-text">
              Contested vs{" "}
              <span className="text-accent">{targetLabel ?? "your foe"}</span>
            </p>
            <p className="mt-0.5 text-xs leading-relaxed text-text-muted">
              Your opponent rolls too — highest wins (ties go to them).
            </p>
          </div>
        )}

        {/* Check + DC */}
        <div className="flex items-center justify-between rounded-lg border border-border-accent bg-bg-elevated px-4 py-3">
          <div>
            <p
              className="text-lg font-bold text-text"
              style={{ fontFamily: "var(--font-display)" }}
            >
              {heading}
            </p>
            <p className="tabular text-xs text-text-muted">
              Your modifier <span className="text-gold">{sign}</span>
            </p>
          </div>
          {/* DC is meaningless for an opposed (CONTEST) roll, so hide it there. */}
          {!isContest && (
            <div className="flex flex-col items-center rounded-lg border border-gold/40 bg-gold/10 px-3 py-1.5">
              <span className="text-[0.6rem] uppercase tracking-wider text-text-muted">
                DC
              </span>
              <span className="tabular text-xl font-bold text-gold">
                {request.dc}
              </span>
            </div>
          )}
        </div>

        {/* DM-granted situational mode */}
        {dmMode === "ADVANTAGE" && (
          <div className="flex items-start gap-2.5">
            <span className="inline-flex shrink-0 items-center gap-1 rounded-full border border-success/50 bg-success/10 px-2.5 py-1 text-xs font-semibold text-success">
              <ChevronUp />
              Advantage
            </span>
            <p className="text-sm leading-relaxed text-text-muted">
              The DM grants you advantage{reason ? ` — ${reason}` : ""}.
            </p>
          </div>
        )}
        {dmMode === "DISADVANTAGE" && (
          <div className="flex items-start gap-2.5">
            <span className="inline-flex shrink-0 items-center gap-1 rounded-full border border-danger/50 bg-danger/10 px-2.5 py-1 text-xs font-semibold text-danger">
              <ChevronDown />
              Disadvantage
            </span>
            <p className="text-sm leading-relaxed text-text-muted">
              The DM imposes disadvantage{reason ? ` — ${reason}` : ""}.
            </p>
          </div>
        )}
        {dmMode === "NORMAL" && reason && (
          <p className="text-sm leading-relaxed text-text-muted">{reason}</p>
        )}

        {/* Inspiration — only when the local player holds it */}
        {showToggle && (
          <div className="rounded-lg border border-gold/40 bg-gold-muted/60 p-3">
            <button
              type="button"
              role="switch"
              aria-checked={spend}
              disabled={!connected}
              onClick={() => setSpend((v) => !v)}
              className={cn(
                "flex min-h-[44px] w-full items-center gap-3 rounded-md px-1 text-left transition cursor-pointer",
                "focus:outline-none focus-visible:ring-2 focus-visible:ring-gold focus-visible:ring-offset-2 focus-visible:ring-offset-surface",
                "disabled:cursor-not-allowed disabled:opacity-50"
              )}
            >
              {/* Track */}
              <span
                className={cn(
                  "relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border transition-colors",
                  spend
                    ? "border-gold bg-gold/80"
                    : "border-border bg-surface-light"
                )}
              >
                <span
                  className={cn(
                    "inline-block h-4 w-4 transform rounded-full bg-bg shadow transition-transform motion-reduce:transition-none",
                    spend ? "translate-x-6" : "translate-x-1"
                  )}
                />
              </span>
              <span className="flex items-center gap-1.5 text-sm font-semibold text-gold">
                <Spark />
                Spend Inspiration
                <span className="font-normal text-text-muted">
                  (grants advantage)
                </span>
              </span>
            </button>

            {spend && (
              <p
                role="status"
                aria-live="polite"
                className="mt-2 px-1 text-xs leading-relaxed text-gold"
              >
                {inspirationNote}
              </p>
            )}
          </div>
        )}

        {/* Effective-mode preview (display-only) */}
        <p className="tabular text-center text-[11px] uppercase tracking-wider text-text-muted">
          Rolling:{" "}
          <span
            className={cn(
              "font-semibold",
              eff === "ADVANTAGE"
                ? "text-success"
                : eff === "DISADVANTAGE"
                  ? "text-danger"
                  : "text-text"
            )}
          >
            {effLabel}
          </span>
        </p>

        {/* Single primary CTA */}
        <Button
          id="roll-cta"
          fullWidth
          size="lg"
          disabled={!connected}
          onClick={() => onRoll(spend && showToggle)}
        >
          Roll the d20{" "}
          <span className="tabular font-normal opacity-80">{sign}</span>
        </Button>
      </div>
    </Modal>
  );
}
