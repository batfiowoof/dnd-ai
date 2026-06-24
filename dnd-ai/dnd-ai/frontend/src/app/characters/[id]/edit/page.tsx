"use client";

import { useState, useEffect, useMemo, use } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { useCharacter, useUpdateCharacter } from "@/hooks/useCharacterQueries";
import {
  RACES,
  CLASSES,
  BACKGROUNDS,
  ALIGNMENTS,
  ABILITY_NAMES,
  getAbilityModifier,
  formatModifier,
  calculateHitPoints,
  calculateArmorClass,
  type AbilityName,
} from "@/lib/dnd5e";
import { Button, Alert, Spinner } from "@/components/ui";

const ABILITY_LABELS: Record<AbilityName, string> = {
  strength: "STR",
  dexterity: "DEX",
  constitution: "CON",
  intelligence: "INT",
  wisdom: "WIS",
  charisma: "CHA",
};

export default function CharacterEditPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  return (
    <RequireAuth>
      <EditForm characterId={id} />
    </RequireAuth>
  );
}

function EditForm({ characterId }: { characterId: string }) {
  const router = useRouter();
  const { username } = useAuth();
  const characterQuery = useCharacter(characterId, !!username);
  const updateMutation = useUpdateCharacter();

  const loading = characterQuery.isLoading;
  const saving = updateMutation.isPending;
  const [error, setError] = useState("");

  const [name, setName] = useState("");
  const [race, setRace] = useState("");
  const [characterClass, setCharacterClass] = useState("");
  const [level, setLevel] = useState(1);
  const [background, setBackground] = useState("");
  const [alignment, setAlignment] = useState("");
  const [backstory, setBackstory] = useState("");
  const [abilities, setAbilities] = useState<Record<AbilityName, number>>({
    strength: 10,
    dexterity: 10,
    constitution: 10,
    intelligence: 10,
    wisdom: 10,
    charisma: 10,
  });

  // Seed the editable form once the character loads.
  useEffect(() => {
    const c = characterQuery.data;
    if (!c) return;
    setName(c.name);
    setRace(c.race);
    setCharacterClass(c.characterClass);
    setLevel(c.level);
    setBackground(c.background ?? "");
    setAlignment(c.alignment ?? "");
    setBackstory(c.backstory ?? "");
    setAbilities({
      strength: c.strength,
      dexterity: c.dexterity,
      constitution: c.constitution,
      intelligence: c.intelligence,
      wisdom: c.wisdom,
      charisma: c.charisma,
    });
  }, [characterQuery.data]);

  useEffect(() => {
    if (characterQuery.isError) setError("Failed to load character");
  }, [characterQuery.isError]);

  const selectedClass = CLASSES.find((c) => c.name === characterClass);

  const derivedHP = useMemo(() => {
    if (!selectedClass) return 10;
    return calculateHitPoints(selectedClass, abilities.constitution);
  }, [selectedClass, abilities.constitution]);

  const derivedAC = useMemo(
    () => calculateArmorClass(abilities.dexterity),
    [abilities.dexterity]
  );

  const selectedRace = RACES.find((r) => r.name === race);
  const derivedSpeed = selectedRace?.speed ?? 30;

  async function handleSave() {
    if (!username || !name.trim() || !race || !characterClass) return;
    setError("");
    try {
      await updateMutation.mutateAsync({
        id: characterId,
        body: {
          name: name.trim(),
          race,
          characterClass,
          level,
          background,
          alignment,
          strength: abilities.strength,
          dexterity: abilities.dexterity,
          constitution: abilities.constitution,
          intelligence: abilities.intelligence,
          wisdom: abilities.wisdom,
          charisma: abilities.charisma,
          hitPoints: derivedHP,
          armorClass: derivedAC,
          speed: derivedSpeed,
          equipment: selectedClass?.equipment ?? [],
          proficiencies: selectedClass?.proficiencies ?? [],
          features: selectedRace?.traits ?? [],
          backstory,
        },
      });
      router.push("/characters");
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to save character");
    }
  }

  if (loading) {
    return (
      <main className="flex min-h-dvh items-center justify-center">
        <span className="inline-flex items-center gap-3 text-text-muted">
          <Spinner className="text-accent" /> Loading character...
        </span>
      </main>
    );
  }

  return (
    <main className="min-h-dvh p-4">
      <div className="mx-auto max-w-2xl">
        <div className="mb-6 flex items-center justify-between">
          <button
            onClick={() => router.push("/characters")}
            className="cursor-pointer text-sm text-text-muted transition hover:text-accent"
          >
            &larr; Back to Characters
          </button>
          <h1
            className="text-2xl font-bold text-accent"
            style={{ fontFamily: "var(--font-display)" }}
          >
            Edit Character
          </h1>
          <div className="w-24" />
        </div>

        <div className="rounded-xl border border-border-accent bg-surface p-6 space-y-5 panel-corners">
          {/* Name */}
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
              Name
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text outline-none focus:border-accent focus:ring-1 focus:ring-accent"
            />
          </div>

          {/* Race & Class */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                Race
              </label>
              <select
                value={race}
                onChange={(e) => setRace(e.target.value)}
                className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text"
              >
                <option value="">--</option>
                {RACES.map((r) => (
                  <option key={r.name} value={r.name}>
                    {r.name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                Class
              </label>
              <select
                value={characterClass}
                onChange={(e) => setCharacterClass(e.target.value)}
                className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text"
              >
                <option value="">--</option>
                {CLASSES.map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Level */}
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
              Level
            </label>
            <input
              type="number"
              min={1}
              max={20}
              value={level}
              onChange={(e) => setLevel(Number(e.target.value))}
              className="w-24 rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text outline-none focus:border-accent focus:ring-1 focus:ring-accent"
            />
          </div>

          {/* Background & Alignment */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                Background
              </label>
              <select
                value={background}
                onChange={(e) => setBackground(e.target.value)}
                className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text"
              >
                <option value="">--</option>
                {BACKGROUNDS.map((b) => (
                  <option key={b} value={b}>
                    {b}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                Alignment
              </label>
              <select
                value={alignment}
                onChange={(e) => setAlignment(e.target.value)}
                className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text"
              >
                <option value="">--</option>
                {ALIGNMENTS.map((a) => (
                  <option key={a} value={a}>
                    {a}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Ability Scores */}
          <div>
            <label className="mb-2 block text-xs font-semibold uppercase tracking-wider text-text-muted">
              Ability Scores
            </label>
            <div className="grid grid-cols-3 gap-3">
              {ABILITY_NAMES.map((a) => {
                const score = abilities[a];
                const mod = getAbilityModifier(score);
                return (
                  <div
                    key={a}
                    className="rounded-lg border border-border bg-bg-elevated p-3 text-center"
                  >
                    <div className="text-xs font-bold text-accent">
                      {ABILITY_LABELS[a]}
                    </div>
                    <input
                      type="number"
                      min={1}
                      max={30}
                      value={score}
                      onChange={(e) =>
                        setAbilities((prev) => ({
                          ...prev,
                          [a]: Number(e.target.value),
                        }))
                      }
                      className="mx-auto mt-1 w-16 rounded border border-border bg-surface px-2 py-1 text-center text-sm text-text outline-none focus:border-accent"
                    />
                    <div className="mt-0.5 text-xs text-text-muted">
                      {formatModifier(mod)}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Combat stats preview */}
          <div className="flex justify-around rounded-lg border border-border bg-bg-elevated p-3 text-center">
            <div>
              <div className="tabular text-lg font-bold text-gold">{derivedHP}</div>
              <div className="text-[10px] text-text-muted">HP</div>
            </div>
            <div>
              <div className="tabular text-lg font-bold text-gold">{derivedAC}</div>
              <div className="text-[10px] text-text-muted">AC</div>
            </div>
            <div>
              <div className="tabular text-lg font-bold text-gold">
                {derivedSpeed}
              </div>
              <div className="text-[10px] text-text-muted">Speed</div>
            </div>
          </div>

          {/* Backstory */}
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
              Backstory
            </label>
            <textarea
              value={backstory}
              onChange={(e) => setBackstory(e.target.value)}
              rows={4}
              className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none focus:border-accent focus:ring-1 focus:ring-accent"
            />
          </div>

          {/* Error & Actions */}
          {error && <Alert>{error}</Alert>}

          <div className="flex gap-3">
            <Button
              onClick={() => router.push("/characters")}
              variant="ghost"
              fullWidth
            >
              Cancel
            </Button>
            <Button
              onClick={handleSave}
              disabled={!name.trim() || !race || !characterClass}
              loading={saving}
              fullWidth
            >
              {saving ? "Saving..." : "Save Changes"}
            </Button>
          </div>
        </div>
      </div>
    </main>
  );
}
