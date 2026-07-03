package com.dungeon.master.model.mapper;

import com.dungeon.master.model.dto.WorldDto;
import com.dungeon.master.model.dto.WorldSummaryDto;
import com.dungeon.master.model.entity.World;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link World} entities to their read DTOs. Keeps entities from crossing the web boundary.
 * Plain Spring component (no MapStruct), consistent with {@link PlayerMapper}.
 */
@Component
public class WorldMapper {

    public WorldDto toDto(World w) {
        return new WorldDto(
                w.getId(),
                w.getName(),
                w.getTagline(),
                w.getOverview(),
                w.getTone(),
                w.getMagicLevel(),
                w.getRegions(),
                w.getFactions(),
                w.getNpcs(),
                w.getCustomMonsters(),
                w.getMilestones(),
                w.getQuests(),
                w.getShops(),
                w.getCreatedAt(),
                w.getUpdatedAt());
    }

    public WorldSummaryDto toSummary(World w) {
        return new WorldSummaryDto(
                w.getId(),
                w.getName(),
                w.getTagline(),
                w.getTone(),
                size(w.getRegions()),
                size(w.getFactions()),
                size(w.getNpcs()),
                size(w.getCustomMonsters()),
                size(w.getMilestones()),
                size(w.getQuests()),
                size(w.getShops()),
                w.getUpdatedAt());
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
