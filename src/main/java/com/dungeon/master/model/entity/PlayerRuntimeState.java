package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.SpellSlot;
import com.dungeon.master.model.enums.ProficiencyLevel;
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

/**
 * Per-session runtime state for a player. The id IS the {@link Player} id
 * (shared primary key) — one runtime row per session player. Seeded from the
 * linked {@code Character} template on join; mutated only by the backend.
 */
@Entity
@Table(name = "player_runtime_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerRuntimeState {

    @Id
    @Column(name = "player_id")
    private UUID playerId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "current_hp", nullable = false)
    private int currentHp;

    @Column(name = "max_hp", nullable = false)
    private int maxHp;

    @Column(name = "temp_hp", nullable = false)
    @Builder.Default
    private int tempHp = 0;

    /** Whether the player currently holds Inspiration (spendable on a roll for advantage). */
    @Column(name = "inspiration", nullable = false)
    @Builder.Default
    private boolean inspiration = false;

    /** Armor class, snapshotted from the Character template on join. */
    @Column(name = "armor_class", nullable = false)
    @Builder.Default
    private int armorClass = 10;

    /** Ability scores (STR/DEX/CON/INT/WIS/CHA), snapshotted from the Character template. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Integer> abilities = new LinkedHashMap<>();

    /** Skill training keyed by canonical skill name → {@link ProficiencyLevel}, copied from the Character. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_proficiencies", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, ProficiencyLevel> skillProficiencies = new LinkedHashMap<>();

    /** Ability abbreviations proficient for saving throws ("STR","CON",…), copied from the Character. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "saving_throw_proficiencies", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> savingThrowProficiencies = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spell_slots", columnDefinition = "jsonb")
    @Builder.Default
    private List<SpellSlot> spellSlots = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<InventoryItem> inventory = new ArrayList<>();

    /**
     * Display names of the magic items this player is currently attuned to (max 3, per the SRD).
     * Attunement gates an attunement-required item's mechanical effects (see {@code MagicItemEffects}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attuned_items", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> attunedItems = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<ActiveCondition> conditions = new ArrayList<>();

    /**
     * Name of the concentration spell this player is currently sustaining, or {@code null}.
     * Casting a new concentration spell, or dropping to 0 HP, breaks it — clearing every
     * condition this player applied with concentration.
     */
    @Column(name = "concentrating_spell")
    private String concentratingSpell;

    /** Cantrips known, copied from the Character template. Display only (slot-level casting). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> cantrips = new ArrayList<>();

    /** Leveled spells known, copied from the Character template. Display only. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "known_spells", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> knownSpells = new ArrayList<>();

    /** Death saving throw successes (0–3) while at 0 HP. Foundation for Phase C. */
    @Column(name = "death_save_successes", nullable = false)
    @Builder.Default
    private int deathSaveSuccesses = 0;

    /** Death saving throw failures (0–3) while at 0 HP. Foundation for Phase C. */
    @Column(name = "death_save_failures", nullable = false)
    @Builder.Default
    private int deathSaveFailures = 0;

    /** Whether the player is stable at 0 HP (no longer rolling death saves). */
    @Column(name = "stable", nullable = false)
    @Builder.Default
    private boolean stable = false;

    /** Whether the player has died (three death-save failures). */
    @Column(name = "dead", nullable = false)
    @Builder.Default
    private boolean dead = false;

    /** Class hit die size (d6/d8/d10/d12 → 6/8/10/12), snapshotted from the character's class. */
    @Column(name = "hit_die_size", nullable = false)
    @Builder.Default
    private int hitDieSize = 8;

    /** Total Hit Dice = character level; the size of the short-rest healing pool. */
    @Column(name = "hit_dice_total", nullable = false)
    @Builder.Default
    private int hitDiceTotal = 1;

    /** Hit Dice still available to spend on a short rest (a long rest restores half, min one). */
    @Column(name = "hit_dice_remaining", nullable = false)
    @Builder.Default
    private int hitDiceRemaining = 1;

    /** 5e exhaustion level (0–6). +1 per 24h in-game without a long rest, −1 per long rest; 6 is death. */
    @Column(name = "exhaustion_level", nullable = false)
    @Builder.Default
    private int exhaustionLevel = 0;

    /** The {@code in_game_minutes} marking the start of the current awake window; each full day past it tires. */
    @Column(name = "exhaustion_check_minutes", nullable = false)
    @Builder.Default
    private long exhaustionCheckMinutes = 0;

    /**
     * The player's coin purse, in <b>copper</b> (1 gp = 100 cp, 1 sp = 10 cp). The single source of
     * truth for money — quest coin rewards, starting coin, and shop buy/sell all move this balance.
     * See {@link com.dungeon.master.service.game.MoneyUtil}.
     */
    @Column(name = "copper", nullable = false)
    @Builder.Default
    private long copper = 0;
}
