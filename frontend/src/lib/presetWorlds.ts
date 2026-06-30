import type { TurnMode, Milestone } from "@/types";

/**
 * Ready-made world settings offered on the create-session screen. Each carries authored
 * `milestones` — the only story beats at which the DM may level the party (it cannot invent
 * its own). They persist onto the session and are fired once each via the `awardMilestone` tool.
 */
export const PRESET_WORLDS: {
  id: string;
  name: string;
  tagline: string;
  setting: string;
  milestones: Milestone[];
}[] = [
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
    milestones: [
      {
        key: "claim-a-ship",
        title: "Masters of the Tide",
        description:
          "The party secures their own seaworthy vessel and a loyal crew, becoming true captains of the Shattered Isles.",
      },
      {
        key: "drowned-spire-secret",
        title: "Secret of the Drowned Spire",
        description:
          "The heroes brave the half-submerged wizard's tower at low tide and recover a clue to the Titan's Eye.",
      },
      {
        key: "break-crimson-fleet",
        title: "Break the Crimson Fleet",
        description:
          "Captain Maelstra's pirate armada is defeated, routed, or turned aside in open naval battle.",
      },
      {
        key: "titans-eye",
        title: "The Titan's Eye",
        description:
          "The party claims the Titan's Eye and confronts the cataclysmic power sleeping within it.",
      },
    ],
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
    milestones: [
      {
        key: "into-the-dark",
        title: "Into the Dark",
        description:
          "The party survives their first deep descent and establishes a foothold amid the ruins of Kharn-Dural.",
      },
      {
        key: "pact-of-the-spore",
        title: "Pact of the Spore",
        description:
          "The heroes earn the trust — or the lasting enmity — of the Spore Collective in the Mycelium Sea.",
      },
      {
        key: "silence-the-mindforge",
        title: "Silence the Mindforge",
        description:
          "The mind flayer research facility is destroyed and its captive surface-dwellers freed.",
      },
      {
        key: "reclaim-kharn-dural",
        title: "Reclaim Kharn-Dural",
        description:
          "The heart of the dwarven capital is retaken, deciding the fate of the Ironvault.",
      },
    ],
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
    milestones: [
      {
        key: "bargain-at-the-threshold",
        title: "Bargain at the Threshold",
        description:
          "The party strikes its first true fey bargain and learns the unspoken rules of Aeloria.",
      },
      {
        key: "favor-of-summer",
        title: "Favor of the Summer Court",
        description:
          "The heroes win real standing in the Everbloom Court's beautiful and ruthless intrigues.",
      },
      {
        key: "through-murkhollow",
        title: "Through Murkhollow",
        description:
          "The party survives the Shadowfell-touched swamp and outwits the hags who broker its dark deals.",
      },
      {
        key: "the-archfeys-wish",
        title: "The Archfey's Wish",
        description:
          "The heroes reach the heart of the Thornwood Labyrinth and confront the wish that always twists.",
      },
    ],
  },
];

/** Narrative turn-handling modes shown as selectable cards on the host settings form. */
export const TURN_MODES: { value: TurnMode; name: string; desc: string }[] = [
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
