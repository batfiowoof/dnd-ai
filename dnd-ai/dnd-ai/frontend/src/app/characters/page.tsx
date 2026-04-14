"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { getMyCharacters, deleteCharacter } from "@/lib/api";
import type { CharacterDto } from "@/types";
import {
  getAbilityModifier,
  formatModifier,
  ABILITY_NAMES,
  type AbilityName,
} from "@/lib/dnd5e";

const ABILITY_LABELS: Record<AbilityName, string> = {
  strength: "STR",
  dexterity: "DEX",
  constitution: "CON",
  intelligence: "INT",
  wisdom: "WIS",
  charisma: "CHA",
};

export default function CharactersPage() {
  return (
    <RequireAuth>
      <CharactersList />
    </RequireAuth>
  );
}

function CharactersList() {
  const router = useRouter();
  const { username, logout, getToken } = useAuth();
  const [characters, setCharacters] = useState<CharacterDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  useEffect(() => {
    if (!username) return;
    loadCharacters();
  }, [username]);

  async function loadCharacters() {
    if (!username) return;
    setLoading(true);
    try {
      const token = await getToken();
      if (!token) throw new Error("Not authenticated");
      const chars = await getMyCharacters(token);
      setCharacters(chars);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load characters");
    } finally {
      setLoading(false);
    }
  }

  async function handleDelete(id: string) {
    if (!username) return;
    try {
      const token = await getToken();
      if (!token) throw new Error("Not authenticated");
      await deleteCharacter(token, id);
      setCharacters((prev) => prev.filter((c) => c.id !== id));
      setConfirmDelete(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to delete character");
    }
  }

  return (
    <main className="min-h-screen p-4">
      <div className="mx-auto max-w-4xl">
        {/* Header */}
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold text-accent">D&D AI</h1>
            <p className="text-sm text-text-muted">
              Logged in as <span className="text-text">{username}</span>
            </p>
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => router.push("/")}
              className="rounded-lg border border-border px-4 py-2 text-sm text-text-muted transition hover:text-text"
            >
              Play
            </button>
            <button
              onClick={logout}
              className="rounded-lg border border-border px-4 py-2 text-sm text-text-muted transition hover:text-accent"
            >
              Logout
            </button>
          </div>
        </div>

        {/* Title + Create button */}
        <div className="mb-6 flex items-center justify-between">
          <h2 className="text-xl font-bold text-text">My Characters</h2>
          <button
            onClick={() => router.push("/characters/new")}
            className="rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-accent-dark"
          >
            + New Character
          </button>
        </div>

        {error && (
          <p className="mb-4 rounded-lg bg-accent-dark/20 px-3 py-2 text-center text-sm text-accent">
            {error}
          </p>
        )}

        {loading ? (
          <p className="text-center text-sm text-text-muted">
            Loading characters...
          </p>
        ) : characters.length === 0 ? (
          <div className="rounded-xl border border-border-accent bg-surface p-12 text-center">
            <p className="mb-2 text-lg text-text-muted">
              No characters yet
            </p>
            <p className="mb-6 text-sm text-text-muted">
              Create your first D&D 5E character to start adventuring.
            </p>
            <button
              onClick={() => router.push("/characters/new")}
              className="rounded-lg bg-accent px-6 py-3 text-sm font-semibold text-white transition hover:bg-accent-dark"
            >
              Create Your First Character
            </button>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2">
            {characters.map((c) => (
              <div
                key={c.id}
                className="rounded-xl border border-border bg-surface p-5 transition hover:border-accent/50"
              >
                {/* Name & class */}
                <div className="mb-3 flex items-start justify-between">
                  <div>
                    <h3 className="text-lg font-bold text-text">{c.name}</h3>
                    <p className="text-sm text-text-muted">
                      Level {c.level} {c.race} {c.characterClass}
                    </p>
                    {c.background && (
                      <p className="text-xs text-text-muted">
                        {c.background}
                        {c.alignment ? ` \u00B7 ${c.alignment}` : ""}
                      </p>
                    )}
                  </div>
                  <div className="flex gap-1">
                    <button
                      onClick={() => router.push(`/characters/${c.id}/edit`)}
                      className="rounded px-2 py-1 text-xs text-text-muted hover:text-accent"
                    >
                      Edit
                    </button>
                    {confirmDelete === c.id ? (
                      <div className="flex gap-1">
                        <button
                          onClick={() => handleDelete(c.id)}
                          className="rounded bg-accent-dark px-2 py-1 text-xs text-white"
                        >
                          Confirm
                        </button>
                        <button
                          onClick={() => setConfirmDelete(null)}
                          className="rounded px-2 py-1 text-xs text-text-muted"
                        >
                          Cancel
                        </button>
                      </div>
                    ) : (
                      <button
                        onClick={() => setConfirmDelete(c.id)}
                        className="rounded px-2 py-1 text-xs text-text-muted hover:text-accent"
                      >
                        Delete
                      </button>
                    )}
                  </div>
                </div>

                {/* Combat stats */}
                <div className="mb-3 flex gap-4 text-center">
                  <div>
                    <div className="text-lg font-bold text-accent">
                      {c.hitPoints}
                    </div>
                    <div className="text-[10px] text-text-muted">HP</div>
                  </div>
                  <div>
                    <div className="text-lg font-bold text-accent">
                      {c.armorClass}
                    </div>
                    <div className="text-[10px] text-text-muted">AC</div>
                  </div>
                  <div>
                    <div className="text-lg font-bold text-accent">
                      {c.speed}
                    </div>
                    <div className="text-[10px] text-text-muted">Speed</div>
                  </div>
                </div>

                {/* Ability scores mini */}
                <div className="grid grid-cols-6 gap-1">
                  {ABILITY_NAMES.map((a) => {
                    const score = c[a as keyof CharacterDto] as number;
                    const mod = getAbilityModifier(score);
                    return (
                      <div
                        key={a}
                        className="rounded border border-border bg-bg p-1 text-center"
                      >
                        <div className="text-[9px] font-bold text-accent">
                          {ABILITY_LABELS[a]}
                        </div>
                        <div className="text-xs font-bold text-text">
                          {score}
                        </div>
                        <div className="text-[9px] text-text-muted">
                          {formatModifier(mod)}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </main>
  );
}
