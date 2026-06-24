"use client";

import { useState, useMemo } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { useCreateCharacter } from "@/hooks/useCharacterQueries";
import {
  RACES,
  CLASSES,
  BACKGROUNDS,
  ALIGNMENTS,
  ABILITY_NAMES,
  POINT_BUY_COSTS,
  POINT_BUY_TOTAL,
  STANDARD_ARRAY,
  getAbilityModifier,
  formatModifier,
  calculateHitPoints,
  calculateArmorClass,
  type AbilityName,
  type RaceInfo,
  type ClassInfo,
} from "@/lib/dnd5e";
import { Button, Alert, cn } from "@/components/ui";

type AbilityMethod = "standard" | "pointbuy";

const ABILITY_LABELS: Record<AbilityName, string> = {
  strength: "STR",
  dexterity: "DEX",
  constitution: "CON",
  intelligence: "INT",
  wisdom: "WIS",
  charisma: "CHA",
};

const ABILITY_DESCRIPTIONS: Record<AbilityName, string> = {
  strength: "Physical power. Affects melee attacks, carrying capacity, and Athletics checks.",
  dexterity: "Agility and reflexes. Affects AC, ranged attacks, initiative, and Acrobatics/Stealth.",
  constitution: "Endurance and health. Affects hit points and Constitution saving throws.",
  intelligence: "Learning and reasoning. Affects Arcana, History, Investigation, Nature, Religion.",
  wisdom: "Perception and insight. Affects Perception, Insight, Medicine, Survival, Animal Handling.",
  charisma: "Force of personality. Affects Deception, Intimidation, Performance, Persuasion.",
};

/* ── Guide step navigation ───────────────────────────────────── */
const STEPS = [
  "Race",
  "Class",
  "Abilities",
  "Details",
  "Review",
] as const;
type Step = (typeof STEPS)[number];

export default function CharacterCreatePage() {
  return (
    <RequireAuth>
      <CharacterCreateForm />
    </RequireAuth>
  );
}

function CharacterCreateForm() {
  const router = useRouter();
  const { username } = useAuth();
  const createMutation = useCreateCharacter();
  const saving = createMutation.isPending;

  const [step, setStep] = useState<Step>("Race");
  const [error, setError] = useState("");

  // Character state
  const [name, setName] = useState("");
  const [selectedRace, setSelectedRace] = useState<RaceInfo | null>(null);
  const [selectedClass, setSelectedClass] = useState<ClassInfo | null>(null);
  const [background, setBackground] = useState("");
  const [alignment, setAlignment] = useState("");
  const [backstory, setBackstory] = useState("");

  // Abilities
  const [abilityMethod, setAbilityMethod] = useState<AbilityMethod>("standard");
  const [baseAbilities, setBaseAbilities] = useState<Record<AbilityName, number>>({
    strength: 10,
    dexterity: 10,
    constitution: 10,
    intelligence: 10,
    wisdom: 10,
    charisma: 10,
  });
  const [standardAssignments, setStandardAssignments] = useState<
    Record<AbilityName, number | null>
  >({
    strength: null,
    dexterity: null,
    constitution: null,
    intelligence: null,
    wisdom: null,
    charisma: null,
  });

  // Computed final abilities (base + racial bonuses)
  const finalAbilities = useMemo(() => {
    const base =
      abilityMethod === "standard"
        ? Object.fromEntries(
            ABILITY_NAMES.map((a) => [a, standardAssignments[a] ?? 8])
          )
        : { ...baseAbilities };

    if (selectedRace) {
      for (const [ability, bonus] of Object.entries(
        selectedRace.abilityBonuses
      )) {
        if (ability in base) {
          (base as Record<string, number>)[ability] += bonus;
        }
      }
    }

    return base as Record<AbilityName, number>;
  }, [baseAbilities, standardAssignments, abilityMethod, selectedRace]);

  const pointBuySpent = useMemo(() => {
    if (abilityMethod !== "pointbuy") return 0;
    return ABILITY_NAMES.reduce(
      (sum, a) => sum + (POINT_BUY_COSTS[baseAbilities[a]] ?? 0),
      0
    );
  }, [baseAbilities, abilityMethod]);

  const usedStandardValues = useMemo(() => {
    return Object.values(standardAssignments).filter(
      (v) => v !== null
    ) as number[];
  }, [standardAssignments]);

  const derivedHP = useMemo(() => {
    if (!selectedClass) return 10;
    return calculateHitPoints(selectedClass, finalAbilities.constitution);
  }, [selectedClass, finalAbilities.constitution]);

  const derivedAC = useMemo(
    () => calculateArmorClass(finalAbilities.dexterity),
    [finalAbilities.dexterity]
  );

  const derivedSpeed = selectedRace?.speed ?? 30;

  const stepIndex = STEPS.indexOf(step);

  function canProceed(): boolean {
    switch (step) {
      case "Race":
        return !!selectedRace;
      case "Class":
        return !!selectedClass;
      case "Abilities":
        if (abilityMethod === "standard") {
          return ABILITY_NAMES.every((a) => standardAssignments[a] !== null);
        }
        return pointBuySpent <= POINT_BUY_TOTAL;
      case "Details":
        return !!name.trim();
      case "Review":
        return true;
    }
  }

  function nextStep() {
    if (stepIndex < STEPS.length - 1) {
      setStep(STEPS[stepIndex + 1]);
    }
  }
  function prevStep() {
    if (stepIndex > 0) {
      setStep(STEPS[stepIndex - 1]);
    }
  }

  async function handleSave() {
    if (!username || !selectedRace || !selectedClass || !name.trim()) return;
    setError("");
    try {
      await createMutation.mutateAsync({
        name: name.trim(),
        race: selectedRace.name,
        characterClass: selectedClass.name,
        level: 1,
        background,
        alignment,
        strength: finalAbilities.strength,
        dexterity: finalAbilities.dexterity,
        constitution: finalAbilities.constitution,
        intelligence: finalAbilities.intelligence,
        wisdom: finalAbilities.wisdom,
        charisma: finalAbilities.charisma,
        hitPoints: derivedHP,
        armorClass: derivedAC,
        speed: derivedSpeed,
        equipment: selectedClass.equipment,
        proficiencies: selectedClass.proficiencies,
        features: selectedRace.traits,
        backstory,
      });
      router.push("/characters");
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to save character");
    }
  }

  return (
    <main className="min-h-dvh p-4 sm:p-6">
      <div className="mx-auto max-w-4xl">
        {/* Header */}
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
            Create Character
          </h1>
          <div className="w-24" />
        </div>

        {/* Step indicator */}
        <div className="mb-8 flex items-center justify-center gap-1">
          {STEPS.map((s, i) => (
            <div key={s} className="flex items-center">
              <button
                onClick={() => i <= stepIndex && setStep(s)}
                className={cn(
                  "rounded-full px-3 py-1 text-xs font-semibold transition",
                  s === step
                    ? "bg-accent text-white shadow-[0_0_16px_var(--color-accent-glow)]"
                    : i < stepIndex
                      ? "cursor-pointer bg-accent-dark/30 text-accent hover:bg-accent-dark/50"
                      : "bg-surface-light text-text-muted"
                )}
              >
                <span className="tabular">{i + 1}.</span> {s}
              </button>
              {i < STEPS.length - 1 && (
                <div
                  className={cn(
                    "mx-1 h-px w-6 transition",
                    i < stepIndex ? "bg-accent" : "bg-border"
                  )}
                />
              )}
            </div>
          ))}
        </div>

        <div className="rounded-xl border border-border-accent bg-surface p-6 panel-corners">
          {/* ── STEP: Race ────────────────────────────────────── */}
          {step === "Race" && (
            <div>
              <h2 className="mb-2 text-lg font-bold text-accent">
                Choose Your Race
              </h2>
              <div className="mb-4 rounded-lg bg-bg-elevated p-3 text-xs text-text-muted">
                <strong className="text-text">D&D 5E Guide:</strong> Your race
                determines your character&apos;s physical traits, ability score
                bonuses, speed, and special racial features. Each race brings
                unique strengths to your character.
              </div>
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {RACES.map((race) => (
                  <button
                    key={race.name}
                    onClick={() => setSelectedRace(race)}
                    className={`rounded-lg border p-4 text-left transition ${
                      selectedRace?.name === race.name
                        ? "border-accent bg-accent-glow"
                        : "border-border bg-bg-elevated hover:border-accent/50"
                    }`}
                  >
                    <div className="mb-1 font-semibold text-text">
                      {race.name}
                    </div>
                    <p className="mb-2 text-xs text-text-muted">
                      {race.description}
                    </p>
                    <div className="text-xs text-accent">
                      {Object.entries(race.abilityBonuses)
                        .map(
                          ([a, b]) =>
                            `${a.slice(0, 3).toUpperCase()} +${b}`
                        )
                        .join(", ")}
                    </div>
                    <div className="mt-1 text-xs text-text-muted">
                      Speed: {race.speed} ft
                    </div>
                    {selectedRace?.name === race.name && (
                      <div className="mt-2 border-t border-border pt-2">
                        <div className="text-xs font-semibold text-text-muted">
                          Racial Traits:
                        </div>
                        {race.traits.map((t) => (
                          <span
                            key={t}
                            className="mr-1 mt-1 inline-block rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                          >
                            {t}
                          </span>
                        ))}
                      </div>
                    )}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* ── STEP: Class ───────────────────────────────────── */}
          {step === "Class" && (
            <div>
              <h2 className="mb-2 text-lg font-bold text-accent">
                Choose Your Class
              </h2>
              <div className="mb-4 rounded-lg bg-bg-elevated p-3 text-xs text-text-muted">
                <strong className="text-text">D&D 5E Guide:</strong> Your class
                is your primary adventuring role. It determines your hit points,
                abilities, and equipment. Your class defines how you interact
                with the world and what you can do in combat.
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {CLASSES.map((cls) => (
                  <button
                    key={cls.name}
                    onClick={() => setSelectedClass(cls)}
                    className={`rounded-lg border p-4 text-left transition ${
                      selectedClass?.name === cls.name
                        ? "border-accent bg-accent-glow"
                        : "border-border bg-bg-elevated hover:border-accent/50"
                    }`}
                  >
                    <div className="mb-1 flex items-center justify-between">
                      <span className="font-semibold text-text">
                        {cls.name}
                      </span>
                      <span className="text-xs text-accent">
                        d{cls.hitDie} HD
                      </span>
                    </div>
                    <p className="mb-2 text-xs text-text-muted">
                      {cls.description}
                    </p>
                    <div className="text-xs text-text-muted">
                      Primary: {cls.primaryAbility}
                    </div>
                    <div className="text-xs text-text-muted">
                      Saves: {cls.savingThrows.join(", ")}
                    </div>
                    {selectedClass?.name === cls.name && (
                      <div className="mt-2 border-t border-border pt-2">
                        <div className="text-xs font-semibold text-text-muted mb-1">
                          Starting Equipment:
                        </div>
                        {cls.equipment.map((e) => (
                          <span
                            key={e}
                            className="mr-1 mt-1 inline-block rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                          >
                            {e}
                          </span>
                        ))}
                        <div className="mt-2 text-xs font-semibold text-text-muted mb-1">
                          Proficiencies:
                        </div>
                        {cls.proficiencies.map((p) => (
                          <span
                            key={p}
                            className="mr-1 mt-1 inline-block rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                          >
                            {p}
                          </span>
                        ))}
                      </div>
                    )}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* ── STEP: Abilities ───────────────────────────────── */}
          {step === "Abilities" && (
            <div>
              <h2 className="mb-2 text-lg font-bold text-accent">
                Ability Scores
              </h2>
              <div className="mb-4 rounded-lg bg-bg-elevated p-3 text-xs text-text-muted">
                <strong className="text-text">D&D 5E Guide:</strong> Six
                abilities define your character: Strength, Dexterity,
                Constitution, Intelligence, Wisdom, and Charisma. Higher scores
                give bonuses to rolls. Choose{" "}
                <strong>Standard Array</strong> (assign the values 15, 14, 13,
                12, 10, 8) or <strong>Point Buy</strong> (spend 27 points to
                set scores from 8-15). Racial bonuses are added automatically.
              </div>

              {/* Method toggle */}
              <div className="mb-4 flex gap-2">
                <button
                  onClick={() => setAbilityMethod("standard")}
                  className={`rounded-lg px-4 py-2 text-sm font-semibold transition ${
                    abilityMethod === "standard"
                      ? "bg-accent text-white"
                      : "border border-border text-text-muted hover:text-text"
                  }`}
                >
                  Standard Array
                </button>
                <button
                  onClick={() => setAbilityMethod("pointbuy")}
                  className={`rounded-lg px-4 py-2 text-sm font-semibold transition ${
                    abilityMethod === "pointbuy"
                      ? "bg-accent text-white"
                      : "border border-border text-text-muted hover:text-text"
                  }`}
                >
                  Point Buy
                </button>
              </div>

              {abilityMethod === "pointbuy" && (
                <div className="mb-4 text-sm">
                  Points spent:{" "}
                  <span
                    className={
                      pointBuySpent > POINT_BUY_TOTAL
                        ? "text-red-500 font-bold"
                        : "text-accent font-bold"
                    }
                  >
                    {pointBuySpent}
                  </span>{" "}
                  / {POINT_BUY_TOTAL}
                </div>
              )}

              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {ABILITY_NAMES.map((ability) => {
                  const racialBonus =
                    selectedRace?.abilityBonuses[ability] ?? 0;
                  const finalScore = finalAbilities[ability];
                  const mod = getAbilityModifier(finalScore);

                  return (
                    <div
                      key={ability}
                      className="rounded-lg border border-border bg-bg-elevated p-4"
                    >
                      <div className="mb-1 flex items-center justify-between">
                        <span className="text-sm font-bold text-accent">
                          {ABILITY_LABELS[ability]}
                        </span>
                        <span className="text-lg font-bold text-text">
                          {finalScore}{" "}
                          <span className="text-sm text-text-muted">
                            ({formatModifier(mod)})
                          </span>
                        </span>
                      </div>
                      <p className="mb-2 text-xs text-text-muted">
                        {ABILITY_DESCRIPTIONS[ability]}
                      </p>

                      {abilityMethod === "standard" ? (
                        <select
                          value={standardAssignments[ability] ?? ""}
                          onChange={(e) => {
                            const val = e.target.value
                              ? Number(e.target.value)
                              : null;
                            setStandardAssignments((prev) => ({
                              ...prev,
                              [ability]: val,
                            }));
                          }}
                          className="w-full rounded border border-border bg-surface px-2 py-1.5 text-sm text-text"
                        >
                          <option value="">-- Assign --</option>
                          {STANDARD_ARRAY.map((v) => (
                            <option
                              key={v}
                              value={v}
                              disabled={
                                usedStandardValues.includes(v) &&
                                standardAssignments[ability] !== v
                              }
                            >
                              {v}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <div className="flex items-center gap-2">
                          <button
                            onClick={() =>
                              setBaseAbilities((prev) => ({
                                ...prev,
                                [ability]: Math.max(8, prev[ability] - 1),
                              }))
                            }
                            disabled={baseAbilities[ability] <= 8}
                            className="rounded bg-surface-light px-2 py-1 text-sm font-bold text-text-muted hover:text-accent disabled:opacity-30"
                          >
                            -
                          </button>
                          <span className="w-8 text-center text-sm font-bold">
                            {baseAbilities[ability]}
                          </span>
                          <button
                            onClick={() =>
                              setBaseAbilities((prev) => ({
                                ...prev,
                                [ability]: Math.min(15, prev[ability] + 1),
                              }))
                            }
                            disabled={baseAbilities[ability] >= 15}
                            className="rounded bg-surface-light px-2 py-1 text-sm font-bold text-text-muted hover:text-accent disabled:opacity-30"
                          >
                            +
                          </button>
                          <span className="ml-auto text-xs text-text-muted">
                            Cost: {POINT_BUY_COSTS[baseAbilities[ability]] ?? 0}
                          </span>
                        </div>
                      )}

                      {racialBonus > 0 && (
                        <div className="mt-1 text-xs text-accent">
                          +{racialBonus} from {selectedRace?.name}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* ── STEP: Details ─────────────────────────────────── */}
          {step === "Details" && (
            <div>
              <h2 className="mb-2 text-lg font-bold text-accent">
                Character Details
              </h2>
              <div className="mb-4 rounded-lg bg-bg-elevated p-3 text-xs text-text-muted">
                <strong className="text-text">D&D 5E Guide:</strong> Give your
                character a name, choose a background that describes their life
                before adventuring, select an alignment that reflects their
                moral compass, and write a short backstory to bring them to
                life.
              </div>

              <div className="space-y-4">
                <div>
                  <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                    Character Name *
                  </label>
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="e.g. Thorin Ironforge"
                    className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent"
                  />
                </div>

                <div>
                  <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                    Background
                  </label>
                  <select
                    value={background}
                    onChange={(e) => setBackground(e.target.value)}
                    className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text"
                  >
                    <option value="">-- Select Background --</option>
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
                    <option value="">-- Select Alignment --</option>
                    {ALIGNMENTS.map((a) => (
                      <option key={a} value={a}>
                        {a}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="mb-1 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                    Backstory
                  </label>
                  <textarea
                    value={backstory}
                    onChange={(e) => setBackstory(e.target.value)}
                    placeholder="Describe your character's history, motivations, and goals..."
                    rows={4}
                    className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent"
                  />
                </div>
              </div>
            </div>
          )}

          {/* ── STEP: Review ──────────────────────────────────── */}
          {step === "Review" && (
            <div>
              <h2 className="mb-4 text-lg font-bold text-accent">
                Review Your Character
              </h2>

              <div className="grid gap-4 sm:grid-cols-2">
                {/* Identity */}
                <div className="rounded-lg border border-border bg-bg-elevated p-4">
                  <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                    Identity
                  </h3>
                  <div className="space-y-1 text-sm">
                    <div>
                      <span className="text-text-muted">Name:</span>{" "}
                      <span className="font-semibold text-text">
                        {name || "Unnamed"}
                      </span>
                    </div>
                    <div>
                      <span className="text-text-muted">Race:</span>{" "}
                      <span className="text-text">
                        {selectedRace?.name ?? "—"}
                      </span>
                    </div>
                    <div>
                      <span className="text-text-muted">Class:</span>{" "}
                      <span className="text-text">
                        {selectedClass?.name ?? "—"}
                      </span>
                    </div>
                    <div>
                      <span className="text-text-muted">Background:</span>{" "}
                      <span className="text-text">{background || "—"}</span>
                    </div>
                    <div>
                      <span className="text-text-muted">Alignment:</span>{" "}
                      <span className="text-text">{alignment || "—"}</span>
                    </div>
                  </div>
                </div>

                {/* Combat Stats */}
                <div className="rounded-lg border border-border bg-bg-elevated p-4">
                  <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                    Combat
                  </h3>
                  <div className="flex justify-around text-center">
                    <div>
                      <div className="tabular text-2xl font-bold text-gold">
                        {derivedHP}
                      </div>
                      <div className="text-xs text-text-muted">HP</div>
                    </div>
                    <div>
                      <div className="tabular text-2xl font-bold text-gold">
                        {derivedAC}
                      </div>
                      <div className="text-xs text-text-muted">AC</div>
                    </div>
                    <div>
                      <div className="tabular text-2xl font-bold text-gold">
                        {derivedSpeed}
                      </div>
                      <div className="text-xs text-text-muted">Speed</div>
                    </div>
                    <div>
                      <div className="tabular text-2xl font-bold text-gold">
                        d{selectedClass?.hitDie ?? "?"}
                      </div>
                      <div className="text-xs text-text-muted">Hit Die</div>
                    </div>
                  </div>
                </div>

                {/* Ability Scores */}
                <div className="rounded-lg border border-border bg-bg-elevated p-4 sm:col-span-2">
                  <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                    Ability Scores
                  </h3>
                  <div className="grid grid-cols-6 gap-2 text-center">
                    {ABILITY_NAMES.map((a) => {
                      const score = finalAbilities[a];
                      const mod = getAbilityModifier(score);
                      return (
                        <div
                          key={a}
                          className="rounded border border-border p-2"
                        >
                          <div className="text-xs font-bold text-accent">
                            {ABILITY_LABELS[a]}
                          </div>
                          <div className="text-lg font-bold text-text">
                            {score}
                          </div>
                          <div className="text-xs text-text-muted">
                            {formatModifier(mod)}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* Equipment */}
                {selectedClass && (
                  <div className="rounded-lg border border-border bg-bg-elevated p-4">
                    <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                      Equipment
                    </h3>
                    <div className="flex flex-wrap gap-1">
                      {selectedClass.equipment.map((e) => (
                        <span
                          key={e}
                          className="rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                        >
                          {e}
                        </span>
                      ))}
                    </div>
                  </div>
                )}

                {/* Traits & Features */}
                {selectedRace && (
                  <div className="rounded-lg border border-border bg-bg-elevated p-4">
                    <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                      Racial Traits
                    </h3>
                    <div className="flex flex-wrap gap-1">
                      {selectedRace.traits.map((t) => (
                        <span
                          key={t}
                          className="rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                        >
                          {t}
                        </span>
                      ))}
                    </div>
                  </div>
                )}

                {/* Backstory */}
                {backstory && (
                  <div className="rounded-lg border border-border bg-bg-elevated p-4 sm:col-span-2">
                    <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                      Backstory
                    </h3>
                    <p className="text-sm text-text-muted whitespace-pre-wrap">
                      {backstory}
                    </p>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* ── Navigation / Error ────────────────────────────── */}
          {error && <Alert className="mt-4">{error}</Alert>}

          <div className="mt-6 flex items-center justify-between">
            <Button
              onClick={prevStep}
              disabled={stepIndex === 0}
              variant="ghost"
            >
              &larr; Back
            </Button>

            {step === "Review" ? (
              <Button onClick={handleSave} loading={saving} size="lg">
                {saving ? "Saving..." : "Create Character"}
              </Button>
            ) : (
              <Button onClick={nextStep} disabled={!canProceed()} size="lg">
                Next &rarr;
              </Button>
            )}
          </div>
        </div>
      </div>
    </main>
  );
}
