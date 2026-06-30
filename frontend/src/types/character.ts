import type { InventoryItem } from "./player";

/* ── Character types ──────────────────────────────────────────── */

export interface CharacterDto {
  id: string;
  name: string;
  race: string;
  characterClass: string;
  level: number;
  background: string | null;
  alignment: string | null;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  hitPoints: number;
  armorClass: number;
  speed: number;
  proficiencyBonus: number;
  equipment: string[];
  proficiencies: string[];
  features: string[];
  cantrips: string[];
  knownSpells: string[];
  startingInventory: InventoryItem[];
  /** Levels whose ASI/spell choices the player still owes after a milestone advance. */
  pendingChoiceLevels: number[];
  backstory: string | null;
  imageUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

/** +2 to one ability, or +1 to two different abilities (cap 20, enforced server-side). */
export type AsiMode = "PLUS_TWO" | "TWO_PLUS_ONE";

export interface AbilityScoreImprovement {
  mode: AsiMode;
  /** Lower-cased ability name (e.g. "constitution"). */
  first: string;
  /** Second target for TWO_PLUS_ONE; omitted/null for PLUS_TWO. */
  second?: string | null;
}

export interface CharacterLevelUpRequest {
  /** Required only at ASI levels (4/8/12/16/19); null otherwise. */
  asi: AbilityScoreImprovement | null;
  /** New cantrip names to append (casters only). */
  newCantrips: string[];
  /** New leveled-spell names to append (casters only). */
  newSpells: string[];
}

export interface CharacterCreateUpdateRequest {
  name: string;
  race: string;
  characterClass: string;
  level: number;
  background: string;
  alignment: string;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  hitPoints: number;
  armorClass: number;
  speed: number;
  equipment: string[];
  proficiencies: string[];
  features: string[];
  cantrips: string[];
  knownSpells: string[];
  startingInventory: InventoryItem[];
  backstory: string;
  imageUrl: string;
}
