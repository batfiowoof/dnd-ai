import type {
  CreateSessionRequest,
  CreateSessionResponse,
  GameStateDto,
  JoinSessionRequest,
  PlayerDto,
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

export async function getSessionByCode(code: string): Promise<GameStateDto> {
  const res = await fetch(`${BASE_URL}/sessions/${code}`);
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

export async function getGameState(sessionId: string): Promise<GameStateDto> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/state`);
  return handleResponse(res);
}

export async function getSessionHistory(
  sessionId: string
): Promise<TurnEventDto[]> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/history`);
  return handleResponse(res);
}

export async function getSessionPlayers(
  sessionId: string
): Promise<PlayerDto[]> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/players`);
  return handleResponse(res);
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
