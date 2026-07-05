package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.enums.ProficiencyLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "characters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 50)
    private String race;

    @Column(name = "\"class\"", nullable = false, length = 50)
    private String characterClass;

    @Column(nullable = false)
    @Builder.Default
    private int level = 1;

    @Column(length = 100)
    private String background;

    @Column(length = 50)
    private String alignment;

    @Column(nullable = false)
    @Builder.Default
    private int strength = 10;

    @Column(nullable = false)
    @Builder.Default
    private int dexterity = 10;

    @Column(nullable = false)
    @Builder.Default
    private int constitution = 10;

    @Column(nullable = false)
    @Builder.Default
    private int intelligence = 10;

    @Column(nullable = false)
    @Builder.Default
    private int wisdom = 10;

    @Column(nullable = false)
    @Builder.Default
    private int charisma = 10;

    @Column(name = "hit_points", nullable = false)
    @Builder.Default
    private int hitPoints = 10;

    @Column(name = "armor_class", nullable = false)
    @Builder.Default
    private int armorClass = 10;

    @Column(nullable = false)
    @Builder.Default
    private int speed = 30;

    @Column(name = "proficiency_bonus", nullable = false)
    @Builder.Default
    private int proficiencyBonus = 2;

    /**
     * Levels whose Ability Score Improvement / new-spell choices a player still owes after a
     * milestone applied the mechanical advance. Empty for normal (choices-included) level-ups.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_choice_levels", columnDefinition = "jsonb")
    @Builder.Default
    private List<Integer> pendingChoiceLevels = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> equipment = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> proficiencies = new ArrayList<>();

    /**
     * Structured skill training keyed by canonical skill name ("Stealth") → {@link ProficiencyLevel}.
     * The authoritative source for check math (expertise / half-proficiency); the flat
     * {@link #proficiencies} list is kept for back-compat and human-readable display.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_proficiencies", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, ProficiencyLevel> skillProficiencies = new LinkedHashMap<>();

    /** Ability abbreviations the class is proficient in for saving throws ("STR","CON",…). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "saving_throw_proficiencies", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> savingThrowProficiencies = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> features = new ArrayList<>();

    /** Cantrips chosen at creation (names). Display only — slot-level casting in play. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> cantrips = new ArrayList<>();

    /** Leveled spells chosen at creation (names). Display only. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "known_spells", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> knownSpells = new ArrayList<>();

    /**
     * Resolved starting equipment with quantities and item kinds. Seeds the session
     * inventory directly. Preferred over the {@link #equipment} string list, which is
     * kept for human-readable display.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "starting_inventory", columnDefinition = "jsonb")
    @Builder.Default
    private List<InventoryItem> startingInventory = new ArrayList<>();

    private String backstory;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
