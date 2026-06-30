package com.dungeon.master.model.entity;

import com.dungeon.master.model.enums.DocumentCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "world_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorldDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentCategory category;

    /**
     * Session this document belongs to, or null when global (e.g. the bundled SRD RULES). LORE and
     * SESSION_HISTORY rows are scoped so retrieval never crosses campaigns.
     */
    @Column(name = "session_id")
    private UUID sessionId;

    @Transient
    private float[] embedding;
}
