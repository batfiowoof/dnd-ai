/**
 * Single source of truth for turning thrown errors into human-readable copy.
 *
 * The backend already returns friendly messages for expected (4xx) failures via `ErrorResponse` /
 * `WsError`; this module is the frontend safety net that (a) maps server/network/unknown failures
 * to calm, actionable copy and (b) makes sure a raw status code, stack, or non-JSON frame never
 * reaches the UI. Always run caught errors through {@link getErrorMessage} before showing them.
 */

/** Error thrown by the REST layer (`api.ts`) carrying the HTTP status + server-provided message. */
export class ApiError extends Error {
  readonly status: number;
  /** The `message` field from the backend `ErrorResponse`, if any. */
  readonly serverMessage?: string;

  constructor(status: number, serverMessage?: string) {
    super(serverMessage ?? `Request failed: ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.serverMessage = serverMessage;
  }
}

/** Friendly fallback copy per HTTP status, used when the server gives us nothing usable. */
const STATUS_FALLBACKS: Record<number, string> = {
  400: "That request couldn't be processed. Please check your input and try again.",
  401: "Your session has expired. Please sign in again.",
  403: "You don't have permission to do that.",
  404: "We couldn't find what you were looking for.",
  409: "That action conflicts with the current state. Refresh and try again.",
  413: "That file is too large. Please choose a smaller one.",
  415: "That file type isn't supported.",
  429: "You're doing that a bit too fast. Please wait a moment and try again.",
  500: "The server hit a snag. Please try again in a moment.",
  502: "The server is unreachable right now. Please try again shortly.",
  503: "The service is temporarily unavailable. Please try again shortly.",
  504: "The server took too long to respond. Please try again.",
};

const DEFAULT_FALLBACK = "Something went wrong. Please try again.";
const NETWORK_MESSAGE = "Can't reach the server. Check your connection and try again.";

/**
 * Convert any caught value into a message safe to show a user.
 *
 * - `ApiError`: 4xx surfaces the server's (already user-facing) message; 5xx is replaced with calm
 *   fallback copy so internal details never leak.
 * - Network failures (`fetch` rejects with `TypeError`) become a connection message.
 * - App-thrown `Error`s (e.g. client-side validation) keep their message.
 * - Anything else falls back to `fallback`.
 */
export function getErrorMessage(e: unknown, fallback: string = DEFAULT_FALLBACK): string {
  if (e instanceof ApiError) {
    if (e.status >= 500) {
      return STATUS_FALLBACKS[e.status] ?? STATUS_FALLBACKS[500];
    }
    const trimmed = e.serverMessage?.trim();
    return trimmed || STATUS_FALLBACKS[e.status] || fallback;
  }
  // `fetch` surfaces connection/DNS/CORS failures as a bare TypeError.
  if (e instanceof TypeError) {
    return NETWORK_MESSAGE;
  }
  if (e instanceof Error && e.message.trim()) {
    return e.message;
  }
  if (typeof e === "string" && e.trim()) {
    return e;
  }
  return fallback;
}
