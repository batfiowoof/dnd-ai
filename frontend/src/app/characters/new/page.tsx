"use client";

import { useState, useMemo, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { useCreateCharacter } from "@/hooks/useCharacterQueries";
import {
  useRaces,
  useClasses,
  useAlignments,
  useClassSpells,
  useStartingEquipment,
  useEquipmentCategory,
} from "@/hooks/useDnd5eData";
import {
  BACKGROUNDS,
  ABILITY_NAMES,
  POINT_BUY_COSTS,
  POINT_BUY_TOTAL,
  STANDARD_ARRAY,
  resolveEquipment,
  equipmentToStrings,
  equipmentSelectionComplete,
  catKey,
  getAbilityModifier,
  formatModifier,
  calculateHitPoints,
  calculateArmorClass,
  type AbilityName,
  type RaceInfo,
  type ClassInfo,
} from "@/lib/dnd5e";
import { SPELLCASTING, isCaster, type Spell } from "@/lib/spells";
import { Button, Alert, Spinner, cn } from "@/components/ui";
import Portrait from "@/components/Portrait";

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
// The Spells step only appears for classes that pick spells at level 1, so the
// step list is derived from the chosen class (see `steps` in the component).
type Step =
  | "Race"
  | "Class"
  | "Abilities"
  | "Equipment"
  | "Spells"
  | "Details"
  | "Review";

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

  // ── 5e reference data (dnd5eapi.co, cached) ──────────────────
  const racesQuery = useRaces();
  const classesQuery = useClasses();
  const alignmentsQuery = useAlignments();

  const [step, setStep] = useState<Step>("Race");
  const [error, setError] = useState("");

  // Character state
  const [name, setName] = useState("");
  const [selectedRace, setSelectedRace] = useState<RaceInfo | null>(null);
  const [selectedClass, setSelectedClass] = useState<ClassInfo | null>(null);
  const [background, setBackground] = useState("");
  const [alignment, setAlignment] = useState("");
  const [backstory, setBackstory] = useState("");
  const [imageUrl, setImageUrl] = useState("");

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

  // Equipment & spells — reset whenever the class changes.
  const [equipmentSelections, setEquipmentSelections] = useState<number[]>([]);
  const [categoryChoices, setCategoryChoices] = useState<Record<string, string>>({});
  const [selectedCantrips, setSelectedCantrips] = useState<string[]>([]);
  const [selectedSpells, setSelectedSpells] = useState<string[]>([]);

  const caster = selectedClass ? isCaster(selectedClass.name) : false;
  const spellCaps = selectedClass ? SPELLCASTING[selectedClass.name] : undefined;

  // Class-dependent catalog fetches (gated on a chosen class).
  const equipmentQuery = useStartingEquipment(selectedClass?.index, !!selectedClass);
  const spellsQuery = useClassSpells(selectedClass?.index, caster);

  const classEquip = equipmentQuery.data ?? null;

  useEffect(() => {
    setEquipmentSelections([]);
    setCategoryChoices({});
    setSelectedCantrips([]);
    setSelectedSpells([]);
  }, [selectedClass?.index]);

  const resolvedItems = useMemo(
    () =>
      classEquip
        ? resolveEquipment(classEquip, equipmentSelections, categoryChoices)
        : [],
    [classEquip, equipmentSelections, categoryChoices]
  );

  const cantripChoices = spellsQuery.data?.cantrips ?? [];
  const levelOneChoices = spellsQuery.data?.level1 ?? [];

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
    return calculateHitPoints(selectedClass.hitDie, finalAbilities.constitution);
  }, [selectedClass, finalAbilities.constitution]);

  const derivedAC = useMemo(
    () => calculateArmorClass(finalAbilities.dexterity),
    [finalAbilities.dexterity]
  );

  const derivedSpeed = selectedRace?.speed ?? 30;

  const steps = useMemo<Step[]>(() => {
    const base: Step[] = ["Race", "Class", "Abilities", "Equipment"];
    if (caster) base.push("Spells");
    base.push("Details", "Review");
    return base;
  }, [caster]);

  const stepIndex = steps.indexOf(step);

  // If the step list shrinks (e.g. switching to a non-caster removes "Spells"),
  // keep the current step valid.
  useEffect(() => {
    if (!steps.includes(step)) setStep("Class");
  }, [steps, step]);

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
      case "Equipment":
        return (
          !!classEquip &&
          equipmentSelectionComplete(classEquip, equipmentSelections, categoryChoices)
        );
      case "Spells":
        return (
          !!spellCaps &&
          selectedCantrips.length === spellCaps.cantripsKnown &&
          selectedSpells.length === spellCaps.spellsKnown
        );
      case "Details":
        return !!name.trim();
      case "Review":
        return true;
      default:
        return true;
    }
  }

  function nextStep() {
    if (stepIndex < steps.length - 1) {
      setStep(steps[stepIndex + 1]);
    }
  }
  function prevStep() {
    if (stepIndex > 0) {
      setStep(steps[stepIndex - 1]);
    }
  }

  function toggleCantrip(name: string) {
    setSelectedCantrips((prev) => {
      if (prev.includes(name)) return prev.filter((n) => n !== name);
      if (!spellCaps || prev.length >= spellCaps.cantripsKnown) return prev;
      return [...prev, name];
    });
  }
  function toggleSpell(name: string) {
    setSelectedSpells((prev) => {
      if (prev.includes(name)) return prev.filter((n) => n !== name);
      if (!spellCaps || prev.length >= spellCaps.spellsKnown) return prev;
      return [...prev, name];
    });
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
        equipment: equipmentToStrings(resolvedItems),
        proficiencies: selectedClass.proficiencies,
        features: selectedRace.traits,
        cantrips: selectedCantrips,
        knownSpells: selectedSpells,
        startingInventory: resolvedItems,
        backstory,
        imageUrl: imageUrl.trim(),
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
          {steps.map((s, i) => (
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
              {i < steps.length - 1 && (
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

              <DataGate
                query={racesQuery}
                loadingLabel="Loading races…"
                onRetry={() => racesQuery.refetch()}
              >
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {racesQuery.data?.map((race) => (
                    <button
                      key={race.index}
                      onClick={() => setSelectedRace(race)}
                      className={`rounded-lg border p-4 text-left transition ${
                        selectedRace?.index === race.index
                          ? "border-accent bg-accent-glow"
                          : "border-border bg-bg-elevated hover:border-accent/50"
                      }`}
                    >
                      <div className="mb-1 flex items-center justify-between">
                        <span className="font-semibold text-text">
                          {race.name}
                        </span>
                        <span className="text-[10px] uppercase tracking-wider text-text-muted">
                          {race.size}
                        </span>
                      </div>
                      <div className="text-xs text-accent">
                        {Object.entries(race.abilityBonuses)
                          .map(
                            ([a, b]) => `${a.slice(0, 3).toUpperCase()} +${b}`
                          )
                          .join(", ") || "No ability bonuses"}
                      </div>
                      <div className="mt-1 text-xs text-text-muted">
                        Speed: {race.speed} ft
                      </div>
                      {selectedRace?.index === race.index && (
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
              </DataGate>
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
                proficiencies, saving throws, and equipment.
              </div>

              <DataGate
                query={classesQuery}
                loadingLabel="Loading classes…"
                onRetry={() => classesQuery.refetch()}
              >
                <div className="grid gap-3 sm:grid-cols-2">
                  {classesQuery.data?.map((cls) => (
                    <button
                      key={cls.index}
                      onClick={() => setSelectedClass(cls)}
                      className={`rounded-lg border p-4 text-left transition ${
                        selectedClass?.index === cls.index
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
                      <div className="text-xs text-text-muted">
                        Saves: {cls.savingThrows.join(", ")}
                      </div>
                      {isCaster(cls.name) && (
                        <span className="mt-1 inline-block rounded bg-accent-dark/30 px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-accent-light">
                          Spellcaster
                        </span>
                      )}
                      {selectedClass?.index === cls.index && (
                        <div className="mt-2 border-t border-border pt-2">
                          <div className="mb-1 text-xs font-semibold text-text-muted">
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
              </DataGate>
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

          {/* ── STEP: Equipment ───────────────────────────────── */}
          {step === "Equipment" && (
            <div>
              <h2 className="mb-2 text-lg font-bold text-accent">
                Starting Equipment
              </h2>
              <div className="mb-4 rounded-lg bg-bg-elevated p-3 text-xs text-text-muted">
                <strong className="text-text">D&D 5E Guide:</strong> Your class
                grants a set of starting gear. For each choice below, pick one
                option; some let you choose a specific weapon. Selected items
                become your character&apos;s inventory.
              </div>

              <DataGate
                query={equipmentQuery}
                loadingLabel="Loading starting equipment…"
                onRetry={() => equipmentQuery.refetch()}
              >
                {classEquip && (
                  <>
                    {classEquip.fixed.length > 0 && (
                      <div className="mb-5">
                        <div className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-text-muted">
                          Always included
                        </div>
                        <div className="flex flex-wrap gap-1">
                          {classEquip.fixed.map((it) => (
                            <span
                              key={`${it.name}-${it.kind}`}
                              className="rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                            >
                              {it.name}
                              {it.qty > 1 && (
                                <span className="tabular text-text"> ×{it.qty}</span>
                              )}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}

                    <div className="space-y-5">
                      {classEquip.groups.map((group, gi) => {
                        const selOpt =
                          group.options[equipmentSelections[gi] ?? 0];
                        return (
                          <div key={group.prompt}>
                            <div className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-text-muted">
                              {group.prompt}
                            </div>
                            <div className="grid gap-3 sm:grid-cols-2">
                              {group.options.map((opt, oi) => {
                                const selected =
                                  (equipmentSelections[gi] ?? 0) === oi;
                                return (
                                  <button
                                    key={opt.label || oi}
                                    type="button"
                                    aria-pressed={selected}
                                    onClick={() =>
                                      setEquipmentSelections((prev) => {
                                        const next = [...prev];
                                        next[gi] = oi;
                                        return next;
                                      })
                                    }
                                    className={cn(
                                      "rounded-lg border p-3 text-left transition",
                                      selected
                                        ? "border-accent bg-accent-glow"
                                        : "border-border bg-bg-elevated hover:border-accent/50"
                                    )}
                                  >
                                    <div className="flex items-center gap-2">
                                      <span
                                        className={cn(
                                          "flex h-4 w-4 shrink-0 items-center justify-center rounded-full border text-[10px]",
                                          selected
                                            ? "border-accent bg-accent text-white"
                                            : "border-border text-transparent"
                                        )}
                                      >
                                        ✓
                                      </span>
                                      <span className="text-sm font-semibold text-text">
                                        {String.fromCharCode(97 + oi)}.{" "}
                                        {opt.label}
                                      </span>
                                    </div>
                                  </button>
                                );
                              })}
                            </div>

                            {/* Specific-item sub-picks for the selected option */}
                            {selOpt && selOpt.categoryPicks.length > 0 && (
                              <div className="mt-3 space-y-2 rounded-lg border border-border bg-bg-elevated p-3">
                                {selOpt.categoryPicks.flatMap((pick, pi) =>
                                  Array.from({ length: pick.count }).map(
                                    (_, slot) => {
                                      const key = catKey(gi, pi, slot);
                                      return (
                                        <CategoryPickSelect
                                          key={key}
                                          label={
                                            pick.count > 1
                                              ? `${pick.categoryName} #${slot + 1}`
                                              : `Choose a ${pick.categoryName}`
                                          }
                                          categoryIndex={pick.categoryIndex}
                                          value={categoryChoices[key] ?? ""}
                                          onChange={(itemName) =>
                                            setCategoryChoices((prev) => ({
                                              ...prev,
                                              [key]: itemName,
                                            }))
                                          }
                                        />
                                      );
                                    }
                                  )
                                )}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </>
                )}
              </DataGate>
            </div>
          )}

          {/* ── STEP: Spells ──────────────────────────────────── */}
          {step === "Spells" && spellCaps && (
            <div>
              <h2 className="mb-2 text-lg font-bold text-accent">
                Choose Your Spells
              </h2>
              <div className="mb-4 rounded-lg bg-bg-elevated p-3 text-xs text-text-muted">
                <strong className="text-text">D&D 5E Guide:</strong> As a{" "}
                {selectedClass?.name}, you begin knowing{" "}
                {spellCaps.cantripsKnown} cantrips and {spellCaps.spellsKnown}{" "}
                level-1 spells. Cantrips are cast at will; leveled spells use
                spell slots.
              </div>

              <DataGate
                query={spellsQuery}
                loadingLabel="Loading spell list…"
                onRetry={() => spellsQuery.refetch()}
              >
                <SpellPicker
                  title="Cantrips"
                  picked={selectedCantrips.length}
                  max={spellCaps.cantripsKnown}
                  choices={cantripChoices}
                  selectedNames={selectedCantrips}
                  onToggle={toggleCantrip}
                />

                <div className="mt-5">
                  <SpellPicker
                    title="Level 1 Spells"
                    picked={selectedSpells.length}
                    max={spellCaps.spellsKnown}
                    choices={levelOneChoices}
                    selectedNames={selectedSpells}
                    onToggle={toggleSpell}
                  />
                </div>
              </DataGate>
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
                <div className="flex items-start gap-4">
                  <Portrait src={imageUrl} name={name} size="xl" />
                  <div className="flex-1 space-y-4">
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
                        Portrait URL
                      </label>
                      <input
                        type="url"
                        value={imageUrl}
                        onChange={(e) => setImageUrl(e.target.value)}
                        placeholder="https://example.com/portrait.png"
                        className="w-full rounded-lg border border-border bg-bg-elevated px-4 py-2.5 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent"
                      />
                      <p className="mt-1 text-xs text-text-muted">
                        Optional. Paste a link to an image; leave empty for initials.
                      </p>
                    </div>
                  </div>
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
                    {(alignmentsQuery.data ?? []).map((a) => (
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
                {resolvedItems.length > 0 && (
                  <div className="rounded-lg border border-border bg-bg-elevated p-4">
                    <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                      Equipment
                    </h3>
                    <div className="flex flex-wrap gap-1">
                      {resolvedItems.map((i) => (
                        <span
                          key={`${i.name}-${i.kind}`}
                          className="rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                        >
                          {i.qty > 1 ? `${i.name} ×${i.qty}` : i.name}
                        </span>
                      ))}
                    </div>
                  </div>
                )}

                {/* Spells */}
                {caster &&
                  (selectedCantrips.length > 0 || selectedSpells.length > 0) && (
                    <div className="rounded-lg border border-border bg-bg-elevated p-4">
                      <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                        Spells
                      </h3>
                      {selectedCantrips.length > 0 && (
                        <div className="mb-2">
                          <div className="mb-1 text-[10px] uppercase tracking-wider text-gold">
                            Cantrips
                          </div>
                          <div className="flex flex-wrap gap-1">
                            {selectedCantrips.map((s) => (
                              <span
                                key={s}
                                className="rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                              >
                                {s}
                              </span>
                            ))}
                          </div>
                        </div>
                      )}
                      {selectedSpells.length > 0 && (
                        <div>
                          <div className="mb-1 text-[10px] uppercase tracking-wider text-gold">
                            Level 1
                          </div>
                          <div className="flex flex-wrap gap-1">
                            {selectedSpells.map((s) => (
                              <span
                                key={s}
                                className="rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                              >
                                {s}
                              </span>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  )}

                {/* Traits & Features */}
                {selectedRace && selectedRace.traits.length > 0 && (
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

/* ── Data loading / error gate ───────────────────────────────── */
function DataGate({
  query,
  loadingLabel,
  onRetry,
  children,
}: {
  query: { isLoading: boolean; isError: boolean };
  loadingLabel: string;
  onRetry: () => void;
  children: React.ReactNode;
}) {
  if (query.isLoading) {
    return (
      <div className="flex items-center justify-center gap-3 py-12 text-text-muted">
        <Spinner className="text-accent" /> {loadingLabel}
      </div>
    );
  }
  if (query.isError) {
    return (
      <div className="py-8">
        <Alert>
          Couldn&apos;t load D&D data from dnd5eapi.co.{" "}
          <button
            onClick={onRetry}
            className="font-semibold text-accent underline hover:text-accent-light"
          >
            Retry
          </button>
        </Alert>
      </div>
    );
  }
  return <>{children}</>;
}

/* ── Specific-item picker for "choose any X" equipment ───────── */
function CategoryPickSelect({
  label,
  categoryIndex,
  value,
  onChange,
}: {
  label: string;
  categoryIndex: string;
  value: string;
  onChange: (itemName: string) => void;
}) {
  const cat = useEquipmentCategory(categoryIndex);
  return (
    <div>
      <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-text-muted">
        {label}
      </label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={cat.isLoading}
        className="w-full rounded border border-border bg-surface px-2 py-1.5 text-sm text-text"
      >
        <option value="">
          {cat.isLoading ? "Loading…" : "-- choose an item --"}
        </option>
        {cat.data?.map((it) => (
          <option key={it.index} value={it.name}>
            {it.name}
          </option>
        ))}
      </select>
      {cat.isError && (
        <span className="mt-1 block text-xs text-danger">
          Failed to load options.
        </span>
      )}
    </div>
  );
}

/* ── Spell picker (cantrips / leveled) ───────────────────────── */
function SpellPicker({
  title,
  picked,
  max,
  choices,
  selectedNames,
  onToggle,
}: {
  title: string;
  picked: number;
  max: number;
  choices: Spell[];
  selectedNames: string[];
  onToggle: (name: string) => void;
}) {
  const atCap = picked >= max;
  return (
    <div>
      <div className="mb-2 flex items-baseline justify-between">
        <span className="text-sm font-semibold text-text">{title}</span>
        <span
          className={cn(
            "tabular text-xs font-semibold",
            atCap ? "text-success" : "text-text-muted"
          )}
        >
          {picked}/{max} chosen
        </span>
      </div>
      {choices.length === 0 ? (
        <p className="text-xs text-text-muted">No options available.</p>
      ) : (
        <div className="grid gap-2 sm:grid-cols-2">
          {choices.map((spell) => {
            const selected = selectedNames.includes(spell.name);
            const disabled = !selected && atCap;
            return (
              <button
                key={spell.name}
                type="button"
                aria-pressed={selected}
                disabled={disabled}
                onClick={() => onToggle(spell.name)}
                className={cn(
                  "rounded-lg border p-3 text-left transition",
                  selected
                    ? "border-accent bg-accent-glow"
                    : disabled
                      ? "cursor-not-allowed border-border bg-bg-elevated opacity-40"
                      : "border-border bg-bg-elevated hover:border-accent/50"
                )}
              >
                <div className="mb-0.5 flex items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-text">
                    {spell.name}
                  </span>
                  {spell.school && (
                    <span className="shrink-0 text-[10px] uppercase tracking-wider text-gold">
                      {spell.school}
                    </span>
                  )}
                </div>
                {spell.desc && (
                  <p className="text-xs text-text-muted">{spell.desc}</p>
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
