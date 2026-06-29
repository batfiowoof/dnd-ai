package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.SpellSlot;
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spell_slots", columnDefinition = "jsonb")
    @Builder.Default
    private List<SpellSlot> spellSlots = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<InventoryItem> inventory = new ArrayList<>();

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
}
