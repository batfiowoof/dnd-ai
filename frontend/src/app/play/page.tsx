"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { useMyCharacters } from "@/hooks/useCharacterQueries";
import {
  useCreateSession,
  useJoinByCode,
} from "@/hooks/useSessionQueries";
import SessionsPanel from "@/components/SessionsPanel";
import type { Difficulty, DmLength, DmStyle, TurnMode } from "@/types";
import {
  Button,
  Panel,
  Brand,
  Field,
  Alert,
  Spinner,
  controlClass,
  cn,
} from "@/components/ui";

export default function PlayPage() {
  return (
    <RequireAuth>
      <PlayContent />
    </RequireAuth>
  );
}

const PRESET_WORLDS = [
  {
    id: "shattered-isles",
    name: "The Shattered Isles",
    tagline: "Nautical adventure across cursed archipelagos",
    setting: `# The Shattered Isles

## Overview
A vast ocean dotted with hundreds of islands — remnants of an ancient continent shattered by a cataclysmic war between titans. The seas are treacherous, teeming with sea monsters, ghost ships, and unpredictable magical storms.

## Key Locations
- **Port Vellara** — The largest free city, built on three connected islands. A melting pot of traders, pirates, and mercenaries. Ruled by the Council of Captains.
- **The Drowned Spire** — A half-submerged wizard's tower visible only at low tide. Said to hold the Titan's Eye, an artifact of immense power.
- **Thornback Reef** — A living coral labyrinth inhabited by sahuagin and their shark-riding war parties.
- **The Ashen Caldera** — A volcanic island where a fire giant clan forges weapons from magma-infused obsidian.

## Factions
- **The Crimson Fleet** — Ruthless pirates led by Captain Maelstra, a half-elf warlock who made a pact with a kraken.
- **The Tide Wardens** — An order of druids and rangers who protect the natural balance of the seas.
- **The Salvage Guild** — Treasure hunters and archaeologists obsessed with recovering pre-Shattering artifacts.

## Tone & Themes
Exploration, naval combat, treasure hunting, ancient mysteries, and the tension between freedom and law on the open sea. Magic is tied to the tides — spells can surge or ebb with the ocean's rhythm.`,
  },
  {
    id: "ironvault",
    name: "Ironvault — The Deep Below",
    tagline: "Survival horror in the Underdark's forgotten halls",
    setting: `# Ironvault — The Deep Below

## Overview
Miles beneath the surface lies the Ironvault — a sprawling network of dwarven mega-cities, fungal forests, underground lakes, and tunnels that stretch beyond all maps. The dwarven empire that built it collapsed centuries ago, and now countless creatures and factions fight over its ruins.

## Key Locations
- **Kharn-Dural** — A ruined dwarven capital carved into a cavern so vast it has its own weather. Bioluminescent fungi light the crumbling halls. Treasure and traps abound.
- **The Mycelium Sea** — A miles-wide fungal forest where mushrooms tower like redwoods. Home to myconid colonies and stranger things that lurk in the spore clouds.
- **The Mindforge** — A mind flayer research facility where they experiment on captured surface dwellers. The psychic hum can be felt from a mile away.
- **Emberlake** — A subterranean lake heated by magma vents, surrounded by a trading outpost of drow, deep gnomes, and kobold merchants.

## Factions
- **The Reclaimer Clans** — Dwarven descendants determined to reclaim Kharn-Dural, no matter the cost in blood.
- **The Pale Court** — A drow noble house that rules through assassination and spider-goddess worship.
- **The Spore Collective** — Myconid elders who communicate through shared fungal dreams and seek only peace — but will defend their groves fiercely.

## Tone & Themes
Claustrophobia, resource scarcity, ancient dwarven engineering marvels, alien ecosystems, and the question of what "civilization" means in total darkness. Light is precious — torches attract predators, but darkness hides worse things.`,
  },
  {
    id: "feywild-aeloria",
    name: "The Feywild of Aeloria",
    tagline: "Whimsy and danger in a realm of wild magic",
    setting: `# The Feywild of Aeloria

## Overview
The party has crossed into the Feywild — a mirror of the material plane where emotions shape reality, time flows strangely, and nothing is ever quite what it seems. Colors are more vivid, sounds carry melodies, and every bargain has a hidden price.

## Key Locations
- **The Everbloom Court** — Palace of the Summer Queen, where eternal sunlight bathes gardens of sentient flowers. Beauty masks ruthless political intrigue.
- **Murkhollow** — A swamp where the boundary between the Feywild and the Shadowfell grows thin. Will-o'-wisps lead travelers astray, and hags broker dark deals.
- **The Wandering Market** — A bazaar that appears in a different location each dawn. Merchants sell bottled memories, stolen voices, and maps to places that don't exist yet.
- **The Thornwood Labyrinth** — A living maze of brambles that rearranges itself. At its heart sleeps an archfey who grants one wish to those who reach them — but the wish always twists.

## Factions
- **The Summer Court** — Led by Queen Titania's chosen, they value beauty, passion, and growth — but their generosity always comes with strings.
- **The Gloaming Fellowship** — Trickster fey (pixies, satyrs, redcaps) who delight in pranks ranging from harmless to lethal.
- **The Forgotten** — Mortals who wandered into the Feywild and lost their way home. Some have been here for centuries without aging. They cling to fading memories.

## Tone & Themes
Fairy-tale logic, moral ambiguity, deals and consequences, shifting landscapes, emotional storytelling. Time moves differently — a week in the Feywild might be a year on the material plane, or vice versa. Trust nothing at face value; even kindness has a cost.`,
  },
];

const TURN_MODES: { value: TurnMode; name: string; desc: string }[] = [
  {
    value: "COLLABORATIVE",
    name: "Collaborative",
    desc: "Everyone submits each round; the DM resolves all actions in one combined reply.",
  },
  {
    value: "INITIATIVE",
    name: "Initiative",
    desc: "Players act one at a time in rolled initiative order.",
  },
  {
    value: "FREEFORM",
    name: "Freeform",
    desc: "Anyone can act anytime; input locks only while the DM is replying.",
  },
];

/** Segmented single-choice control matching the world-source tab styling. */
function SegGroup<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: string }[];
}) {
  return (
    <div className="flex rounded-lg border border-border bg-bg-elevated p-1 text-xs">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={cn(
            "flex-1 cursor-pointer rounded-md px-2 py-2 font-medium transition",
            value === o.value
              ? "bg-accent text-white shadow-[0_0_16px_var(--color-accent-glow)]"
              : "text-text-muted hover:text-text"
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

/** Labelled on/off switch (full-width tap target). */
function ToggleRow({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className="flex w-full cursor-pointer items-center justify-between gap-3 rounded-lg border border-border bg-bg-elevated px-3 py-2.5 text-left transition hover:border-accent/50"
    >
      <span>
        <span className="block text-sm font-medium text-text">{label}</span>
        {hint && <span className="block text-xs text-text-muted">{hint}</span>}
      </span>
      <span
        aria-hidden
        className={cn(
          "relative h-6 w-11 flex-shrink-0 rounded-full border transition",
          checked ? "border-accent bg-accent/80" : "border-border bg-bg"
        )}
      >
        <span
          className={cn(
            "absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all",
            checked ? "left-[22px]" : "left-0.5"
          )}
        />
      </span>
    </button>
  );
}

function PlayContent() {
  const router = useRouter();
  const { username } = useAuth();

  const [joinCode, setJoinCode] = useState("");
  const [mode, setMode] = useState<"idle" | "create" | "join">("idle");
  const [error, setError] = useState("");

  // Characters (React Query)
  const charactersQuery = useMyCharacters(!!username);
  const characters = charactersQuery.data ?? [];
  const charsLoading = charactersQuery.isLoading;
  const [selectedCharId, setSelectedCharId] = useState<string>("");

  // Session mutations
  const createMutation = useCreateSession();
  const joinMutation = useJoinByCode();
  const loading = createMutation.isPending || joinMutation.isPending;

  // World setting
  const [worldSource, setWorldSource] = useState<
    "preset" | "custom-write" | "custom-upload"
  >("preset");
  const [selectedPreset, setSelectedPreset] = useState(PRESET_WORLDS[0].id);
  const [customWorldText, setCustomWorldText] = useState("");
  const [expandedPreset, setExpandedPreset] = useState<string | null>(null);

  // Host session settings
  const [turnMode, setTurnMode] = useState<TurnMode>("COLLABORATIVE");
  const [maxPlayers, setMaxPlayers] = useState(4);
  const [difficulty, setDifficulty] = useState<Difficulty>("NORMAL");
  const [dmStyle, setDmStyle] = useState<DmStyle>("HEROIC");
  const [dmLength, setDmLength] = useState<DmLength>("STANDARD");
  const [allowAiCombat, setAllowAiCombat] = useState(true);
  const [allowAiRolls, setAllowAiRolls] = useState(true);
  const [collabWindowSeconds, setCollabWindowSeconds] = useState(10);

  // Default-select the first character once the list loads.
  useEffect(() => {
    if (characters.length > 0 && !selectedCharId) {
      setSelectedCharId(characters[0].id);
    }
  }, [characters, selectedCharId]);

  const selectedChar = characters.find((c) => c.id === selectedCharId);

  function getWorldSetting(): string | undefined {
    if (worldSource === "preset") {
      return PRESET_WORLDS.find((w) => w.id === selectedPreset)?.setting;
    }
    if (worldSource === "custom-write" || worldSource === "custom-upload") {
      return customWorldText.trim() || undefined;
    }
    return undefined;
  }

  function handleFileUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!file.name.endsWith(".md") && !file.name.endsWith(".txt")) {
      setError("Please upload a .md or .txt file.");
      return;
    }
    const reader = new FileReader();
    reader.onload = (ev) => {
      const text = ev.target?.result;
      if (typeof text === "string") {
        setCustomWorldText(text);
        setError("");
      }
    };
    reader.readAsText(file);
  }

  async function handleCreate() {
    if (!username || !selectedCharId) {
      setError("Please select a character.");
      return;
    }
    const worldSetting = getWorldSetting();
    if (!worldSetting) {
      setError("Please select or provide a world setting.");
      return;
    }
    setError("");
    try {
      const res = await createMutation.mutateAsync({
        playerName: username,
        characterId: selectedCharId,
        worldSetting,
        turnMode,
        maxPlayers,
        difficulty,
        dmStyle,
        dmLength,
        allowAiCombat,
        allowAiRolls,
        collabWindowSeconds,
      });
      localStorage.setItem(`dnd-playerId-${res.sessionId}`, res.playerId);
      localStorage.setItem(`dnd-joinCode-${res.sessionId}`, res.joinCode);
      router.push(`/lobby/${res.sessionId}`);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to create session");
    }
  }

  async function handleJoin() {
    if (!username || !selectedCharId || !joinCode.trim()) {
      setError("Please select a character and enter a join code.");
      return;
    }
    setError("");
    try {
      const { gameState, player } = await joinMutation.mutateAsync({
        code: joinCode.toUpperCase(),
        body: {
          playerName: username,
          characterId: selectedCharId,
        },
      });
      localStorage.setItem(`dnd-playerId-${gameState.sessionId}`, player.id);
      localStorage.setItem(
        `dnd-joinCode-${gameState.sessionId}`,
        gameState.joinCode
      );
      router.push(`/lobby/${gameState.sessionId}`);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to join session");
    }
  }

  return (
    <main className="flex min-h-dvh flex-col items-center p-4 py-10">
      <div className="w-full max-w-lg">
        <button
          onClick={() => router.push("/")}
          className="mb-4 inline-flex cursor-pointer items-center gap-1.5 text-xs text-text-muted transition hover:text-accent"
        >
          <span aria-hidden>←</span> Menu
        </button>
      </div>

      {/* Your Adventures */}
      <SessionsPanel className="mb-5 w-full max-w-lg" />

      <Panel glow corners className="w-full max-w-lg p-8 animate-rise">
        <div className="mb-1 flex justify-center">
          <Brand size="lg" />
        </div>
        <p className="text-center text-xs uppercase tracking-[0.25em] text-gold">
          New Adventure
        </p>

        <hr className="ornament my-6" />

        {/* Character Selection */}
        <div className="mb-6">
          {charsLoading ? (
            <span className="inline-flex items-center gap-2 text-sm text-text-muted">
              <Spinner className="text-accent" /> Loading characters...
            </span>
          ) : characters.length === 0 ? (
            <div className="rounded-lg border border-border bg-bg-elevated p-5 text-center">
              <p className="mb-3 text-sm text-text-muted">No characters found</p>
              <Button onClick={() => router.push("/characters/new")} size="sm">
                Create Your First Character
              </Button>
            </div>
          ) : (
            <Field label="Select Character" htmlFor="char-select">
              <select
                id="char-select"
                value={selectedCharId}
                onChange={(e) => setSelectedCharId(e.target.value)}
                className={controlClass}
              >
                {characters.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} — Lv{c.level} {c.race} {c.characterClass}
                  </option>
                ))}
              </select>
              {selectedChar && (
                <div className="mt-2 flex items-center justify-between rounded-lg border border-border bg-bg-elevated px-3 py-2">
                  <div className="tabular text-xs text-text-muted">
                    <span className="text-gold">HP</span> {selectedChar.hitPoints}
                    {"  "}
                    <span className="text-gold">AC</span> {selectedChar.armorClass}
                    {"  "}
                    <span className="text-gold">SPD</span> {selectedChar.speed}
                  </div>
                  <button
                    onClick={() => router.push("/characters")}
                    className="cursor-pointer text-xs text-accent transition hover:text-accent-light hover:underline"
                  >
                    Manage
                  </button>
                </div>
              )}
            </Field>
          )}
        </div>

        {/* Mode selection */}
        {mode === "idle" && characters.length > 0 && (
          <div className="flex gap-3">
            <Button onClick={() => setMode("create")} fullWidth>
              Create Session
            </Button>
            <Button onClick={() => setMode("join")} variant="outline" fullWidth>
              Join Session
            </Button>
          </div>
        )}

        {/* Create */}
        {mode === "create" && (
          <div className="space-y-4">
            {/* World setting source tabs */}
            <div>
              <label className="mb-2 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                World Setting
              </label>
              <div className="flex rounded-lg border border-border bg-bg-elevated p-1 text-xs">
                {(
                  [
                    ["preset", "Presets"],
                    ["custom-write", "Write"],
                    ["custom-upload", "Upload"],
                  ] as const
                ).map(([value, label]) => (
                  <button
                    key={value}
                    onClick={() => setWorldSource(value)}
                    className={cn(
                      "flex-1 cursor-pointer rounded-md px-3 py-2 font-medium transition",
                      worldSource === value
                        ? "bg-accent text-white shadow-[0_0_16px_var(--color-accent-glow)]"
                        : "text-text-muted hover:text-text"
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>

            {/* Preset world cards */}
            {worldSource === "preset" && (
              <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
                {PRESET_WORLDS.map((world) => (
                  <div key={world.id}>
                    <button
                      onClick={() => setSelectedPreset(world.id)}
                      className={cn(
                        "w-full cursor-pointer rounded-lg border px-4 py-3 text-left transition",
                        selectedPreset === world.id
                          ? "border-accent bg-accent/10"
                          : "border-border bg-bg-elevated hover:border-accent/50 hover:-translate-y-0.5"
                      )}
                    >
                      <div className="flex items-center justify-between">
                        <div>
                          <p
                            className="text-sm font-semibold text-text"
                            style={{ fontFamily: "var(--font-display)" }}
                          >
                            {world.name}
                          </p>
                          <p className="text-xs text-text-muted">
                            {world.tagline}
                          </p>
                        </div>
                        <span
                          className={cn(
                            "h-3.5 w-3.5 flex-shrink-0 rounded-full border-2 transition",
                            selectedPreset === world.id
                              ? "border-gold bg-gold"
                              : "border-border"
                          )}
                        />
                      </div>
                    </button>
                    {selectedPreset === world.id && (
                      <button
                        onClick={() =>
                          setExpandedPreset(
                            expandedPreset === world.id ? null : world.id
                          )
                        }
                        className="mt-1 ml-1 cursor-pointer text-xs text-accent transition hover:text-accent-light hover:underline"
                      >
                        {expandedPreset === world.id
                          ? "Hide details"
                          : "Show details"}
                      </button>
                    )}
                    {expandedPreset === world.id && (
                      <div className="mt-1 max-h-40 overflow-y-auto rounded-lg border border-border bg-bg-elevated p-3 text-xs leading-relaxed text-text-muted whitespace-pre-wrap">
                        {world.setting}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}

            {/* Custom write */}
            {worldSource === "custom-write" && (
              <textarea
                value={customWorldText}
                onChange={(e) => setCustomWorldText(e.target.value)}
                placeholder={`Describe your world setting...\n\nInclude key locations, factions, tone, and any rules or themes you want the DM to follow. Markdown is supported.`}
                rows={8}
                className={cn(controlClass, "resize-none")}
              />
            )}

            {/* Custom upload */}
            {worldSource === "custom-upload" && (
              <div className="space-y-3">
                <label className="flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-bg-elevated px-4 py-6 transition hover:border-accent">
                  <svg
                    className="mb-2 h-8 w-8 text-text-muted"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={1.5}
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M12 16.5V9.75m0 0 3 3m-3-3-3 3M6.75 19.5a4.5 4.5 0 0 1-1.41-8.775 5.25 5.25 0 0 1 10.233-2.33 3 3 0 0 1 3.758 3.848A3.752 3.752 0 0 1 18 19.5H6.75Z"
                    />
                  </svg>
                  <span className="text-sm text-text-muted">
                    Upload a <span className="text-accent">.md</span> or{" "}
                    <span className="text-accent">.txt</span> file
                  </span>
                  <input
                    type="file"
                    accept=".md,.txt"
                    onChange={handleFileUpload}
                    className="hidden"
                  />
                </label>
                {customWorldText && (
                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-text-muted">
                        File loaded ({customWorldText.length} characters)
                      </span>
                      <button
                        onClick={() => setCustomWorldText("")}
                        className="cursor-pointer text-xs text-accent transition hover:text-accent-light hover:underline"
                      >
                        Clear
                      </button>
                    </div>
                    <div className="max-h-32 overflow-y-auto rounded-lg border border-border bg-bg-elevated p-3 text-xs text-text-muted whitespace-pre-wrap">
                      {customWorldText}
                    </div>
                  </div>
                )}
              </div>
            )}

            <hr className="ornament my-2" />

            {/* Turn handling */}
            <div className="space-y-2">
              <label className="block text-xs font-semibold uppercase tracking-wider text-text-muted">
                Turn Handling
              </label>
              {TURN_MODES.map((m) => (
                <button
                  key={m.value}
                  type="button"
                  onClick={() => setTurnMode(m.value)}
                  className={cn(
                    "w-full cursor-pointer rounded-lg border px-4 py-3 text-left transition",
                    turnMode === m.value
                      ? "border-accent bg-accent/10"
                      : "border-border bg-bg-elevated hover:border-accent/50"
                  )}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <p
                        className="text-sm font-semibold text-text"
                        style={{ fontFamily: "var(--font-display)" }}
                      >
                        {m.name}
                      </p>
                      <p className="text-xs text-text-muted">{m.desc}</p>
                    </div>
                    <span
                      aria-hidden
                      className={cn(
                        "h-3.5 w-3.5 flex-shrink-0 rounded-full border-2 transition",
                        turnMode === m.value ? "border-gold bg-gold" : "border-border"
                      )}
                    />
                  </div>
                </button>
              ))}
            </div>

            {turnMode === "COLLABORATIVE" && (
              <Field
                label="Round window"
                htmlFor="collab-window"
                hint="How long the round collects actions before the DM resolves it."
              >
                <div className="flex items-center gap-3">
                  <input
                    id="collab-window"
                    type="range"
                    min={3}
                    max={30}
                    step={1}
                    value={collabWindowSeconds}
                    onChange={(e) => setCollabWindowSeconds(Number(e.target.value))}
                    className="flex-1 accent-[var(--color-accent)]"
                  />
                  <span className="tabular w-12 text-right text-sm text-gold">
                    {collabWindowSeconds}s
                  </span>
                </div>
              </Field>
            )}

            {/* Party size + difficulty */}
            <div className="grid grid-cols-2 gap-3">
              <Field label="Party Size" htmlFor="party-size">
                <select
                  id="party-size"
                  value={maxPlayers}
                  onChange={(e) => setMaxPlayers(Number(e.target.value))}
                  className={controlClass}
                >
                  {[1, 2, 3, 4, 5, 6, 7, 8].map((n) => (
                    <option key={n} value={n}>
                      {n} player{n > 1 ? "s" : ""}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Difficulty">
                <SegGroup<Difficulty>
                  value={difficulty}
                  onChange={setDifficulty}
                  options={[
                    { value: "EASY", label: "Easy" },
                    { value: "NORMAL", label: "Normal" },
                    { value: "DEADLY", label: "Deadly" },
                  ]}
                />
              </Field>
            </div>

            {/* DM voice */}
            <Field label="DM Style">
              <SegGroup<DmStyle>
                value={dmStyle}
                onChange={setDmStyle}
                options={[
                  { value: "HEROIC", label: "Heroic" },
                  { value: "GRIMDARK", label: "Grimdark" },
                  { value: "COMEDIC", label: "Comedic" },
                ]}
              />
            </Field>
            <Field label="Narration Length">
              <SegGroup<DmLength>
                value={dmLength}
                onChange={setDmLength}
                options={[
                  { value: "CONCISE", label: "Concise" },
                  { value: "STANDARD", label: "Standard" },
                  { value: "RICH", label: "Rich" },
                ]}
              />
            </Field>

            {/* Feature toggles */}
            <div className="space-y-2">
              <ToggleRow
                label="DM can start combat"
                hint="Lets the DM trigger encounters from the story."
                checked={allowAiCombat}
                onChange={setAllowAiCombat}
              />
              <ToggleRow
                label="DM can request rolls"
                hint="Lets the DM call for ability checks on uncertain actions."
                checked={allowAiRolls}
                onChange={setAllowAiRolls}
              />
            </div>

            <Button
              onClick={handleCreate}
              disabled={!selectedCharId}
              loading={loading}
              fullWidth
            >
              {loading ? "Creating..." : "Create New Session"}
            </Button>
            <Button
              onClick={() => {
                setMode("idle");
                setError("");
              }}
              variant="ghost"
              fullWidth
            >
              Back
            </Button>
          </div>
        )}

        {/* Join */}
        {mode === "join" && (
          <div className="space-y-3">
            <input
              type="text"
              placeholder="JOIN CODE"
              maxLength={6}
              value={joinCode}
              onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
              className={cn(
                controlClass,
                "tabular text-center text-2xl tracking-[0.4em]"
              )}
            />
            <Button
              onClick={handleJoin}
              disabled={!selectedCharId}
              loading={loading}
              fullWidth
            >
              {loading ? "Joining..." : "Join Session"}
            </Button>
            <Button
              onClick={() => {
                setMode("idle");
                setError("");
              }}
              variant="ghost"
              fullWidth
            >
              Back
            </Button>
          </div>
        )}

        {error && <Alert className="mt-4">{error}</Alert>}
      </Panel>
    </main>
  );
}
