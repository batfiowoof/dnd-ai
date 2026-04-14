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

  // World setting
  const [worldSource, setWorldSource] = useState<
    "preset" | "custom-write" | "custom-upload"
  >("preset");
  const [selectedPreset, setSelectedPreset] = useState(PRESET_WORLDS[0].id);
  const [customWorldText, setCustomWorldText] = useState("");
  const [expandedPreset, setExpandedPreset] = useState<string | null>(null);

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

  function getWorldSetting(): string | undefined {
    if (worldSource === "preset") {
      return PRESET_WORLDS.find((w) => w.id === selectedPreset)?.setting;
    }
    if (
      worldSource === "custom-write" ||
      worldSource === "custom-upload"
    ) {
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
    setLoading(true);
    try {
      const token = await getToken();
      if (!token) throw new Error("Not authenticated");
      const res = await createSession(token, {
        playerName: username,
        characterId: selectedCharId,
        worldSetting,
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
      <div className="w-full max-w-lg rounded-xl border border-border-accent bg-surface p-8 glow">
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
          <div className="space-y-4">
            {/* World setting source tabs */}
            <div>
              <label className="mb-2 block text-xs font-semibold uppercase tracking-wider text-text-muted">
                World Setting
              </label>
              <div className="flex rounded-lg border border-border bg-bg text-xs">
                <button
                  onClick={() => setWorldSource("preset")}
                  className={`flex-1 rounded-l-lg px-3 py-2 font-medium transition ${
                    worldSource === "preset"
                      ? "bg-accent text-white"
                      : "text-text-muted hover:text-text"
                  }`}
                >
                  Presets
                </button>
                <button
                  onClick={() => setWorldSource("custom-write")}
                  className={`flex-1 border-x border-border px-3 py-2 font-medium transition ${
                    worldSource === "custom-write"
                      ? "bg-accent text-white"
                      : "text-text-muted hover:text-text"
                  }`}
                >
                  Write
                </button>
                <button
                  onClick={() => setWorldSource("custom-upload")}
                  className={`flex-1 rounded-r-lg px-3 py-2 font-medium transition ${
                    worldSource === "custom-upload"
                      ? "bg-accent text-white"
                      : "text-text-muted hover:text-text"
                  }`}
                >
                  Upload
                </button>
              </div>
            </div>

            {/* Preset world cards */}
            {worldSource === "preset" && (
              <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
                {PRESET_WORLDS.map((world) => (
                  <div key={world.id}>
                    <button
                      onClick={() => setSelectedPreset(world.id)}
                      className={`w-full rounded-lg border px-4 py-3 text-left transition ${
                        selectedPreset === world.id
                          ? "border-accent bg-accent/10"
                          : "border-border bg-bg hover:border-accent/50"
                      }`}
                    >
                      <div className="flex items-center justify-between">
                        <div>
                          <p className="text-sm font-semibold text-text">
                            {world.name}
                          </p>
                          <p className="text-xs text-text-muted">
                            {world.tagline}
                          </p>
                        </div>
                        <div className="flex items-center gap-2">
                          <span
                            className={`h-3 w-3 rounded-full border-2 transition ${
                              selectedPreset === world.id
                                ? "border-accent bg-accent"
                                : "border-border"
                            }`}
                          />
                        </div>
                      </div>
                    </button>
                    {selectedPreset === world.id && (
                      <button
                        onClick={() =>
                          setExpandedPreset(
                            expandedPreset === world.id ? null : world.id
                          )
                        }
                        className="mt-1 ml-1 text-xs text-accent hover:underline"
                      >
                        {expandedPreset === world.id
                          ? "Hide details"
                          : "Show details"}
                      </button>
                    )}
                    {expandedPreset === world.id && (
                      <div className="mt-1 max-h-40 overflow-y-auto rounded-lg border border-border bg-bg p-3 text-xs text-text-muted whitespace-pre-wrap">
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
                className="w-full rounded-lg border border-border bg-bg px-4 py-3 text-sm text-text placeholder-text-muted outline-none transition focus:border-accent focus:ring-1 focus:ring-accent resize-none"
              />
            )}

            {/* Custom upload */}
            {worldSource === "custom-upload" && (
              <div className="space-y-3">
                <label className="flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed border-border bg-bg px-4 py-6 transition hover:border-accent">
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
                        className="text-xs text-accent hover:underline"
                      >
                        Clear
                      </button>
                    </div>
                    <div className="max-h-32 overflow-y-auto rounded-lg border border-border bg-bg p-3 text-xs text-text-muted whitespace-pre-wrap">
                      {customWorldText}
                    </div>
                  </div>
                )}
              </div>
            )}

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
