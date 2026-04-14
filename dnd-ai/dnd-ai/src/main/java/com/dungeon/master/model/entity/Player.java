package com.dungeon.master.model.entity;

import com.dungeon.master.model.enums.PlayerRole;
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

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "players")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(name = "character_name")
    private String characterName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "character_sheet", columnDefinition = "jsonb")
    private Map<String, Object> characterSheet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PlayerRole role = PlayerRole.PLAYER;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "character_id")
    private UUID characterId;

    @Column(name = "turn_index", nullable = false)
    @Builder.Default
    private int turnIndex = 0;
}
