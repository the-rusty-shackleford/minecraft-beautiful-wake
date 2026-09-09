/*
 * Beautiful Wake - the water behind a boat.
 * Copyright (C) 2026 Rusty Shackleford and nfx
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.nfx.beautifulwake.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Partitions. Intensity: below the minimum, at it, just above it (no jump), between (a curve), at full, above,
 * bad bounds, bad speed. Foam alpha: at the stern,
 * mid-life (holds early), at the end, past it, by intensity. Spray: below
 * the start, at full, between (grows faster than linearly), bad arguments.
 * The Kelvin angle itself.
 */
final class WakeTest {

    @Test
    void theKelvinHalfAngleIsNineteenAndAHalfDegrees() {
        assertEquals(19.47, Math.toDegrees(Wake.KELVIN_HALF_ANGLE), 0.01);
    }

    @Test
    void intensityIsNothingBelowTheMinimumAndEverythingAtFull() {
        assertEquals(0.0, Wake.intensity(0.0, 0.075, 0.35));
        assertEquals(0.0, Wake.intensity(0.075, 0.075, 0.35));
        assertEquals(1.0, Wake.intensity(0.35, 0.075, 0.35));
        assertEquals(1.0, Wake.intensity(2.0, 0.075, 0.35));
    }

    @Test
    void intensityRisesQuicklyFromNothingAndEasesTowardFull() {
        double quarter = Wake.intensity(0.15, 0.1, 0.3);
        double half = Wake.intensity(0.2, 0.1, 0.3);
        assertEquals(Math.pow(0.25, Wake.RAMP), quarter, 1e-9);
        assertEquals(Math.pow(0.5, Wake.RAMP), half, 1e-9);
        assertTrue(quarter > 0.25 && half > 0.5, "ahead of a straight line");
        assertTrue(Wake.intensity(0.1000001, 0.1, 0.3) < 0.01, "but from nothing, not a jump: " + Wake.intensity(0.1000001, 0.1, 0.3));
    }

    @Test
    void aFlooredIntensityStartsAtTheFloorAndStillEndsAtOne() {
        assertEquals(0.0, Wake.intensity(0.075, 0.075, 0.35, 0.35));
        assertEquals(0.35, Wake.intensity(0.0751, 0.075, 0.35, 0.35), 0.01);
        assertEquals(0.35 + 0.65 * Math.pow(0.5, Wake.RAMP), Wake.intensity(0.2125, 0.075, 0.35, 0.35), 1e-9);
        assertEquals(1.0, Wake.intensity(0.35, 0.075, 0.35, 0.35));
        assertThrows(IllegalArgumentException.class, () -> Wake.intensity(0.1, 0.075, 0.35, 1.5));
    }

    @Test
    void intensityRefusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> Wake.intensity(0.1, 0.3, 0.2));
        assertThrows(IllegalArgumentException.class, () -> Wake.intensity(0.1, -0.1, 0.2));
        assertThrows(IllegalArgumentException.class, () -> Wake.intensity(-0.1, 0.0, 0.2));
        assertThrows(IllegalArgumentException.class, () -> Wake.intensity(Double.NaN, 0.0, 0.2));
    }

    @Test
    void foamIsSolidAtTheSternBreaksUpQuicklyAndIsGoneAtTheEndOfItsLife() {
        assertEquals(1.0, Wake.foamAlpha(1.0, 0, 80), 1e-9);
        assertEquals(Math.pow(0.5, 1.5), Wake.foamAlpha(1.0, 40, 80), 1e-9);
        assertTrue(Wake.foamAlpha(1.0, 20, 80) < 0.7, "breaking up a quarter of the way along");
        assertEquals(0.0, Wake.foamAlpha(1.0, 80, 80), 1e-9);
        assertEquals(0.0, Wake.foamAlpha(1.0, 200, 80), 1e-9);
        assertEquals(0.5 * Math.pow(0.5, 1.5), Wake.foamAlpha(0.5, 40, 80), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Wake.foamAlpha(1.0, 0, 0));
    }

    @Test
    void theFadeIsFoamAlphasCurveAtAnyFractionOfATick() {
        assertEquals(1.0, Wake.fade(0.0, 80), 1e-9);
        assertEquals(Wake.foamAlpha(1.0, 40, 80), Wake.fade(40.0, 80), 1e-9);
        assertTrue(Wake.fade(40.25, 80) < Wake.fade(40.0, 80) && Wake.fade(40.25, 80) > Wake.fade(40.5, 80), "moves between ticks");
        assertEquals(0.0, Wake.fade(80.0, 80), 1e-9);
        assertEquals(0.0, Wake.fade(800.0, 80), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Wake.fade(-1.0, 80));
        assertThrows(IllegalArgumentException.class, () -> Wake.fade(1.0, 0));
    }

    @Test
    void sprayStartsPartWayUpAndGrowsFasterThanTheSpeedDoes() {
        assertEquals(0, Wake.sprayCount(0.0, 0.3, 8));
        assertEquals(0, Wake.sprayCount(0.3, 0.3, 8));
        assertEquals(8, Wake.sprayCount(1.0, 0.3, 8));
        int mid = Wake.sprayCount(0.65, 0.3, 8);
        assertTrue(mid > 0 && mid < 4, "half way up the ramp throws less than half: " + mid);
        assertThrows(IllegalArgumentException.class, () -> Wake.sprayCount(1.1, 0.3, 8));
        assertThrows(IllegalArgumentException.class, () -> Wake.sprayCount(0.5, 1.0, 8));
        assertThrows(IllegalArgumentException.class, () -> Wake.sprayCount(0.5, 0.3, -1));
    }
}
