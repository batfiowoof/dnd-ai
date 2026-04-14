"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import {
  createSession,
  getSessionByCode,
  joinSession,
  getMyCharacters,
} from "@/lib/api";
import type { CharacterDto } from "@/types";

export default function LandingPage() {
  return (
    <RequireAuth>
      <LandingContent />
    </RequireAuth>
  );
}

function LandingContent() {
  const router = useRouter();
  const { username, logout, getToken } = useAuth();

  const [joinCode, setJoinCode] = useState("");
  const [mode, setMode] = useState<"idle" | "create" | "join">("idle");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  // Characters
  const [characters, setCharacters] = useState<CharacterDto[]>([]);
  const [selectedCharId, setSelectedCharId] = useState<string>("");
  const [charsLoading, setCharsLoading] = useState(true);

  useEffect(() => {
    if (!username) return;
    setCharsLoading(true);
    getToken()
      .then((t) => {
        if (!t) return Promise.resolve([]);
        return getMyCharacters(t);
      })
      .then((chars) => {
        setCharacters(chars);
        if (chars.length > 0) setSelectedCharId(chars[0].id);
      })
      .catch(() => {})
      .finally(() => setCharsLoading(false));
  }, [username, getToken]);

  const selectedChar = characters.find((c) => c.id === selectedCharId);

  async function handleCreate() {
    if (!username || !selectedCharId) {
      setError("Please select a character.");
      return;
    }
    setError("");
    setLoading(true);
    try {
      const token = await getToken();
      if (!token) throw new Error("Not authenticated");
      const res = await createSession(token, {
        playerName: username,
        characterId: selectedCharId,
      });
      localStorage.setItem(`dnd-playerId-${res.sessionId}`, res.playerId);
      localStorage.setItem(`dnd-joinCode-${res.sessionId}`, res.joinCode);
      router.push(`/lobby/${res.sessionId}`);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to create session");
    } finally {
      setLoading(false);
    }
  }

  async function handleJoin() {
    if (!username || !selectedCharId || !joinCode.trim()) {
      setError("Please select a character and enter a join code.");
      return;
    }
    setError("");
    setLoading(true);
    try {
      const token = await getToken();
      if (!token) throw new Error("Not authenticated");
      const gameState = await getSessionByCode(joinCode.toUpperCase());
      const player = await joinSession(token, gameState.sessionId, {
        playerName: username,
        characterId: selectedCharId,
      });
      localStorage.setItem(
        `dnd-playerId-${gameState.sessionId}`,
        player.id
      );
      localStorage.setItem(
        `dnd-joinCode-${gameState.sessionId}`,
        gameState.joinCode
      );
      router.push(`/lobby/${gameState.sessionId}`);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to join session");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <div className="w-full max-w-md rounded-xl border border-border-accent bg-surface p-8 glow">
        <h1 className="mb-2 text-center text-4xl font-bold tracking-wider text-accent">
          D&D AI
        </h1>
        <p className="mb-2 text-center text-sm text-text-muted">
          AI Dungeon Master
        </p>
        <p className="mb-6 text-center text-xs text-text-muted">
          Logged in as <span className="text-text">{username}</span>
          <button
            onClick={logout}
            className="ml-2 text-accent hover:underline"
          >
            logout
          </button>
        </p>

        {/* Character Selection */}
        <div className="mb-6">
          <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
            Select Character
          </label>
          {charsLoading ? (
            <p className="text-sm text-text-muted">Loading characters...</p>
          ) : characters.length === 0 ? (
            <div className="rounded-lg border border-border bg-bg p-4 text-center">
              <p className="mb-2 text-sm text-text-muted">
                No characters found
              </p>
              <button
                onClick={() => router.push("/characters/new")}
                className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white transition hover:bg-accent-dark"
              >
                Create Your First Character
              </button>
            </div>
          ) : (
            <>
              <select
                value={selectedCharId}
                onChange={(e) => setSelectedCharId(e.target.value)}
                className="w-full rounded-lg border border-border bg-bg px-4 py-2.5 text-sm text-text outline-none transition focus:border-accent focus:ring-1 focus:ring-accent"
              >
                {characters.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} — Lv{c.level} {c.race} {c.characterClass}
                  </option>
                ))}
              </select>
              {selectedChar && (
                <div className="mt-2 flex items-center justify-between rounded-lg border border-border bg-bg px-3 py-2">
                  <div className="text-xs text-text-muted">
                    HP {selectedChar.hitPoints} | AC{" "}
                    {selectedChar.armorClass} | Speed{" "}
                    {selectedChar.speed}
                  </div>
                  <button
                    onClick={() => router.push("/characters")}
                    className="text-xs text-accent hover:underline"
                  >
                    Manage
                  </button>
                </div>
              )}
            </>
          )}
        </div>

        {/* Mode selection */}
        {mode === "idle" && characters.length > 0 && (
          <div className="flex gap-3">
            <button
              onClick={() => setMode("create")}
              className="flex-1 rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-accent-dark"
            >
              Create Session
            </button>
            <button
              onClick={() => setMode("join")}
              className="flex-1 rounded-lg border border-accent px-4 py-2.5 text-sm font-semibold text-accent transition hover:bg-accent hover:text-white"
            >
              Join Session
            </button>
          </div>
        )}

        {/* Create */}
        {mode === "create" && (
          <div className="space-y-3">
            <button
              onClick={handleCreate}
              disabled={loading || !selectedCharId}
              className="w-full rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-accent-dark disabled:opacity-50"
            >
              {loading ? "Creating..." : "Create New Session"}
            </button>
            <button
              onClick={() => {
                setMode("idle");
                setError("");
              }}
              className="w-full text-sm text-text-muted hover:text-text"
            >
              Back
            </button>
          </div>
        )}

        {/* Join */}
        {mode === "join" && (
          <div className="space-y-3">
            <input
              type="text"
              placeholder="Join Code (6 characters)"
              maxLength={6}
              value={joinCode}
              onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
              className="w-full rounded-lg border border-border bg-bg px-4 py-2.5 text-center text-lg font-mono tracking-widest text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent"
            />
            <button
              onClick={handleJoin}
              disabled={loading || !selectedCharId}
              className="w-full rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-accent-dark disabled:opacity-50"
            >
              {loading ? "Joining..." : "Join Session"}
            </button>
            <button
              onClick={() => {
                setMode("idle");
                setError("");
              }}
              className="w-full text-sm text-text-muted hover:text-text"
            >
              Back
            </button>
          </div>
        )}

        {error && (
          <p className="mt-4 rounded-lg bg-accent-dark/20 px-3 py-2 text-center text-sm text-accent">
            {error}
          </p>
        )}
      </div>
    </main>
  );
}
