"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { useMyCharacters, useDeleteCharacter } from "@/hooks/useCharacterQueries";
import type { CharacterDto } from "@/types";
import {
  getAbilityModifier,
  formatModifier,
  ABILITY_NAMES,
  type AbilityName,
} from "@/lib/dnd5e";
import { Button, Brand, Alert, Spinner } from "@/components/ui";
import Portrait from "@/components/Portrait";

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
  const { username, logout } = useAuth();
  const charactersQuery = useMyCharacters(!!username);
  const characters = charactersQuery.data ?? [];
  const loading = charactersQuery.isLoading;
  const deleteMutation = useDeleteCharacter();
  const [error, setError] = useState("");
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  useEffect(() => {
    if (charactersQuery.isError) setError("Failed to load characters");
  }, [charactersQuery.isError]);

  async function handleDelete(id: string) {
    if (!username) return;
    try {
      await deleteMutation.mutateAsync(id);
      setConfirmDelete(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to delete character");
    }
  }

  return (
    <main className="min-h-dvh p-4 sm:p-6">
      <div className="mx-auto max-w-4xl">
        {/* Header */}
        <div className="mb-6 flex items-center justify-between">
          <div>
            <Brand size="md" />
            <p className="mt-1 text-sm text-text-muted">
              Logged in as <span className="text-text">{username}</span>
            </p>
          </div>
          <div className="flex gap-2">
            <Button onClick={() => router.push("/")} variant="ghost" size="sm">
              Play
            </Button>
            <Button onClick={logout} variant="ghost" size="sm">
              Logout
            </Button>
          </div>
        </div>

        <hr className="ornament mb-6" />

        {/* Title + Create button */}
        <div className="mb-6 flex items-center justify-between">
          <h2
            className="text-xl font-bold text-text"
            style={{ fontFamily: "var(--font-display)" }}
          >
            My Characters
          </h2>
          <Button onClick={() => router.push("/characters/new")}>
            + New Character
          </Button>
        </div>

        {error && <Alert className="mb-4">{error}</Alert>}

        {loading ? (
          <div className="flex items-center justify-center gap-3 py-12 text-sm text-text-muted">
            <Spinner className="text-accent" /> Loading characters...
          </div>
        ) : characters.length === 0 ? (
          <div className="rounded-xl border border-border-accent bg-surface p-12 text-center panel-corners">
            <p
              className="mb-2 text-lg text-text"
              style={{ fontFamily: "var(--font-display)" }}
            >
              No characters yet
            </p>
            <p className="mb-6 text-sm text-text-muted">
              Create your first D&D 5E character to start adventuring.
            </p>
            <Button onClick={() => router.push("/characters/new")} size="lg">
              Create Your First Character
            </Button>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2">
            {characters.map((c) => (
              <div
                key={c.id}
                className="rounded-xl border border-border bg-surface p-5 transition hover:border-accent/50 hover:shadow-[0_0_24px_var(--color-accent-glow)]"
              >
                {/* Name & class */}
                <div className="mb-4 flex items-start justify-between">
                  <div className="flex items-start gap-3">
                    <Portrait src={c.imageUrl} name={c.name} size="lg" />
                    <div>
                      <h3
                        className="text-lg font-bold text-text"
                        style={{ fontFamily: "var(--font-display)" }}
                      >
                        {c.name}
                      </h3>
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
                  </div>
                  <div className="flex gap-1">
                    <button
                      onClick={() => router.push(`/characters/${c.id}/edit`)}
                      className="cursor-pointer rounded px-2 py-1 text-xs text-text-muted transition hover:text-accent"
                    >
                      Edit
                    </button>
                    {confirmDelete === c.id ? (
                      <div className="flex gap-1">
                        <button
                          onClick={() => handleDelete(c.id)}
                          className="cursor-pointer rounded bg-accent-dark px-2 py-1 text-xs text-white transition hover:bg-accent"
                        >
                          Confirm
                        </button>
                        <button
                          onClick={() => setConfirmDelete(null)}
                          className="cursor-pointer rounded px-2 py-1 text-xs text-text-muted transition hover:text-text"
                        >
                          Cancel
                        </button>
                      </div>
                    ) : (
                      <button
                        onClick={() => setConfirmDelete(c.id)}
                        className="cursor-pointer rounded px-2 py-1 text-xs text-text-muted transition hover:text-accent"
                      >
                        Delete
                      </button>
                    )}
                  </div>
                </div>

                {/* Combat stats */}
                <div className="mb-4 flex gap-3">
                  {(
                    [
                      ["HP", c.hitPoints],
                      ["AC", c.armorClass],
                      ["SPD", c.speed],
                    ] as const
                  ).map(([label, value]) => (
                    <div
                      key={label}
                      className="flex-1 rounded-lg border border-border bg-bg-elevated py-2 text-center"
                    >
                      <div className="tabular text-lg font-bold text-gold">
                        {value}
                      </div>
                      <div className="text-[10px] uppercase tracking-wider text-text-muted">
                        {label}
                      </div>
                    </div>
                  ))}
                </div>

                {/* Ability scores mini */}
                <div className="grid grid-cols-6 gap-1">
                  {ABILITY_NAMES.map((a) => {
                    const score = c[a as keyof CharacterDto] as number;
                    const mod = getAbilityModifier(score);
                    return (
                      <div
                        key={a}
                        className="rounded border border-border bg-bg-elevated p-1 text-center"
                      >
                        <div className="text-[9px] font-bold text-accent">
                          {ABILITY_LABELS[a]}
                        </div>
                        <div className="tabular text-xs font-bold text-text">
                          {score}
                        </div>
                        <div className="tabular text-[9px] text-text-muted">
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
