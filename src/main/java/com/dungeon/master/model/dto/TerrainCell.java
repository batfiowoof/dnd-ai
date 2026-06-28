package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.TerrainType;

/**
 * A single grid square flagged with special terrain. Squares with no
 * {@code TerrainCell} are plain open floor.
 *
 * @param x    column (0-based)
 * @param y    row (0-based)
 * @param type the kind of terrain occupying this square
 */
public record TerrainCell(int x, int y, TerrainType type) {
}
