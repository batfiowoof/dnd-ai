import type { SessionSummary } from "@/types";

/** Chip tint classes per session status (used in the "your adventures" list). */
export const STATUS_STYLES: Record<SessionSummary["status"], string> = {
  WAITING: "bg-gold-muted text-gold",
  ACTIVE: "bg-success/15 text-success",
  FINISHED: "bg-surface-light text-text-muted",
};

/** Human-readable label per session status. */
export const STATUS_LABEL: Record<SessionSummary["status"], string> = {
  WAITING: "Lobby",
  ACTIVE: "In Progress",
  FINISHED: "Finished",
};
