/**
 * Per-session client identity persisted in localStorage (no backend). The lobby/game pages read
 * these to know "who am I in this session" and to rejoin on reload. Centralised here so the key
 * format lives in one place instead of being hand-built with template strings across pages.
 */

const playerIdKey = (sessionId: string) => `dnd-playerId-${sessionId}`;
const joinCodeKey = (sessionId: string) => `dnd-joinCode-${sessionId}`;

function read(key: string): string {
  if (typeof window === "undefined") return "";
  try {
    return localStorage.getItem(key) ?? "";
  } catch {
    return "";
  }
}

function write(key: string, value: string): void {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(key, value);
  } catch {
    /* localStorage unavailable (private mode / quota) — nothing to persist */
  }
}

function remove(key: string): void {
  if (typeof window === "undefined") return;
  try {
    localStorage.removeItem(key);
  } catch {
    /* localStorage unavailable — nothing to clean up */
  }
}

/** This client's player id within a session ("" if not joined on this device). */
export function getPlayerId(sessionId: string): string {
  return read(playerIdKey(sessionId));
}

/** The invite/join code for a session ("" if unknown on this device). */
export function getJoinCode(sessionId: string): string {
  return read(joinCodeKey(sessionId));
}

/** Persist the keys the lobby/game pages need to identify and rejoin a session. */
export function rememberSession(
  sessionId: string,
  { playerId, joinCode }: { playerId: string; joinCode: string }
): void {
  write(playerIdKey(sessionId), playerId);
  write(joinCodeKey(sessionId), joinCode);
}

/** Clear a session's local identity (on leave). */
export function forgetSession(sessionId: string): void {
  remove(playerIdKey(sessionId));
  remove(joinCodeKey(sessionId));
}
