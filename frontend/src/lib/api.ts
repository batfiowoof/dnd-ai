import type {
  CombatStateDto,
  CreateSessionRequest,
  CreateSessionResponse,
  GameStateDto,
  JoinSessionRequest,
  MonsterSummary,
  PlayerDto,
  PlayerRuntimeState,
  SessionSummary,
  SpellSummary,
  TurnEventDto,
  CharacterDto,
  CharacterCreateUpdateRequest,
} from "@/types";

const BASE_URL = "/api";

function getHeaders(token: string): HeadersInit {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

function getAuthHeaders(token: string): HeadersInit {
  return {
    Authorization: `Bearer ${token}`,
  };
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? `Request failed: ${res.status}`);
  }
  return res.json();
}

/* ── Session endpoints ───────────────────────────────────────── */

export async function createSession(
  token: string,
  body: CreateSessionRequest
): Promise<CreateSessionResponse> {
  const res = await fetch(`${BASE_URL}/sessions`, {
    method: "POST",
    headers: getHeaders(token),
    body: JSON.stringify(body),
  });
  return handleResponse(res);
}

export async function getSessionByCode(
  token: string,
  code: string
): Promise<GameStateDto> {
  const res = await fetch(`${BASE_URL}/sessions/${code}`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function joinSession(
  token: string,
  sessionId: string,
  body: JoinSessionRequest
): Promise<PlayerDto> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/join`, {
    method: "POST",
    headers: getHeaders(token),
    body: JSON.stringify(body),
  });
  return handleResponse(res);
}

export async function startSession(
  token: string,
  sessionId: string
): Promise<GameStateDto> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/start`, {
    method: "POST",
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function getGameState(
  token: string,
  sessionId: string
): Promise<GameStateDto> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/state`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function getSessionHistory(
  token: string,
  sessionId: string
): Promise<TurnEventDto[]> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/history`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function getSessionPlayers(
  token: string,
  sessionId: string
): Promise<PlayerDto[]> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/players`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function getUserSessions(
  token: string
): Promise<SessionSummary[]> {
  const res = await fetch(`${BASE_URL}/sessions/my-sessions`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function leaveSession(
  token: string,
  sessionId: string
): Promise<void> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/leave`, {
    method: "POST",
    headers: getAuthHeaders(token),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? `Request failed: ${res.status}`);
  }
}

export async function deleteSession(
  token: string,
  sessionId: string
): Promise<void> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}`, {
    method: "DELETE",
    headers: getAuthHeaders(token),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? `Request failed: ${res.status}`);
  }
}

export async function kickPlayer(
  token: string,
  sessionId: string,
  playerId: string
): Promise<void> {
  const res = await fetch(
    `${BASE_URL}/sessions/${sessionId}/players/${playerId}`,
    {
      method: "DELETE",
      headers: getAuthHeaders(token),
    }
  );
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? `Request failed: ${res.status}`);
  }
}

export async function getMyRuntimeState(
  token: string,
  sessionId: string
): Promise<PlayerRuntimeState> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/me/state`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function getSessionStates(
  token: string,
  sessionId: string
): Promise<PlayerRuntimeState[]> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/states`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function getActiveCombat(
  token: string,
  sessionId: string
): Promise<CombatStateDto | null> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/combat`, {
    headers: getAuthHeaders(token),
  });
  if (res.status === 204) return null;
  return handleResponse(res);
}

/** Combat spell catalog (metadata for the in-combat cast menu). */
export async function getCombatSpells(token: string): Promise<SpellSummary[]> {
  const res = await fetch(`${BASE_URL}/combat/spells`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

/** Available encounter monsters (for the host's encounter picker). */
export async function getCombatMonsters(token: string): Promise<MonsterSummary[]> {
  const res = await fetch(`${BASE_URL}/combat/monsters`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

/**
 * Host-only upload of a battle-map background image for the active encounter
 * (`multipart/form-data`, field `file`). On success the server pins the new
 * `grid.backgroundImageUrl` and broadcasts a combat refresh — callers only need
 * to trigger this. Surfaces the server's message for 400 (bad type/size),
 * 403 (not host), and 409 (no active encounter).
 */
export async function uploadCombatMap(
  token: string,
  sessionId: string,
  file: File
): Promise<{ url: string }> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/combat/map`, {
    method: "POST",
    // No Content-Type header — the browser sets the multipart boundary itself.
    headers: getAuthHeaders(token),
    body: form,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(
      body?.error ?? body?.message ?? `Upload failed: ${res.status}`
    );
  }
  return res.json();
}

/* ── Character endpoints ─────────────────────────────────────── */

export async function getMyCharacters(
  token: string
): Promise<CharacterDto[]> {
  const res = await fetch(`${BASE_URL}/characters`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function getCharacter(
  token: string,
  id: string
): Promise<CharacterDto> {
  const res = await fetch(`${BASE_URL}/characters/${id}`, {
    headers: getAuthHeaders(token),
  });
  return handleResponse(res);
}

export async function createCharacter(
  token: string,
  body: CharacterCreateUpdateRequest
): Promise<CharacterDto> {
  const res = await fetch(`${BASE_URL}/characters`, {
    method: "POST",
    headers: getHeaders(token),
    body: JSON.stringify(body),
  });
  return handleResponse(res);
}

export async function updateCharacter(
  token: string,
  id: string,
  body: CharacterCreateUpdateRequest
): Promise<CharacterDto> {
  const res = await fetch(`${BASE_URL}/characters/${id}`, {
    method: "PUT",
    headers: getHeaders(token),
    body: JSON.stringify(body),
  });
  return handleResponse(res);
}

export async function deleteCharacter(
  token: string,
  id: string
): Promise<void> {
  const res = await fetch(`${BASE_URL}/characters/${id}`, {
    method: "DELETE",
    headers: getAuthHeaders(token),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? `Request failed: ${res.status}`);
  }
}
