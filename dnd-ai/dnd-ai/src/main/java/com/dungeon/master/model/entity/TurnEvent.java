package com.dungeon.master.model.entity;

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

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "turn_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TurnEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String action;

    @Column(name = "dm_response", columnDefinition = "TEXT")
    private String dmResponse;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;
}
