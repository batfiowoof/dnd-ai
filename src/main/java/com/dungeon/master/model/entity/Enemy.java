package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.MonsterAction;
import com.dungeon.master.model.dto.MonsterAttack;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A hostile combatant in a session encounter. HP is authoritative server state. */
@Entity
@Table(name = "enemies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Enemy {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private String name;

    @Column(name = "max_hp", nullable = false)
    private int maxHp;

    @Column(name = "current_hp", nullable = false)
    private int currentHp;

    @Column(name = "armor_class", nullable = false)
    private int armorClass;

    @Column(name = "attack_bonus", nullable = false)
    private int attackBonus;

    @Column(name = "damage_dice", nullable = false)
    private String damageDice;

    @Column(nullable = false)
    @Builder.Default
    private int initiative = 0;

    @Column(name = "dex_mod", nullable = false)
    @Builder.Default
    private int dexMod = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean alive = true;

    /**
     * Full attack list from the monster stat block (multiattack monsters have several).
     * Empty for enemies seeded without a catalog block — the engine then falls back to
     * the single {@link #attackBonus} / {@link #damageDice} above.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<MonsterAttack> attacks = new ArrayList<>();

    /** Active conditions on the enemy (e.g. from a player's control spell), with source + duration. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<ActiveCondition> conditions = new ArrayList<>();

    /** Ability scores (STR/DEX/CON/INT/WIS/CHA) from the stat block — used for saving throws. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Integer> abilities = new LinkedHashMap<>();

    /** How many attacks this enemy makes per turn (multiattack), default 1. */
    @Column(name = "attacks_per_turn", nullable = false)
    @Builder.Default
    private int attacksPerTurn = 1;

    /** Walking speed in feet (the per-turn movement budget on the tactical grid), default 30. */
    @Column(nullable = false)
    @Builder.Default
    private int speed = 30;

    /**
     * Legendary-action options, spent between the heroes' turns. Copied from the monster's curated
     * overlay entry at spawn; empty for the great majority of creatures.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legendary_actions", columnDefinition = "jsonb")
    @Builder.Default
    private List<MonsterAction> legendaryActions = new ArrayList<>();

    /**
     * Lair-action options, fired on initiative count 20. Copied at spawn <em>only</em> when the host
     * started the encounter in the monster's lair — so a non-empty list is itself the "this fight
     * has a lair" marker.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lair_actions", columnDefinition = "jsonb")
    @Builder.Default
    private List<MonsterAction> lairActions = new ArrayList<>();

    /** Legendary-action points regained at the start of this enemy's turn; 0 = not legendary. */
    @Column(name = "legendary_action_max", nullable = false)
    @Builder.Default
    private int legendaryActionMax = 0;

    /** Legendary-action points left this round. */
    @Column(name = "legendary_actions_remaining", nullable = false)
    @Builder.Default
    private int legendaryActionsRemaining = 0;

    /** Times left this encounter that a failed saving throw may be turned into a success. */
    @Column(name = "legendary_resistances", nullable = false)
    @Builder.Default
    private int legendaryResistances = 0;

    /** True when this enemy takes legendary actions between the heroes' turns. */
    public boolean isLegendary() {
        return legendaryActionMax > 0 && legendaryActions != null && !legendaryActions.isEmpty();
    }

    /** True when this enemy's lair fights alongside it (host started the encounter in the lair). */
    public boolean hasLair() {
        return lairActions != null && !lairActions.isEmpty();
    }
}
