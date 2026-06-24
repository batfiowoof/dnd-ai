package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.enums.CombatStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An encounter's combat state: initiative order + whose turn it is. */
@Entity
@Table(name = "combat_encounters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CombatEncounter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CombatStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "initiative_order", columnDefinition = "jsonb")
    @Builder.Default
    private List<Combatant> initiativeOrder = new ArrayList<>();

    @Column(name = "active_index", nullable = false)
    @Builder.Default
    private int activeIndex = 0;

    @Column(nullable = false)
    @Builder.Default
    private int round = 1;
}
