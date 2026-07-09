package com.dungeon.master.model.dto;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A combatant's piece on the grid. Mutable on purpose — later phases update a
 * token's position and per-turn action-economy flags in place. Keyed in
 * {@link GridState#getTokens()} by the combatant's refId (player/enemy UUID as a String).
 *
 * <p>Phase A only sets {@code x}/{@code y} (placement); the movement/reaction/dash/
 * disengage/dodge fields are foundation for Phase B and stay at their defaults.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Token {

    /** Grid column (0-based). */
    private int x;

    /** Grid row (0-based). */
    private int y;

    /** Feet of movement already spent this turn. */
    private int movementUsedFeet;

    /** Whether this combatant still has its reaction available this round. */
    private boolean reactionAvailable = true;

    /** Whether the combatant took the Dash action this turn. */
    private boolean dashed;

    /** Whether the combatant took the Disengage action this turn. */
    private boolean disengaged;

    /** Whether the combatant is Dodging this turn. */
    private boolean dodging;

    /** Whether the combatant has spent its action this turn (Dash/Disengage/Dodge/Attack/Cast/Item are mutually exclusive). */
    private boolean actionUsed;

    /** Whether the combatant has spent its bonus action this turn (e.g. a Bonus-Action spell). */
    private boolean bonusActionUsed;

    /**
     * When set, this combatant is holding its reaction for a spell (Shield/Absorb Elements) and
     * will NOT auto-take opportunity attacks. Cleared each turn in {@code resetTurnFlags}.
     */
    private boolean holdingReaction;

    /**
     * A readied attack: the enemy this player will strike as a reaction when it first comes within
     * reach/range this round. Null when nothing is readied; cleared each turn or when it fires.
     */
    private UUID readiedTargetEnemyId;
}
