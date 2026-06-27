package com.dungeon.master.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
}
