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
 * Partitions. Foam: below the start, at it, between (heavier is more),
 * at full, capped, lingering past the ring, at the end, past it, bad
 * arguments. Strength: no mass, gentle entry, a fall, the cap, bad
 * arguments. Ring: at birth, mid-life (past half its spread), at the end,
 * past it; a bigger splash spreads wider; alpha by strength and age.
 * Droplets and bubbles: nothing, an apple, a player, the caps. Ripple RI
 * and age.
 */
final class SplashTest {

    @Test
    void foamComesOnlyWithAHeavyEnoughSplashAndLingersAsItFades() {
        assertEquals(0.0, Splash.foamAlpha(0.5, 0, 18), "an apple set on the water: none");
        assertEquals(0.0, Splash.foamAlpha(Splash.FOAM_FROM, 0, 18), "at the start: none yet");
        double ingot = Splash.foamAlpha(1.5, 0, 18);
        double block = Splash.foamAlpha(2.0, 0, 18);
        assertTrue(ingot > 0.0 && ingot < block, "heavier, more: " + ingot + " vs " + block);
        assertEquals(1.0, Splash.foamAlpha(Splash.FOAM_FULL, 0, 18), 1e-9);
        assertEquals(1.0, Splash.foamAlpha(Splash.MAX_STRENGTH, 0, 18), 1e-9, "capped");
        assertTrue(Splash.foamAlpha(3.0, 9, 18) > Splash.ringAlpha(3.0, 9, 18), "foam outlasts the ring");
        assertEquals(0.0, Splash.foamAlpha(3.0, 18, 18), 1e-9);
        assertEquals(0.0, Splash.foamAlpha(3.0, 40, 18), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Splash.foamAlpha(3.5, 0, 18));
        assertThrows(IllegalArgumentException.class, () -> Splash.foamAlpha(1.0, -1, 18));
    }

    @Test
    void strengthIsMassTimesAFactorThatGrowsWithTheFall() {
        assertEquals(0.0, Splash.strength(0.0, 0.5), 1e-9);
        assertEquals(0.5, Splash.strength(1.0, 0.0), 1e-9);              // set on the water
        assertEquals(1.0, Splash.strength(1.0, 0.2), 1e-9);              // a short drop
        assertEquals(1.0, Splash.strength(1.0, -0.2), 1e-9);             // speed's sign is irrelevant
        assertEquals(Splash.MAX_STRENGTH, Splash.strength(4.0, 1.0), 1e-9);
        assertTrue(Splash.strength(0.35, 0.3) < Splash.strength(0.9, 0.3), "an apple splashes less than an ingot");
        assertThrows(IllegalArgumentException.class, () -> Splash.strength(-1.0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> Splash.strength(1.0, Double.NaN));
    }

    @Test
    void theRingSpreadsFastThenSlowAndWiderForABiggerSplash() {
        assertEquals(0.4, Splash.ringRadius(1.0, 0, 18), 1e-9);
        assertEquals(1.8, Splash.ringRadius(1.0, 18, 18), 1e-9);
        assertEquals(1.8, Splash.ringRadius(1.0, 180, 18), 1e-9);
        double half = Splash.ringRadius(1.0, 9, 18);
        assertTrue(half > (0.4 + 1.8) / 2.0, "past half its spread at half its life");
        assertTrue(Splash.ringRadius(0.3, 18, 18) < Splash.ringRadius(2.0, 18, 18));
        assertThrows(IllegalArgumentException.class, () -> Splash.ringRadius(1.0, -1, 18));
        assertThrows(IllegalArgumentException.class, () -> Splash.ringRadius(4.0, 0, 18));
        assertThrows(IllegalArgumentException.class, () -> Splash.ringRadius(1.0, 0, 0));
    }

    @Test
    void theRingFadesToNothingAndAFaintSplashStartsFaint() {
        assertEquals(1.0, Splash.ringAlpha(1.0, 0, 18), 1e-9);
        assertEquals(1.0, Splash.ringAlpha(2.5, 0, 18), 1e-9);
        assertEquals(0.3, Splash.ringAlpha(0.3, 0, 18), 1e-9);
        assertEquals(0.0, Splash.ringAlpha(1.0, 18, 18), 1e-9);
        assertTrue(Splash.ringAlpha(1.0, 9, 18) < 0.5, "faster toward the end");
    }

    @Test
    void dropletsAndBubblesScaleWithTheSplashAndAreCapped() {
        assertEquals(0, Splash.droplets(0.0));
        assertEquals(2, Splash.droplets(0.35));
        assertEquals(6, Splash.droplets(1.0));
        assertEquals(18, Splash.droplets(3.0));
        assertEquals(0, Splash.bubbles(0.0));
        assertEquals(1, Splash.bubbles(0.35));
        assertEquals(9, Splash.bubbles(3.0));
    }

    @Test
    void aRippleKnowsItsAgeAndRefusesNonsense() {
        Ripple ripple = new Ripple(1.0, 62.0, 2.0, 1.0, 100);
        assertEquals(0, ripple.age(90));
        assertEquals(5, ripple.age(105));
        assertThrows(IllegalArgumentException.class, () -> new Ripple(Double.NaN, 62.0, 2.0, 1.0, 100));
        assertThrows(IllegalArgumentException.class, () -> new Ripple(1.0, 62.0, 2.0, 5.0, 100));
    }
}
