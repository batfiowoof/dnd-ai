package com.dungeon.master.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the disposition score→band mapping and clamping. */
class DispositionBandTest {

    @Test
    void bandsCoverTheBoundaries() {
        assertEquals(DispositionBand.HOSTILE, DispositionBand.fromScore(-100));
        assertEquals(DispositionBand.HOSTILE, DispositionBand.fromScore(-45));
        assertEquals(DispositionBand.UNFRIENDLY, DispositionBand.fromScore(-44));
        assertEquals(DispositionBand.UNFRIENDLY, DispositionBand.fromScore(-15));
        assertEquals(DispositionBand.NEUTRAL, DispositionBand.fromScore(-14));
        assertEquals(DispositionBand.NEUTRAL, DispositionBand.fromScore(0));
        assertEquals(DispositionBand.NEUTRAL, DispositionBand.fromScore(14));
        assertEquals(DispositionBand.FRIENDLY, DispositionBand.fromScore(15));
        assertEquals(DispositionBand.FRIENDLY, DispositionBand.fromScore(44));
        assertEquals(DispositionBand.DEVOTED, DispositionBand.fromScore(45));
        assertEquals(DispositionBand.DEVOTED, DispositionBand.fromScore(100));
    }

    @Test
    void clampAndOutOfRangeScoresResolveToTheEndBands() {
        assertEquals(-100, DispositionBand.clamp(-500));
        assertEquals(100, DispositionBand.clamp(500));
        assertEquals(DispositionBand.HOSTILE, DispositionBand.fromScore(-999));
        assertEquals(DispositionBand.DEVOTED, DispositionBand.fromScore(999));
    }
}
