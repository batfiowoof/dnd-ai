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
  backstory: string | null;
  imageUrl: string | null;
  createdAt: string;
  updatedAt: string;
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
