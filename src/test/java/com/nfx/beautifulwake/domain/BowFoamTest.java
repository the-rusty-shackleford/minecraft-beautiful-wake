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

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Partitions. Count: below the start, at full, between (grows faster than
 * linearly), bad arguments. Throwing: none, some, more than the cap; both
 * shoulders; up and outward; bigger with intensity, never past the cap, mostly small. Ticking: rising,
 * falling, settling on the water and staying, dying; alpha whole then
 * fading; clearing. Bad bubbles.
 */
final class BowFoamTest {

    @Test
    void theCountStartsPartWayUpAndGrowsFasterThanTheIntensity() {
        assertEquals(0, BowFoam.countFor(0.0, 6));
        assertEquals(0, BowFoam.countFor(BowFoam.FROM, 6));
        assertEquals(6, BowFoam.countFor(1.0, 6));
        int mid = BowFoam.countFor((BowFoam.FROM + 1.0) / 2.0, 6);
        assertTrue(mid >= 1 && mid <= 3, "a quarter or so half way, was " + mid);
        assertThrows(IllegalArgumentException.class, () -> BowFoam.countFor(1.5, 6));
        assertThrows(IllegalArgumentException.class, () -> BowFoam.countFor(0.5, -1));
    }

    @Test
    void bubblesLeaveBothShouldersUpAndOutward() {
        BowFoam foam = new BowFoam();
        foam.throwOff(new Random(7), 0.0, 63.0, 0.0, 1.0, 0.0, 1.4, 1.0, 40, 100);
        assertEquals(40, foam.bubbles().size());
        boolean port = false;
        boolean starboard = false;
        int fromTheBow = 0;
        for (BowFoam.Bubble b : foam.bubbles()) {
            assertTrue(b.vy() > 0.0, "thrown up");
            // Heading east, starboard is +z.
            boolean bow = b.x() > -1.4 * 0.1 - 1e-9;
            if (bow) {
                fromTheBow++;
                assertTrue(Math.abs(b.z()) >= 1.4 * 0.42 - 1e-9, "from the shoulders, not the middle");
                assertTrue(b.x() <= 1.4 * 0.45 + 1e-9, "at the bow");
                if (b.z() > 0.0) {
                    starboard = true;
                    assertTrue(b.vz() > 0.0, "outward to starboard");
                } else {
                    port = true;
                    assertTrue(b.vz() < 0.0, "outward to port");
                }
            } else {
                assertTrue(b.x() <= -1.4 * 0.5 + 1e-9, "the rest from the churn off the stern");
                assertTrue(Math.abs(b.z()) <= 1.4 * 0.35 + 1e-9, "in the churn's width");
            }
        }
        assertTrue(port && starboard);
        assertTrue(fromTheBow > 20, "most from the bow, had " + fromTheBow);
        double biggest = 0.0;
        double sum = 0.0;
        for (BowFoam.Bubble b : foam.bubbles()) {
            biggest = Math.max(biggest, b.size());
            sum += b.size();
        }
        assertTrue(biggest <= BowFoam.MAX_SIZE, "never a snowball, biggest was " + biggest);
        assertTrue(sum / foam.bubbles().size() < 0.08, "most are small, mean was " + sum / foam.bubbles().size());
    }

    @Test
    void aHarderPushThrowsBiggerBubbles() {
        BowFoam soft = new BowFoam();
        BowFoam hard = new BowFoam();
        soft.throwOff(new Random(3), 0, 63, 0, 1, 0, 1.4, 0.3, 30, 0);
        hard.throwOff(new Random(3), 0, 63, 0, 1, 0, 1.4, 1.0, 30, 0);
        double softSize = 0;
        double hardSize = 0;
        for (int i = 0; i < 30; i++) {
            softSize += soft.bubbles().get(i).size();
            hardSize += hard.bubbles().get(i).size();
        }
        assertTrue(hardSize > softSize * 1.1, "bigger: " + hardSize + " vs " + softSize);
    }

    @Test
    void theOldestMakeWayPastTheCap() {
        BowFoam foam = new BowFoam();
        foam.throwOff(new Random(1), 0, 63, 0, 1, 0, 1.4, 1.0, BowFoam.MAX_BUBBLES, 5);
        foam.throwOff(new Random(2), 0, 63, 0, 1, 0, 1.4, 1.0, 10, 6);
        assertEquals(BowFoam.MAX_BUBBLES, foam.bubbles().size());
        assertEquals(5, foam.bubbles().get(0).born(), "ten of the first tick's went");
        assertEquals(6, foam.bubbles().get(BowFoam.MAX_BUBBLES - 1).born());
    }

    @Test
    void aBubbleRisesFallsSettlesOnTheWaterAndDies() {
        BowFoam foam = new BowFoam();
        foam.bubbles().add(new BowFoam.Bubble(0, 63.02, 0, 0.05, 0.2, 0.0, 0.3, 0, 20));
        foam.tick(1, 63.0);
        BowFoam.Bubble b = foam.bubbles().get(0);
        assertEquals(0.05, b.x(), 1e-9);
        assertEquals(63.02 + 0.2 - BowFoam.GRAVITY, b.y(), 1e-9, "up, less gravity");
        assertEquals(0.2 - BowFoam.GRAVITY, b.vy(), 1e-9);
        int settledAt = -1;
        for (int t = 2; t < 20; t++) {
            foam.tick(t, 63.0);
            b = foam.bubbles().get(0);
            if (b.settled() && settledAt < 0) {
                settledAt = t;
            }
            if (settledAt > 0) {
                assertEquals(63.02, b.y(), 1e-9, "it stays on the water");
                assertEquals(foam.bubbles().get(0).x(), b.x(), 1e-9, "and where it landed");
            }
        }
        assertTrue(settledAt > 2, "it flew a while, settled at " + settledAt);
        assertEquals(1.0, b.alpha(5), 1e-9);
        assertTrue(b.alpha(18) > 0.0 && b.alpha(18) < 1.0, "fading near the end");
        assertEquals(0.0, b.alpha(20), 1e-9);
        foam.tick(20, 63.0);
        assertTrue(foam.bubbles().isEmpty(), "dead at the end of its life");
    }

    @Test
    void clearingDropsEverything() {
        BowFoam foam = new BowFoam();
        foam.throwOff(new Random(1), 0, 63, 0, 1, 0, 1.4, 1.0, 5, 0);
        foam.clear();
        assertTrue(foam.bubbles().isEmpty());
    }

    @Test
    void badBubblesAndBadThrowsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new BowFoam.Bubble(0, 0, 0, 0, 0, 0, 0.0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new BowFoam.Bubble(0, 0, 0, 0, 0, 0, 0.3, 0, 0));
        BowFoam foam = new BowFoam();
        assertThrows(IllegalArgumentException.class, () -> foam.throwOff(new Random(1), 0, 63, 0, 1, 0, 0.0, 1.0, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> foam.throwOff(new Random(1), 0, 63, 0, 1, 0, 1.4, 2.0, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> foam.throwOff(new Random(1), 0, 63, 0, 1, 0, 1.4, 1.0, -1, 0));
    }
}
