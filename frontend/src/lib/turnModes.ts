import type { TurnMode } from "@/types";

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
