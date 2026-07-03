"use client";

import { useState, useEffect, useMemo } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { useMyCharacters } from "@/hooks/useCharacterQueries";
import {
  useCreateSession,
  useJoinByCode,
} from "@/hooks/useSessionQueries";
import SessionsPanel from "@/components/SessionsPanel";
import WorldSettingPicker from "@/components/play/WorldSettingPicker";
import HostSettingsForm from "@/components/play/HostSettingsForm";
import type { Difficulty, DmLength, DmStyle, TurnMode } from "@/types";
import {
  Button,
  Panel,
  Brand,
  Field,
  Spinner,
  controlClass,
  cn,
  useToast,
} from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { rememberSession } from "@/lib/sessionStorage";

export default function PlayPage() {
  return (
    <RequireAuth>
      <PlayContent />
    </RequireAuth>
  );
}

function PlayContent() {
  const router = useRouter();
  const { username } = useAuth();

  const toast = useToast();
  const [joinCode, setJoinCode] = useState("");
  const [mode, setMode] = useState<"idle" | "create" | "join">("idle");

  // Characters (React Query)
  const charactersQuery = useMyCharacters(!!username);
  const characters = useMemo(() => charactersQuery.data ?? [], [charactersQuery.data]);
  const charsLoading = charactersQuery.isLoading;
  const [selectedCharId, setSelectedCharId] = useState<string>("");

  // Session mutations
  const createMutation = useCreateSession();
  const joinMutation = useJoinByCode();
  const loading = createMutation.isPending || joinMutation.isPending;

  // World: adventures run in a world built (or imported) in the World Builder.
  const [selectedWorldId, setSelectedWorldId] = useState("");

  // Continuing a finished adventure (set via /play?continueFrom=<sessionId>): its recap is carried
  // forward server-side. Read from the URL directly to avoid a Suspense boundary for useSearchParams.
  const [continuedFromSessionId, setContinuedFromSessionId] = useState<string | undefined>();
  useEffect(() => {
    const cont = new URLSearchParams(window.location.search).get("continueFrom");
    if (cont) {
      setContinuedFromSessionId(cont);
      setMode("create");
    }
  }, []);

  // Host session settings
  const [turnMode, setTurnMode] = useState<TurnMode>("COLLABORATIVE");
  const [maxPlayers, setMaxPlayers] = useState(4);
  const [difficulty, setDifficulty] = useState<Difficulty>("NORMAL");
  const [dmStyle, setDmStyle] = useState<DmStyle>("HEROIC");
  const [dmLength, setDmLength] = useState<DmLength>("STANDARD");
  const [allowAiCombat, setAllowAiCombat] = useState(true);
  const [allowAiRolls, setAllowAiRolls] = useState(true);
  const [allowAiDisposition, setAllowAiDisposition] = useState(true);
  const [collabWindowSeconds, setCollabWindowSeconds] = useState(10);

  // Default-select the first character once the list loads.
  useEffect(() => {
    if (characters.length > 0 && !selectedCharId) {
      setSelectedCharId(characters[0].id);
    }
  }, [characters, selectedCharId]);

  const selectedChar = characters.find((c) => c.id === selectedCharId);

  async function handleCreate() {
    if (!username || !selectedCharId) {
      toast.error("Please select a character.");
      return;
    }
    // The session starts from a built world by id — the server renders its setting, milestones,
    // and monsters. (Build or import one from the picker if none is selected.)
    if (!selectedWorldId) {
      toast.error("Please select, build, or import a world.");
      return;
    }
    try {
      const res = await createMutation.mutateAsync({
        playerName: username,
        characterId: selectedCharId,
        worldId: selectedWorldId,
        turnMode,
        maxPlayers,
        difficulty,
        dmStyle,
        dmLength,
        allowAiCombat,
        allowAiRolls,
        allowAiDisposition,
        collabWindowSeconds,
        // Authored milestones live on saved worlds (rendered server-side by worldId);
        // free-text worlds carry none.
        milestones: [],
        continuedFromSessionId,
      });
      rememberSession(res.sessionId, {
        playerId: res.playerId,
        joinCode: res.joinCode,
      });
      router.push(`/lobby/${res.sessionId}`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to create session"));
    }
  }

  async function handleJoin() {
    if (!username || !selectedCharId || !joinCode.trim()) {
      toast.error("Please select a character and enter a join code.");
      return;
    }
    try {
      const { gameState, player } = await joinMutation.mutateAsync({
        code: joinCode.toUpperCase(),
        body: {
          playerName: username,
          characterId: selectedCharId,
        },
      });
      rememberSession(gameState.sessionId, {
        playerId: player.id,
        joinCode: gameState.joinCode,
      });
      router.push(`/lobby/${gameState.sessionId}`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to join session"));
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
            {continuedFromSessionId && (
              <div className="rounded-lg border border-gold/40 bg-gold/5 px-3 py-2 text-xs text-gold">
                Continuing your previous adventure — the DM will pick up the story where it left off.
              </div>
            )}
            <WorldSettingPicker
              selectedWorldId={selectedWorldId}
              setSelectedWorldId={setSelectedWorldId}
            />

            <hr className="ornament my-2" />

            <HostSettingsForm
              turnMode={turnMode}
              setTurnMode={setTurnMode}
              collabWindowSeconds={collabWindowSeconds}
              setCollabWindowSeconds={setCollabWindowSeconds}
              maxPlayers={maxPlayers}
              setMaxPlayers={setMaxPlayers}
              difficulty={difficulty}
              setDifficulty={setDifficulty}
              dmStyle={dmStyle}
              setDmStyle={setDmStyle}
              dmLength={dmLength}
              setDmLength={setDmLength}
              allowAiCombat={allowAiCombat}
              setAllowAiCombat={setAllowAiCombat}
              allowAiRolls={allowAiRolls}
              setAllowAiRolls={setAllowAiRolls}
              allowAiDisposition={allowAiDisposition}
              setAllowAiDisposition={setAllowAiDisposition}
            />

            <Button
              onClick={handleCreate}
              disabled={!selectedCharId}
              loading={loading}
              fullWidth
            >
              {loading ? "Creating..." : "Create New Session"}
            </Button>
            <Button
              onClick={() => setMode("idle")}
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
              onClick={() => setMode("idle")}
              variant="ghost"
              fullWidth
            >
              Back
            </Button>
          </div>
        )}
      </Panel>
    </main>
  );
}
