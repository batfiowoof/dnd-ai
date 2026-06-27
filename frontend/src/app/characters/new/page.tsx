"use client";

import { useState, useMemo, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { useCreateCharacter } from "@/hooks/useCharacterQueries";
import {
  useSpecies,
  useClasses,
  useBackgrounds,
  useAlignments,
  useClassSpells,
  useFeat,
  useEquipmentKindMap,
} from "@/hooks/useDnd5eData";
import {
  ABILITY_NAMES,
  POINT_BUY_COSTS,
  POINT_BUY_TOTAL,
  STANDARD_ARRAY,
  classSkillOptions,
  EMPTY_ASI,
  backgroundTargets,
  backgroundBonuses,
  asiValid,
  parseClassEquipmentOptions,
  parseEquipmentItems,
  equipmentToStrings,
  getAbilityModifier,
  formatModifier,
  calculateHitPoints,
  calculateArmorClass,
  type AbilityName,
  type AsiAssignment,
  type AsiMode,
  type SpeciesInfo,
  type ClassInfo,
  type BackgroundInfo,
} from "@/lib/dnd5e";
import { featIndexFromName } from "@/lib/dnd5eapi";
import { SPELLCASTING, isCaster, type Spell } from "@/lib/spells";
import { Button, Alert, Spinner, Field, controlClass, cn } from "@/components/ui";
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
  strength: "Physical power. Melee attacks, carrying capacity, Athletics.",
  dexterity: "Agility and reflexes. AC, ranged attacks, initiative, Stealth.",
  constitution: "Endurance and health. Hit points and CON saves.",
  intelligence: "Learning and reasoning. Arcana, History, Investigation.",
  wisdom: "Perception and insight. Perception, Insight, Medicine, Survival.",
  charisma: "Force of personality. Deception, Persuasion, Performance.",
};

/* ── Step navigation (2024-canonical order) ──────────────────────── */
// Spells only appears for caster classes (derived from the chosen class below).
type Step =
  | "Class"
  | "Background"
  | "Species"
  | "Abilities"
  | "Spells"
  | "Equipment"
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

  // ── SRD reference data (app backend /api/srd, cached) ────────────
  const classesQuery = useClasses();
  const backgroundsQuery = useBackgrounds();
  const speciesQuery = useSpecies();
  const alignmentsQuery = useAlignments();
  const equipKindMapQuery = useEquipmentKindMap();

  const [step, setStep] = useState<Step>("Class");
  const [error, setError] = useState("");

  // Character state
  const [name, setName] = useState("");
  const [selectedClass, setSelectedClass] = useState<ClassInfo | null>(null);
  const [selectedBackground, setSelectedBackground] =
    useState<BackgroundInfo | null>(null);
  const [selectedSpecies, setSelectedSpecies] = useState<SpeciesInfo | null>(null);
  const [alignment, setAlignment] = useState("");
  const [backstory, setBackstory] = useState("");
  const [imageUrl, setImageUrl] = useState("");

  // Class skill proficiency choices (cap = class.skillProficiencies.choose)
  const [classSkills, setClassSkills] = useState<string[]>([]);

  // Abilities — base method + the background ASI split
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
  const [asi, setAsi] = useState<AsiAssignment>(EMPTY_ASI);

  // Equipment A/B(/C) selections (letters) + spells
  const [classEquipLetter, setClassEquipLetter] = useState("A");
  const [bgEquipLetter, setBgEquipLetter] = useState<"A" | "B">("A");
  const [selectedCantrips, setSelectedCantrips] = useState<string[]>([]);
  const [selectedSpells, setSelectedSpells] = useState<string[]>([]);

  const caster = selectedClass ? isCaster(selectedClass.name) : false;
  const spellCaps = selectedClass ? SPELLCASTING[selectedClass.name] : undefined;

  const spellsQuery = useClassSpells(selectedClass?.index, caster);
  const featIndex = selectedBackground
    ? featIndexFromName(selectedBackground.feat)
    : undefined;
  const featQuery = useFeat(featIndex, !!featIndex);

  // Reset class-dependent choices when the class changes.
  useEffect(() => {
    setClassSkills([]);
    setClassEquipLetter("A");
    setSelectedCantrips([]);
    setSelectedSpells([]);
  }, [selectedClass?.index]);

  // Reset the ASI split + background equipment when the background changes
  // (its target abilities change, so a prior assignment may be invalid).
  useEffect(() => {
    setAsi(EMPTY_ASI);
    setBgEquipLetter("A");
  }, [selectedBackground?.index]);

  const bgTargets = useMemo(
    () => backgroundTargets(selectedBackground),
    [selectedBackground]
  );

  const bgBonuses = useMemo(
    () => backgroundBonuses(selectedBackground, asi),
    [selectedBackground, asi]
  );

  // Final abilities = chosen base + background ASI bonus.
  const finalAbilities = useMemo(() => {
    const base =
      abilityMethod === "standard"
        ? (Object.fromEntries(
            ABILITY_NAMES.map((a) => [a, standardAssignments[a] ?? 8])
          ) as Record<AbilityName, number>)
        : { ...baseAbilities };
    for (const a of ABILITY_NAMES) base[a] += bgBonuses[a];
    return base;
  }, [baseAbilities, standardAssignments, abilityMethod, bgBonuses]);

  const baseScore = useMemo(
    () =>
      (Object.fromEntries(
        ABILITY_NAMES.map((a) => [
          a,
          abilityMethod === "standard"
            ? standardAssignments[a] ?? 8
            : baseAbilities[a],
        ])
      ) as Record<AbilityName, number>),
    [abilityMethod, standardAssignments, baseAbilities]
  );

  const pointBuySpent = useMemo(() => {
    if (abilityMethod !== "pointbuy") return 0;
    return ABILITY_NAMES.reduce(
      (sum, a) => sum + (POINT_BUY_COSTS[baseAbilities[a]] ?? 0),
      0
    );
  }, [baseAbilities, abilityMethod]);

  const usedStandardValues = useMemo(
    () =>
      Object.values(standardAssignments).filter((v) => v !== null) as number[],
    [standardAssignments]
  );

  const derivedHP = useMemo(() => {
    if (!selectedClass) return 10;
    return calculateHitPoints(selectedClass.hitDie, finalAbilities.constitution);
  }, [selectedClass, finalAbilities.constitution]);

  const derivedAC = useMemo(
    () => calculateArmorClass(finalAbilities.dexterity),
    [finalAbilities.dexterity]
  );

  const derivedSpeed = selectedSpecies?.speed ?? 30;

  // ── Equipment resolution (best-effort parse of SRD free text) ────
  const classEquipOptions = useMemo(
    () =>
      selectedClass
        ? parseClassEquipmentOptions(selectedClass.startingEquipment)
        : [],
    [selectedClass]
  );

  const resolvedItems = useMemo(() => {
    const kindMap = equipKindMapQuery.data;
    const classRaw =
      classEquipOptions.find((o) => o.letter === classEquipLetter)?.raw ??
      classEquipOptions[0]?.raw ??
      "";
    const bgRaw =
      bgEquipLetter === "A"
        ? selectedBackground?.equipment.optionA ?? ""
        : selectedBackground?.equipment.optionB ?? "";
    const combined = [classRaw, bgRaw].filter(Boolean).join(", ");
    return combined ? parseEquipmentItems(combined, kindMap) : [];
  }, [
    classEquipOptions,
    classEquipLetter,
    bgEquipLetter,
    selectedBackground,
    equipKindMapQuery.data,
  ]);

  const cantripChoices = spellsQuery.data?.cantrips ?? [];
  const levelOneChoices = spellsQuery.data?.level1 ?? [];

  const steps = useMemo<Step[]>(() => {
    const base: Step[] = ["Class", "Background", "Species", "Abilities"];
    if (caster) base.push("Spells");
    base.push("Equipment", "Details", "Review");
    return base;
  }, [caster]);

  const stepIndex = steps.indexOf(step);

  useEffect(() => {
    if (!steps.includes(step)) setStep("Class");
  }, [steps, step]);

  const baseAbilitiesValid =
    abilityMethod === "standard"
      ? ABILITY_NAMES.every((a) => standardAssignments[a] !== null)
      : pointBuySpent <= POINT_BUY_TOTAL;

  function canProceed(): boolean {
    switch (step) {
      case "Class":
        return (
          !!selectedClass &&
          classSkills.length === selectedClass.skillProficiencies.choose
        );
      case "Background":
        return !!selectedBackground;
      case "Species":
        return !!selectedSpecies;
      case "Abilities":
        return baseAbilitiesValid && asiValid(selectedBackground, asi);
      case "Spells":
        return (
          !!spellCaps &&
          selectedCantrips.length === spellCaps.cantripsKnown &&
          selectedSpells.length === spellCaps.spellsKnown
        );
      case "Equipment":
        return !!selectedClass && !!selectedBackground;
      case "Details":
        return !!name.trim();
      case "Review":
        return true;
      default:
        return true;
    }
  }

  function nextStep() {
    if (stepIndex < steps.length - 1) setStep(steps[stepIndex + 1]);
  }
  function prevStep() {
    if (stepIndex > 0) setStep(steps[stepIndex - 1]);
  }

  function toggleClassSkill(skill: string) {
    if (!selectedClass) return;
    setClassSkills((prev) => {
      if (prev.includes(skill)) return prev.filter((s) => s !== skill);
      if (prev.length >= selectedClass.skillProficiencies.choose) return prev;
      return [...prev, skill];
    });
  }
  function toggleCantrip(n: string) {
    setSelectedCantrips((prev) => {
      if (prev.includes(n)) return prev.filter((x) => x !== n);
      if (!spellCaps || prev.length >= spellCaps.cantripsKnown) return prev;
      return [...prev, n];
    });
  }
  function toggleSpell(n: string) {
    setSelectedSpells((prev) => {
      if (prev.includes(n)) return prev.filter((x) => x !== n);
      if (!spellCaps || prev.length >= spellCaps.spellsKnown) return prev;
      return [...prev, n];
    });
  }

  async function handleSave() {
    if (!username || !selectedSpecies || !selectedClass || !selectedBackground)
      return;
    if (!name.trim()) return;
    setError("");
    try {
      const proficiencies = Array.from(
        new Set([
          ...selectedBackground.skillProficiencies,
          selectedBackground.toolProficiency,
          ...classSkills,
        ])
      ).filter(Boolean);
      const features = [
        ...selectedSpecies.traits.map((t) => t.name),
        selectedBackground.feat,
      ];

      await createMutation.mutateAsync({
        name: name.trim(),
        race: selectedSpecies.name,
        characterClass: selectedClass.name,
        level: 1,
        background: selectedBackground.name,
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
        proficiencies,
        features,
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
        <div className="mb-8 flex flex-wrap items-center justify-center gap-1">
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
                    "mx-1 h-px w-5 transition",
                    i < stepIndex ? "bg-accent" : "bg-border"
                  )}
                />
              )}
            </div>
          ))}
        </div>

        <div className="rounded-xl border border-border-accent bg-surface p-6 panel-corners">
          {/* ── STEP: Class ───────────────────────────────────── */}
          {step === "Class" && (
            <div>
              <StepHeading>Choose Your Class</StepHeading>
              <Guide>
                Your class is your primary adventuring role. It sets your hit
                points, weapon &amp; armor training, saving throws, and the skills
                you train. Pick a class, then choose your trained skills.
              </Guide>

              <DataGate query={classesQuery} loadingLabel="Loading classes…" onRetry={() => classesQuery.refetch()}>
                <div className="grid gap-3 sm:grid-cols-2">
                  {classesQuery.data?.map((cls) => {
                    const selected = selectedClass?.index === cls.index;
                    return (
                      <OptionCard
                        key={cls.index}
                        selected={selected}
                        onClick={() => setSelectedClass(cls)}
                        title={cls.name}
                        badge={`d${cls.hitDie} HD`}
                      >
                        <div className="text-xs text-text-muted">
                          Primary: <span className="text-text">{cls.primaryAbility}</span>
                        </div>
                        <div className="text-xs text-text-muted">
                          Saves: {cls.savingThrows.join(", ")}
                        </div>
                        {isCaster(cls.name) && (
                          <span className="mt-1 inline-block rounded bg-accent-dark/30 px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-accent-light">
                            Spellcaster
                          </span>
                        )}
                      </OptionCard>
                    );
                  })}
                </div>

                {selectedClass && (
                  <div className="mt-5 rounded-lg border border-border bg-bg-elevated p-4">
                    <ChoicePicker
                      title={`Trained Skills — choose ${selectedClass.skillProficiencies.choose}`}
                      picked={classSkills.length}
                      max={selectedClass.skillProficiencies.choose}
                      options={classSkillOptions(selectedClass)}
                      selected={classSkills}
                      onToggle={toggleClassSkill}
                    />
                  </div>
                )}
              </DataGate>
            </div>
          )}

          {/* ── STEP: Background ──────────────────────────────── */}
          {step === "Background" && (
            <div>
              <StepHeading>Choose Your Background</StepHeading>
              <Guide>
                In the 2024 rules your background is mechanical: it grants an
                ability-score increase (assigned in the Abilities step), an{" "}
                <strong className="text-text">origin feat</strong>, skill and tool
                proficiencies, and starting equipment.
              </Guide>

              <DataGate query={backgroundsQuery} loadingLabel="Loading backgrounds…" onRetry={() => backgroundsQuery.refetch()}>
                <div className="grid gap-3 sm:grid-cols-2">
                  {backgroundsQuery.data?.map((bg) => {
                    const selected = selectedBackground?.index === bg.index;
                    return (
                      <OptionCard
                        key={bg.index}
                        selected={selected}
                        onClick={() => setSelectedBackground(bg)}
                        title={bg.name}
                      >
                        <div className="text-xs text-accent">
                          {bg.abilityScores
                            .map((a) => a.slice(0, 3).toUpperCase())
                            .join(" / ")}{" "}
                          increase
                        </div>
                        <div className="mt-1 flex items-center gap-1.5 text-xs text-text-muted">
                          <FeatIcon className="h-3.5 w-3.5 text-gold" />
                          {bg.feat}
                        </div>
                      </OptionCard>
                    );
                  })}
                </div>

                {selectedBackground && (
                  <div className="mt-5 space-y-4">
                    {/* Origin feat callout */}
                    <FeatCallout
                      featName={selectedBackground.feat}
                      desc={featQuery.data?.desc}
                      loading={featQuery.isLoading}
                    />

                    <div className="grid gap-4 sm:grid-cols-2">
                      <DetailPanel title="Proficiencies">
                        <div className="text-xs text-text-muted">Skills</div>
                        <ChipRow items={selectedBackground.skillProficiencies} />
                        <div className="mt-2 text-xs text-text-muted">Tool</div>
                        <ChipRow items={[selectedBackground.toolProficiency]} />
                      </DetailPanel>

                      <DetailPanel title="Ability Increase">
                        <p className="text-xs text-text-muted">
                          Boosts one of these three abilities (you choose how in
                          the Abilities step):
                        </p>
                        <ChipRow items={selectedBackground.abilityScores} accent />
                      </DetailPanel>
                    </div>

                    {/* Equipment A/B choice */}
                    <DetailPanel title="Starting Equipment">
                      <div className="space-y-2">
                        <EquipRadio
                          name="bg-equip"
                          label="Option A"
                          value="A"
                          checked={bgEquipLetter === "A"}
                          onChange={() => setBgEquipLetter("A")}
                          text={selectedBackground.equipment.optionA}
                        />
                        <EquipRadio
                          name="bg-equip"
                          label="Option B"
                          value="B"
                          checked={bgEquipLetter === "B"}
                          onChange={() => setBgEquipLetter("B")}
                          text={selectedBackground.equipment.optionB}
                        />
                      </div>
                    </DetailPanel>
                  </div>
                )}
              </DataGate>
            </div>
          )}

          {/* ── STEP: Species ─────────────────────────────────── */}
          {step === "Species" && (
            <div>
              <StepHeading>Choose Your Species</StepHeading>
              <Guide>
                Your species defines your character&apos;s lineage and grants
                special traits, size, and speed. Under the 2024 rules species no
                longer changes your ability scores — those come from your
                background.
              </Guide>

              <DataGate query={speciesQuery} loadingLabel="Loading species…" onRetry={() => speciesQuery.refetch()}>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {speciesQuery.data?.map((sp) => {
                    const selected = selectedSpecies?.index === sp.index;
                    return (
                      <OptionCard
                        key={sp.index}
                        selected={selected}
                        onClick={() => setSelectedSpecies(sp)}
                        title={sp.name}
                        badge={`${sp.speed} ft`}
                      >
                        <div className="text-xs text-text-muted">
                          {[sp.creatureType, sp.size.split("(")[0].trim()]
                            .filter(Boolean)
                            .join(" · ")}
                        </div>
                        <div className="mt-0.5 text-xs text-text-muted">
                          {sp.traits.length} trait
                          {sp.traits.length === 1 ? "" : "s"}
                        </div>
                      </OptionCard>
                    );
                  })}
                </div>

                {selectedSpecies && (
                  <div className="mt-5 rounded-lg border border-border bg-bg-elevated p-4">
                    <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
                      {selectedSpecies.name} Traits
                    </h3>
                    <div className="space-y-2">
                      {selectedSpecies.traits.map((t) => (
                        <details
                          key={t.name}
                          className="rounded-md border border-border bg-surface"
                        >
                          <summary className="cursor-pointer px-3 py-2 text-sm font-semibold text-text">
                            {t.name}
                          </summary>
                          <p className="border-t border-border px-3 py-2 text-xs leading-relaxed text-text-muted">
                            {t.desc}
                          </p>
                        </details>
                      ))}
                    </div>
                  </div>
                )}
              </DataGate>
            </div>
          )}

          {/* ── STEP: Abilities ───────────────────────────────── */}
          {step === "Abilities" && (
            <div>
              <StepHeading>Ability Scores</StepHeading>
              <Guide>
                Set a <strong className="text-text">base</strong> for each ability
                with <strong>Standard Array</strong> (15, 14, 13, 12, 10, 8) or{" "}
                <strong>Point Buy</strong> (27 points). Then assign your{" "}
                <strong className="text-text">background increase</strong> below —
                the final score is base + bonus.
              </Guide>

              {/* Base method toggle */}
              <Segmented
                className="mb-4"
                ariaLabel="Ability score method"
                options={[
                  { value: "standard", label: "Standard Array" },
                  { value: "pointbuy", label: "Point Buy" },
                ]}
                value={abilityMethod}
                onChange={(v) => setAbilityMethod(v as AbilityMethod)}
              />

              {abilityMethod === "pointbuy" && (
                <div className="mb-4 text-sm">
                  Points spent:{" "}
                  <span
                    className={cn(
                      "font-bold tabular",
                      pointBuySpent > POINT_BUY_TOTAL ? "text-danger" : "text-accent"
                    )}
                  >
                    {pointBuySpent}
                  </span>{" "}
                  / {POINT_BUY_TOTAL}
                </div>
              )}

              {/* Background ASI sub-panel */}
              <AsiPanel
                background={selectedBackground}
                targets={bgTargets}
                asi={asi}
                onChange={setAsi}
              />

              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {ABILITY_NAMES.map((ability) => {
                  const bonus = bgBonuses[ability];
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
                        <span className="text-lg font-bold text-text tabular">
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
                          aria-label={`Assign value to ${ABILITY_LABELS[ability]}`}
                          value={standardAssignments[ability] ?? ""}
                          onChange={(e) => {
                            const val = e.target.value ? Number(e.target.value) : null;
                            setStandardAssignments((prev) => ({
                              ...prev,
                              [ability]: val,
                            }));
                          }}
                          className={controlClass}
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
                            aria-label={`Decrease ${ABILITY_LABELS[ability]}`}
                            onClick={() =>
                              setBaseAbilities((prev) => ({
                                ...prev,
                                [ability]: Math.max(8, prev[ability] - 1),
                              }))
                            }
                            disabled={baseAbilities[ability] <= 8}
                            className="rounded bg-surface-light px-2.5 py-1 text-sm font-bold text-text-muted hover:text-accent disabled:opacity-30"
                          >
                            −
                          </button>
                          <span className="w-8 text-center text-sm font-bold tabular">
                            {baseAbilities[ability]}
                          </span>
                          <button
                            aria-label={`Increase ${ABILITY_LABELS[ability]}`}
                            onClick={() =>
                              setBaseAbilities((prev) => ({
                                ...prev,
                                [ability]: Math.min(15, prev[ability] + 1),
                              }))
                            }
                            disabled={baseAbilities[ability] >= 15}
                            className="rounded bg-surface-light px-2.5 py-1 text-sm font-bold text-text-muted hover:text-accent disabled:opacity-30"
                          >
                            +
                          </button>
                          <span className="ml-auto text-xs text-text-muted">
                            Cost: {POINT_BUY_COSTS[baseAbilities[ability]] ?? 0}
                          </span>
                        </div>
                      )}

                      <div className="mt-1.5 text-xs text-text-muted tabular">
                        base {baseScore[ability]}
                        {bonus > 0 && (
                          <span className="font-semibold text-gold">
                            {" "}
                            +{bonus} background
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* ── STEP: Spells ──────────────────────────────────── */}
          {step === "Spells" && spellCaps && (
            <div>
              <StepHeading>Choose Your Spells</StepHeading>
              <Guide>
                As a {selectedClass?.name}, you begin knowing{" "}
                {spellCaps.cantripsKnown} cantrips and {spellCaps.spellsKnown}{" "}
                level-1 spells. Cantrips are cast at will; leveled spells use spell
                slots.
              </Guide>

              <DataGate query={spellsQuery} loadingLabel="Loading spell list…" onRetry={() => spellsQuery.refetch()}>
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

          {/* ── STEP: Equipment ───────────────────────────────── */}
          {step === "Equipment" && (
            <div>
              <StepHeading>Starting Equipment</StepHeading>
              <Guide>
                Your class and background each grant a starting equipment bundle.
                Pick one option from each; the selected items become your starting
                inventory.
              </Guide>

              {selectedClass && (
                <DetailPanel title={`${selectedClass.name} — choose one`}>
                  <div className="space-y-2">
                    {classEquipOptions.map((opt) => (
                      <EquipRadio
                        key={opt.letter}
                        name="class-equip"
                        label={`Option ${opt.letter}`}
                        value={opt.letter}
                        checked={classEquipLetter === opt.letter}
                        onChange={() => setClassEquipLetter(opt.letter)}
                        text={opt.raw}
                      />
                    ))}
                  </div>
                </DetailPanel>
              )}

              {selectedBackground && (
                <div className="mt-4">
                  <DetailPanel title={`${selectedBackground.name} — choose one`}>
                    <div className="space-y-2">
                      <EquipRadio
                        name="bg-equip-2"
                        label="Option A"
                        value="A"
                        checked={bgEquipLetter === "A"}
                        onChange={() => setBgEquipLetter("A")}
                        text={selectedBackground.equipment.optionA}
                      />
                      <EquipRadio
                        name="bg-equip-2"
                        label="Option B"
                        value="B"
                        checked={bgEquipLetter === "B"}
                        onChange={() => setBgEquipLetter("B")}
                        text={selectedBackground.equipment.optionB}
                      />
                    </div>
                  </DetailPanel>
                </div>
              )}

              {resolvedItems.length > 0 && (
                <div className="mt-4">
                  <DetailPanel title="Resulting Inventory">
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
                  </DetailPanel>
                </div>
              )}
            </div>
          )}

          {/* ── STEP: Details ─────────────────────────────────── */}
          {step === "Details" && (
            <div>
              <StepHeading>Character Details</StepHeading>
              <Guide>
                Name your character, choose an alignment that reflects their moral
                compass, and write a short backstory to bring them to life.
              </Guide>

              <div className="space-y-4">
                <div className="flex items-start gap-4">
                  <Portrait src={imageUrl} name={name} size="xl" />
                  <div className="flex-1 space-y-4">
                    <Field label="Character Name" htmlFor="char-name" required>
                      <input
                        id="char-name"
                        type="text"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="e.g. Thorin Ironforge"
                        className={controlClass}
                      />
                    </Field>
                    <Field
                      label="Portrait URL"
                      htmlFor="char-portrait"
                      hint="Optional. Paste a link to an image; leave empty for initials."
                    >
                      <input
                        id="char-portrait"
                        type="url"
                        value={imageUrl}
                        onChange={(e) => setImageUrl(e.target.value)}
                        placeholder="https://example.com/portrait.png"
                        className={controlClass}
                      />
                    </Field>
                  </div>
                </div>

                <Field label="Alignment" htmlFor="char-alignment">
                  <select
                    id="char-alignment"
                    value={alignment}
                    onChange={(e) => setAlignment(e.target.value)}
                    className={controlClass}
                  >
                    <option value="">-- Select Alignment --</option>
                    {(alignmentsQuery.data ?? []).map((a) => (
                      <option key={a} value={a}>
                        {a}
                      </option>
                    ))}
                  </select>
                </Field>

                <Field label="Backstory" htmlFor="char-backstory">
                  <textarea
                    id="char-backstory"
                    value={backstory}
                    onChange={(e) => setBackstory(e.target.value)}
                    placeholder="Describe your character's history, motivations, and goals..."
                    rows={4}
                    className={controlClass}
                  />
                </Field>
              </div>
            </div>
          )}

          {/* ── STEP: Review ──────────────────────────────────── */}
          {step === "Review" && (
            <div>
              <StepHeading>Review Your Character</StepHeading>

              <div className="grid gap-4 sm:grid-cols-2">
                <DetailPanel title="Identity">
                  <ReviewRow label="Name" value={name || "Unnamed"} strong />
                  <ReviewRow label="Species" value={selectedSpecies?.name ?? "—"} />
                  <ReviewRow label="Class" value={selectedClass?.name ?? "—"} />
                  <ReviewRow
                    label="Background"
                    value={selectedBackground?.name ?? "—"}
                  />
                  <ReviewRow
                    label="Origin Feat"
                    value={selectedBackground?.feat ?? "—"}
                  />
                  <ReviewRow label="Alignment" value={alignment || "—"} />
                </DetailPanel>

                <DetailPanel title="Combat">
                  <div className="flex justify-around text-center">
                    <Stat value={derivedHP} label="HP" />
                    <Stat value={derivedAC} label="AC" />
                    <Stat value={derivedSpeed} label="Speed" />
                    <Stat value={`d${selectedClass?.hitDie ?? "?"}`} label="Hit Die" />
                  </div>
                </DetailPanel>

                <div className="sm:col-span-2">
                  <DetailPanel title="Ability Scores">
                    <div className="grid grid-cols-6 gap-2 text-center">
                      {ABILITY_NAMES.map((a) => {
                        const score = finalAbilities[a];
                        const mod = getAbilityModifier(score);
                        const bonus = bgBonuses[a];
                        return (
                          <div key={a} className="rounded border border-border p-2">
                            <div className="text-xs font-bold text-accent">
                              {ABILITY_LABELS[a]}
                            </div>
                            <div className="text-lg font-bold text-text tabular">
                              {score}
                            </div>
                            <div className="text-xs text-text-muted">
                              {formatModifier(mod)}
                            </div>
                            {bonus > 0 && (
                              <div className="text-[10px] font-semibold text-gold">
                                +{bonus}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </DetailPanel>
                </div>

                {resolvedItems.length > 0 && (
                  <DetailPanel title="Equipment">
                    <ChipRow items={resolvedItems.map((i) =>
                      i.qty > 1 ? `${i.name} ×${i.qty}` : i.name
                    )} />
                  </DetailPanel>
                )}

                {caster && (selectedCantrips.length > 0 || selectedSpells.length > 0) && (
                  <DetailPanel title="Spells">
                    {selectedCantrips.length > 0 && (
                      <div className="mb-2">
                        <div className="mb-1 text-[10px] uppercase tracking-wider text-gold">
                          Cantrips
                        </div>
                        <ChipRow items={selectedCantrips} />
                      </div>
                    )}
                    {selectedSpells.length > 0 && (
                      <div>
                        <div className="mb-1 text-[10px] uppercase tracking-wider text-gold">
                          Level 1
                        </div>
                        <ChipRow items={selectedSpells} />
                      </div>
                    )}
                  </DetailPanel>
                )}

                {selectedSpecies && selectedSpecies.traits.length > 0 && (
                  <DetailPanel title="Species Traits">
                    <ChipRow items={selectedSpecies.traits.map((t) => t.name)} />
                  </DetailPanel>
                )}

                {backstory && (
                  <div className="sm:col-span-2">
                    <DetailPanel title="Backstory">
                      <p className="whitespace-pre-wrap text-sm text-text-muted">
                        {backstory}
                      </p>
                    </DetailPanel>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* ── Navigation / Error ────────────────────────────── */}
          {error && <Alert className="mt-4">{error}</Alert>}

          <div className="mt-6 flex items-center justify-between">
            <Button onClick={prevStep} disabled={stepIndex === 0} variant="ghost">
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

/* ── Small presentational helpers ────────────────────────────────── */

function StepHeading({ children }: { children: React.ReactNode }) {
  return <h2 className="mb-2 text-lg font-bold text-accent">{children}</h2>;
}

function Guide({ children }: { children: React.ReactNode }) {
  return (
    <div className="mb-4 rounded-lg bg-bg-elevated p-3 text-xs text-text-muted">
      <strong className="text-text">D&amp;D 2024 Guide:</strong> {children}
    </div>
  );
}

function DetailPanel({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-border bg-bg-elevated p-4">
      <h3 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">
        {title}
      </h3>
      {children}
    </div>
  );
}

function ChipRow({ items, accent }: { items: string[]; accent?: boolean }) {
  return (
    <div className="flex flex-wrap gap-1">
      {items.filter(Boolean).map((it) => (
        <span
          key={it}
          className={cn(
            "rounded px-2 py-0.5 text-xs",
            accent
              ? "bg-accent-dark/30 text-accent-light"
              : "bg-surface-light text-text-muted"
          )}
        >
          {it}
        </span>
      ))}
    </div>
  );
}

function ReviewRow({
  label,
  value,
  strong,
}: {
  label: string;
  value: string;
  strong?: boolean;
}) {
  return (
    <div className="text-sm">
      <span className="text-text-muted">{label}:</span>{" "}
      <span className={strong ? "font-semibold text-text" : "text-text"}>
        {value}
      </span>
    </div>
  );
}

function Stat({ value, label }: { value: string | number; label: string }) {
  return (
    <div>
      <div className="text-2xl font-bold text-gold tabular">{value}</div>
      <div className="text-xs text-text-muted">{label}</div>
    </div>
  );
}

/** A selectable card used by the Class / Background / Species grids. */
function OptionCard({
  selected,
  onClick,
  title,
  badge,
  children,
}: {
  selected: boolean;
  onClick: () => void;
  title: string;
  badge?: string;
  children?: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        "min-h-[44px] rounded-lg border p-4 text-left transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent",
        selected
          ? "border-accent bg-accent-glow"
          : "border-border bg-bg-elevated hover:border-accent/50"
      )}
    >
      <div className="mb-1 flex items-center justify-between gap-2">
        <span className="font-semibold text-text">{title}</span>
        {badge && (
          <span className="shrink-0 text-xs text-accent tabular">{badge}</span>
        )}
      </div>
      {children}
    </button>
  );
}

/** A capped multi-select chip list (class skills). Mirrors SpellPicker's cap UX. */
function ChoicePicker({
  title,
  picked,
  max,
  options,
  selected,
  onToggle,
}: {
  title: string;
  picked: number;
  max: number;
  options: string[];
  selected: string[];
  onToggle: (v: string) => void;
}) {
  const atCap = picked >= max;
  return (
    <div>
      <div className="mb-2 flex items-baseline justify-between">
        <span className="text-sm font-semibold text-text">{title}</span>
        <span
          className={cn(
            "text-xs font-semibold tabular",
            atCap ? "text-success" : "text-text-muted"
          )}
        >
          {picked}/{max} chosen
        </span>
      </div>
      <div className="flex flex-wrap gap-2">
        {options.map((opt) => {
          const isSel = selected.includes(opt);
          const disabled = !isSel && atCap;
          return (
            <button
              key={opt}
              type="button"
              aria-pressed={isSel}
              disabled={disabled}
              onClick={() => onToggle(opt)}
              className={cn(
                "min-h-[44px] rounded-lg border px-3 py-2 text-sm transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent",
                isSel
                  ? "border-accent bg-accent-glow text-text"
                  : disabled
                    ? "cursor-not-allowed border-border bg-bg-elevated text-text-muted opacity-40"
                    : "border-border bg-bg-elevated text-text-muted hover:border-accent/50"
              )}
            >
              {opt}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Single-select toggle (base ability method, ASI split). It's a radiogroup, not
 * ARIA tabs — there are no tabpanels/roving-tabindex, so radio semantics are the
 * correct fit and keep each option keyboard-reachable.
 */
function Segmented({
  options,
  value,
  onChange,
  className,
  ariaLabel,
}: {
  options: { value: string; label: string }[];
  value: string;
  onChange: (v: string) => void;
  className?: string;
  ariaLabel?: string;
}) {
  return (
    <div
      role="radiogroup"
      aria-label={ariaLabel}
      className={cn(
        "inline-flex rounded-lg border border-border bg-bg-elevated p-1",
        className
      )}
    >
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            className={cn(
              "min-h-[40px] rounded-md px-4 py-1.5 text-sm font-semibold transition",
              active
                ? "bg-accent text-white"
                : "text-text-muted hover:text-text"
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

/** A radio row for an equipment option (text-heavy, full-width target). */
function EquipRadio({
  name,
  label,
  value,
  checked,
  onChange,
  text,
}: {
  name: string;
  label: string;
  value: string;
  checked: boolean;
  onChange: () => void;
  text: string;
}) {
  return (
    <label
      className={cn(
        "flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition",
        checked
          ? "border-accent bg-accent-glow"
          : "border-border bg-surface hover:border-accent/50"
      )}
    >
      <input
        type="radio"
        name={name}
        value={value}
        checked={checked}
        onChange={onChange}
        className="mt-0.5 accent-[var(--color-accent)]"
      />
      <span>
        <span className="block text-xs font-semibold uppercase tracking-wider text-text-muted">
          {label}
        </span>
        <span className="text-sm text-text">{text}</span>
      </span>
    </label>
  );
}

/** The origin-feat callout card shown when a background is selected. */
function FeatCallout({
  featName,
  desc,
  loading,
}: {
  featName: string;
  desc?: string;
  loading: boolean;
}) {
  return (
    <div className="rounded-lg border border-gold/40 bg-gold/5 p-4">
      <div className="mb-1 flex items-center gap-2">
        <FeatIcon className="h-4 w-4 text-gold" />
        <span className="text-[10px] font-semibold uppercase tracking-wider text-gold">
          Origin Feat
        </span>
      </div>
      <div className="mb-1 text-sm font-bold text-text">{featName}</div>
      {loading ? (
        <span className="flex items-center gap-2 text-xs text-text-muted">
          <Spinner className="h-3 w-3" /> Consulting the tomes…
        </span>
      ) : desc ? (
        <p className="text-xs leading-relaxed text-text-muted">{desc}</p>
      ) : (
        <p className="text-xs text-text-muted">
          Grants the {featName} origin feat.
        </p>
      )}
    </div>
  );
}

/** The background ability-score-increase sub-panel (the ASI split + validation). */
function AsiPanel({
  background,
  targets,
  asi,
  onChange,
}: {
  background: BackgroundInfo | null;
  targets: AbilityName[];
  asi: AsiAssignment;
  onChange: (next: AsiAssignment) => void;
}) {
  if (!background) {
    return (
      <div className="mb-4 rounded-lg border border-dashed border-border bg-bg-elevated p-4 text-xs text-text-muted">
        Choose a background first to assign its ability-score increase.
      </div>
    );
  }

  const valid = asiValid(background, asi);
  const setMode = (mode: AsiMode) =>
    onChange({ ...asi, mode, plusTwo: null, plusOne: null });

  return (
    <div className="mb-4 rounded-lg border border-border bg-bg-elevated p-4">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold uppercase tracking-wider text-text-muted">
          {background.name} Ability Increase
        </h3>
        <Segmented
          ariaLabel="Background ability increase split"
          options={[
            { value: "two-one", label: "+2 / +1" },
            { value: "all-one", label: "+1 / +1 / +1" },
          ]}
          value={asi.mode}
          onChange={(v) => setMode(v as AsiMode)}
        />
      </div>

      {asi.mode === "all-one" ? (
        <p className="text-xs text-text-muted">
          Each of{" "}
          <span className="font-semibold text-gold">
            {background.abilityScores.join(", ")}
          </span>{" "}
          gains +1.
        </p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="+2 to" htmlFor="asi-plus-two">
            <select
              id="asi-plus-two"
              value={asi.plusTwo ?? ""}
              onChange={(e) =>
                onChange({
                  ...asi,
                  plusTwo: (e.target.value || null) as AbilityName | null,
                })
              }
              className={controlClass}
            >
              <option value="">-- choose --</option>
              {targets.map((a) => (
                <option key={a} value={a} disabled={asi.plusOne === a}>
                  {ABILITY_LABELS[a]}
                </option>
              ))}
            </select>
          </Field>
          <Field label="+1 to" htmlFor="asi-plus-one">
            <select
              id="asi-plus-one"
              value={asi.plusOne ?? ""}
              onChange={(e) =>
                onChange({
                  ...asi,
                  plusOne: (e.target.value || null) as AbilityName | null,
                })
              }
              className={controlClass}
            >
              <option value="">-- choose --</option>
              {targets.map((a) => (
                <option key={a} value={a} disabled={asi.plusTwo === a}>
                  {ABILITY_LABELS[a]}
                </option>
              ))}
            </select>
          </Field>
        </div>
      )}

      <p role="status" className="mt-2 text-xs">
        {valid ? (
          <span className="text-success">Ability increase assigned.</span>
        ) : (
          <span className="text-text-muted">
            {asi.mode === "two-one"
              ? "Assign +2 and +1 to two different abilities to continue."
              : "Select a split to continue."}
          </span>
        )}
      </p>
    </div>
  );
}

/* ── Data loading / error gate ───────────────────────────────────── */
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
          Couldn&apos;t load reference data.{" "}
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

/* ── Spell picker (cantrips / leveled) ───────────────────────────── */
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
            "text-xs font-semibold tabular",
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
                  "rounded-lg border p-3 text-left transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent",
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

/* ── Inline icon (no icon-lib dependency in this codebase) ───────── */
function FeatIcon({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={className}
    >
      <path d="M12 3l1.9 4.6L18.5 9.5 14 11.4 12 16l-2-4.6L5.5 9.5 10.1 7.6z" />
      <path d="M19 14l.7 1.7L21.5 16.5 20 17.2 19 19l-1-1.8L16.5 16.5 18.3 15.7z" />
    </svg>
  );
}
