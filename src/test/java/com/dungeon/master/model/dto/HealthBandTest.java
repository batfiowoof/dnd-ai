package com.dungeon.master.model.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthBandTest {

    @Test
    void mapsRatiosToBands() {
        assertThat(HealthBand.of(100, 100)).isEqualTo(HealthBand.HEALTHY); // 100%
        assertThat(HealthBand.of(80, 100)).isEqualTo(HealthBand.HEALTHY);  // 80%
        assertThat(HealthBand.of(75, 100)).isEqualTo(HealthBand.HURT);     // exactly 75% → not >75
        assertThat(HealthBand.of(60, 100)).isEqualTo(HealthBand.HURT);     // 60%
        assertThat(HealthBand.of(50, 100)).isEqualTo(HealthBand.BLOODIED); // exactly 50%
        assertThat(HealthBand.of(30, 100)).isEqualTo(HealthBand.BLOODIED); // 30%
        assertThat(HealthBand.of(25, 100)).isEqualTo(HealthBand.CRITICAL); // exactly 25%
        assertThat(HealthBand.of(1, 100)).isEqualTo(HealthBand.CRITICAL);  // 1%
    }

    @Test
    void nonPositiveCurrentIsDown() {
        assertThat(HealthBand.of(0, 100)).isEqualTo(HealthBand.DOWN);
        assertThat(HealthBand.of(-5, 100)).isEqualTo(HealthBand.DOWN);
    }

    @Test
    void nonPositiveMaxIsHealthy() {
        assertThat(HealthBand.of(5, 0)).isEqualTo(HealthBand.HEALTHY);
    }
}
