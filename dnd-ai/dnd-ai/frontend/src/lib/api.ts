import type {
  CreateSessionRequest,
  CreateSessionResponse,
  GameStateDto,
  JoinSessionRequest,
  PlayerDto,
  TurnEventDto,
} from "@/types";

const BASE_URL = "/api";

function getHeaders(username: string): HeadersInit {
  return {
    "Content-Type": "application/json",
    "X-User": username,
  };
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? `Request failed: ${res.status}`);
  }
  return res.json();
}

export async function createSession(
  username: string,
  body: CreateSessionRequest
): Promise<CreateSessionResponse> {
  const res = await fetch(`${BASE_URL}/sessions`, {
    method: "POST",
    headers: getHeaders(username),
    body: JSON.stringify(body),
  });
  return handleResponse(res);
}

export async function getSessionByCode(
  code: string
): Promise<GameStateDto> {
  const res = await fetch(`${BASE_URL}/sessions/${code}`);
  return handleResponse(res);
}

export async function joinSession(
  username: string,
  sessionId: string,
  body: JoinSessionRequest
): Promise<PlayerDto> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/join`, {
    method: "POST",
    headers: getHeaders(username),
    body: JSON.stringify(body),
  });
  return handleResponse(res);
}

export async function startSession(
  username: string,
  sessionId: string
): Promise<GameStateDto> {
  const res = await fetch(`${BASE_URL}/sessions/${sessionId}/start`, {
    method: "POST",
    headers: getHeaders(username),
  });
  return handleResponse(res);
}

export async function getGameState(
  sessionId: string
): Promise<GameStateDto> {
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
