"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { createSession, getSessionByCode, joinSession } from "@/lib/api";

export default function LandingPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [characterName, setCharacterName] = useState("");
  const [joinCode, setJoinCode] = useState("");
  const [mode, setMode] = useState<"idle" | "create" | "join">("idle");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function handleCreate() {
    if (!username.trim() || !characterName.trim()) {
      setError("Username and character name are required.");
      return;
    }
    setError("");
    setLoading(true);
    try {
      const res = await createSession(username, {
        playerName: username,
        characterName,
      });
      localStorage.setItem("dnd-username", username);
      localStorage.setItem("dnd-characterName", characterName);
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
    if (!username.trim() || !characterName.trim() || !joinCode.trim()) {
      setError("All fields are required.");
      return;
    }
    setError("");
    setLoading(true);
    try {
      const gameState = await getSessionByCode(joinCode.toUpperCase());
      const player = await joinSession(username, gameState.sessionId, {
        playerName: username,
        characterName,
      });
      localStorage.setItem("dnd-username", username);
      localStorage.setItem("dnd-characterName", characterName);
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
        <p className="mb-8 text-center text-sm text-text-muted">
          AI Dungeon Master
        </p>

        {/* Username & Character */}
        <div className="mb-6 space-y-3">
          <input
            type="text"
            placeholder="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="w-full rounded-lg border border-border bg-bg px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent"
          />
          <input
            type="text"
            placeholder="Character Name"
            value={characterName}
            onChange={(e) => setCharacterName(e.target.value)}
            className="w-full rounded-lg border border-border bg-bg px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent"
          />
        </div>

        {/* Mode selection */}
        {mode === "idle" && (
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
              disabled={loading}
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
              disabled={loading}
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
