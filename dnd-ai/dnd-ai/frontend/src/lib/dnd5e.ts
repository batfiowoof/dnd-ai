/* ── D&D 5E reference data for character creation ─────────── */

export interface RaceInfo {
  name: string;
  abilityBonuses: Record<string, number>;
  speed: number;
  traits: string[];
  description: string;
}

export interface ClassInfo {
  name: string;
  hitDie: number;
  primaryAbility: string;
  savingThrows: string[];
  proficiencies: string[];
  equipment: string[];
  description: string;
}

export const RACES: RaceInfo[] = [
  {
    name: "Human",
    abilityBonuses: { strength: 1, dexterity: 1, constitution: 1, intelligence: 1, wisdom: 1, charisma: 1 },
    speed: 30,
    traits: ["Extra Language"],
    description: "Versatile and ambitious, humans are the most adaptable and driven people among the common races.",
  },
  {
    name: "Elf",
    abilityBonuses: { dexterity: 2 },
    speed: 30,
    traits: ["Darkvision", "Keen Senses", "Fey Ancestry", "Trance"],
    description: "Elves are a magical people of otherworldly grace, living in the world but not entirely part of it.",
  },
  {
    name: "Dwarf",
    abilityBonuses: { constitution: 2 },
    speed: 25,
    traits: ["Darkvision", "Dwarven Resilience", "Stonecunning"],
    description: "Bold and hardy, dwarves are known as skilled warriors, miners, and workers of stone and metal.",
  },
  {
    name: "Halfling",
    abilityBonuses: { dexterity: 2 },
    speed: 25,
    traits: ["Lucky", "Brave", "Halfling Nimbleness"],
    description: "The diminutive halflings survive in a world full of larger creatures by avoiding notice or, failing that, avoiding offense.",
  },
  {
    name: "Gnome",
    abilityBonuses: { intelligence: 2 },
    speed: 25,
    traits: ["Darkvision", "Gnome Cunning"],
    description: "A gnome's energy and enthusiasm for living shines through every inch of their tiny body.",
  },
  {
    name: "Half-Elf",
    abilityBonuses: { charisma: 2 },
    speed: 30,
    traits: ["Darkvision", "Fey Ancestry", "Skill Versatility"],
    description: "Half-elves combine what some say are the best qualities of their elf and human parents.",
  },
  {
    name: "Half-Orc",
    abilityBonuses: { strength: 2, constitution: 1 },
    speed: 30,
    traits: ["Darkvision", "Menacing", "Relentless Endurance", "Savage Attacks"],
    description: "Half-orcs' grayish pigmentation, sloping foreheads, jutting jaws, and prominent teeth mark their orcish heritage.",
  },
  {
    name: "Tiefling",
    abilityBonuses: { intelligence: 1, charisma: 2 },
    speed: 30,
    traits: ["Darkvision", "Hellish Resistance", "Infernal Legacy"],
    description: "Tieflings are derived from human bloodlines linked to the Nine Hells, bearing an appearance and nature influenced by their infernal heritage.",
  },
  {
    name: "Dragonborn",
    abilityBonuses: { strength: 2, charisma: 1 },
    speed: 30,
    traits: ["Draconic Ancestry", "Breath Weapon", "Damage Resistance"],
    description: "Born of dragons, dragonborn walk proudly through a world that greets them with fearful incomprehension.",
  },
];

export const CLASSES: ClassInfo[] = [
  {
    name: "Barbarian",
    hitDie: 12,
    primaryAbility: "Strength",
    savingThrows: ["Strength", "Constitution"],
    proficiencies: ["Light armor", "Medium armor", "Shields", "Simple weapons", "Martial weapons"],
    equipment: ["Greataxe", "Two handaxes", "Explorer's pack", "Four javelins"],
    description: "A fierce warrior who can enter a battle rage.",
  },
  {
    name: "Bard",
    hitDie: 8,
    primaryAbility: "Charisma",
    savingThrows: ["Dexterity", "Charisma"],
    proficiencies: ["Light armor", "Simple weapons", "Hand crossbows", "Longswords", "Rapiers", "Shortswords"],
    equipment: ["Rapier", "Diplomat's pack", "Lute", "Leather armor", "Dagger"],
    description: "An inspiring magician whose power echoes the music of creation.",
  },
  {
    name: "Cleric",
    hitDie: 8,
    primaryAbility: "Wisdom",
    savingThrows: ["Wisdom", "Charisma"],
    proficiencies: ["Light armor", "Medium armor", "Shields", "Simple weapons"],
    equipment: ["Mace", "Scale mail", "Light crossbow and 20 bolts", "Priest's pack", "Shield", "Holy symbol"],
    description: "A priestly champion who wields divine magic in service of a higher power.",
  },
  {
    name: "Druid",
    hitDie: 8,
    primaryAbility: "Wisdom",
    savingThrows: ["Intelligence", "Wisdom"],
    proficiencies: ["Light armor", "Medium armor", "Shields", "Clubs", "Daggers", "Darts", "Javelins", "Maces", "Quarterstaffs", "Scimitars", "Sickles", "Slings", "Spears"],
    equipment: ["Wooden shield", "Scimitar", "Leather armor", "Explorer's pack", "Druidic focus"],
    description: "A priest of the Old Faith, wielding the powers of nature and adopting animal forms.",
  },
  {
    name: "Fighter",
    hitDie: 10,
    primaryAbility: "Strength or Dexterity",
    savingThrows: ["Strength", "Constitution"],
    proficiencies: ["All armor", "Shields", "Simple weapons", "Martial weapons"],
    equipment: ["Chain mail", "Martial weapon and shield", "Light crossbow and 20 bolts", "Dungeoneer's pack"],
    description: "A master of martial combat, skilled with a variety of weapons and armor.",
  },
  {
    name: "Monk",
    hitDie: 8,
    primaryAbility: "Dexterity & Wisdom",
    savingThrows: ["Strength", "Dexterity"],
    proficiencies: ["Simple weapons", "Shortswords"],
    equipment: ["Shortsword", "Dungeoneer's pack", "10 darts"],
    description: "A master of martial arts, harnessing the power of the body in pursuit of physical and spiritual perfection.",
  },
  {
    name: "Paladin",
    hitDie: 10,
    primaryAbility: "Strength & Charisma",
    savingThrows: ["Wisdom", "Charisma"],
    proficiencies: ["All armor", "Shields", "Simple weapons", "Martial weapons"],
    equipment: ["Martial weapon and shield", "Five javelins", "Priest's pack", "Chain mail", "Holy symbol"],
    description: "A holy warrior bound to a sacred oath.",
  },
  {
    name: "Ranger",
    hitDie: 10,
    primaryAbility: "Dexterity & Wisdom",
    savingThrows: ["Strength", "Dexterity"],
    proficiencies: ["Light armor", "Medium armor", "Shields", "Simple weapons", "Martial weapons"],
    equipment: ["Scale mail", "Two shortswords", "Dungeoneer's pack", "Longbow and 20 arrows"],
    description: "A warrior who combats threats on the edges of civilization.",
  },
  {
    name: "Rogue",
    hitDie: 8,
    primaryAbility: "Dexterity",
    savingThrows: ["Dexterity", "Intelligence"],
    proficiencies: ["Light armor", "Simple weapons", "Hand crossbows", "Longswords", "Rapiers", "Shortswords"],
    equipment: ["Rapier", "Shortbow and 20 arrows", "Burglar's pack", "Leather armor", "Two daggers", "Thieves' tools"],
    description: "A scoundrel who uses stealth and trickery to overcome obstacles and enemies.",
  },
  {
    name: "Sorcerer",
    hitDie: 6,
    primaryAbility: "Charisma",
    savingThrows: ["Constitution", "Charisma"],
    proficiencies: ["Daggers", "Darts", "Slings", "Quarterstaffs", "Light crossbows"],
    equipment: ["Light crossbow and 20 bolts", "Component pouch", "Dungeoneer's pack", "Two daggers"],
    description: "A spellcaster who draws on inherent magic from a gift or bloodline.",
  },
  {
    name: "Warlock",
    hitDie: 8,
    primaryAbility: "Charisma",
    savingThrows: ["Wisdom", "Charisma"],
    proficiencies: ["Light armor", "Simple weapons"],
    equipment: ["Light crossbow and 20 bolts", "Component pouch", "Scholar's pack", "Leather armor", "Simple weapon", "Two daggers"],
    description: "A wielder of magic derived from a bargain with an extraplanar entity.",
  },
  {
    name: "Wizard",
    hitDie: 6,
    primaryAbility: "Intelligence",
    savingThrows: ["Intelligence", "Wisdom"],
    proficiencies: ["Daggers", "Darts", "Slings", "Quarterstaffs", "Light crossbows"],
    equipment: ["Quarterstaff", "Component pouch", "Scholar's pack", "Spellbook"],
    description: "A scholarly magic-user capable of manipulating the structures of reality.",
  },
];

export const BACKGROUNDS = [
  "Acolyte",
  "Charlatan",
  "Criminal",
  "Entertainer",
  "Folk Hero",
  "Guild Artisan",
  "Hermit",
  "Noble",
  "Outlander",
  "Sage",
  "Sailor",
  "Soldier",
  "Urchin",
];

export const ALIGNMENTS = [
  "Lawful Good",
  "Neutral Good",
  "Chaotic Good",
  "Lawful Neutral",
  "True Neutral",
  "Chaotic Neutral",
  "Lawful Evil",
  "Neutral Evil",
  "Chaotic Evil",
];

export const STANDARD_ARRAY = [15, 14, 13, 12, 10, 8];

export const POINT_BUY_COSTS: Record<number, number> = {
  8: 0, 9: 1, 10: 2, 11: 3, 12: 4, 13: 5, 14: 7, 15: 9,
};

export const POINT_BUY_TOTAL = 27;

export const ABILITY_NAMES = [
  "strength",
  "dexterity",
  "constitution",
  "intelligence",
  "wisdom",
  "charisma",
] as const;

export type AbilityName = (typeof ABILITY_NAMES)[number];

export function getAbilityModifier(score: number): number {
  return Math.floor((score - 10) / 2);
}

export function formatModifier(mod: number): string {
  return mod >= 0 ? `+${mod}` : `${mod}`;
}

export function calculateHitPoints(classInfo: ClassInfo, constitution: number): number {
  const conMod = getAbilityModifier(constitution);
  return classInfo.hitDie + conMod;
}

export function calculateArmorClass(dexterity: number): number {
  return 10 + getAbilityModifier(dexterity);
}
