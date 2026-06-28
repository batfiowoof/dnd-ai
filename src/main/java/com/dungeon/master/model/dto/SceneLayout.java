package com.dungeon.master.model.dto;

import java.util.List;

/**
 * An LLM-designed battle map, requested by {@code SceneGenerator} as structured output and
 * converted into a {@link GridState} by {@code GridService.fromSceneLayout}. Kept deliberately
 * simple so the chat model can fill it reliably; dimensions are clamped and out-of-bounds
 * specs are dropped on conversion.
 *
 * @param width    columns (clamped to 10–20)
 * @param height   rows (clamped to 8–16)
 * @param terrain  special terrain squares (sparse — walls/difficult/hazard)
 * @param features decorative labelled points of interest
 */
public record SceneLayout(
        int width,
        int height,
        List<TerrainCellSpec> terrain,
        List<FeatureSpec> features
) {
    /** A terrain square the model proposes; {@code type} is WALL, DIFFICULT, or HAZARD. */
    public record TerrainCellSpec(int x, int y, String type) {}

    /** A labelled point of interest the model proposes (no mechanical effect). */
    public record FeatureSpec(int x, int y, String label) {}
}
