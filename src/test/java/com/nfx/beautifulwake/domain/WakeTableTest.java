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
 * Partitions. Against the field: heights, foam and slopes on and off the
 * grid's points, inside the V, on its edge, outside, ahead of the point,
 * port and starboard, at the reach and beyond it. The hull. Bad hulls.
 */
final class WakeTableTest {
    private static final double HULL = 1.375;
    private static final WakeTable T = WakeTable.of(HULL);

    @Test
    void theTableReadsBackTheFieldToWithinAFewThousandthsOfABlock() {
        double worst = 0.0;
        double worstAtEdge = 0.0;
        double worstFoam = 0.0;
        for (double d = -HULL * 1.5; d < WakeTable.REACH; d += 0.037) {
            double half = WakeField.halfWidth(HULL, d) + WakeField.EDGE + 0.5;
            for (double s = -half; s <= half; s += 0.041) {
                double error = Math.abs(T.height(d, s) - WakeField.height(HULL, 1.0, 1.0, d, s));
                // The V's edge is a crisp fall over a couple of samples; the
                // texture draws the edge, so the height there matters less.
                boolean atEdge = Math.abs(Math.abs(s) - WakeField.halfWidth(HULL, d)) < WakeField.EDGE + WakeTable.STEP
                        || d < -HULL * WakeField.POINT_AHEAD + WakeTable.STEP;
                if (atEdge) {
                    worstAtEdge = Math.max(worstAtEdge, error);
                } else {
                    worst = Math.max(worst, error);
                }
                worstFoam = Math.max(worstFoam, Math.abs(T.foam(d, s) - WakeField.foam(HULL, 1.0, d, s)));
            }
        }
        assertTrue(worst < 0.006, "worst height error inside " + worst);
        assertTrue(worstAtEdge < 0.04, "worst height error at the edge " + worstAtEdge);
        assertTrue(worstFoam < 0.1, "worst foam error " + worstFoam);
    }

    @Test
    void slopesFollowTheFieldAndTheAcrossSlopeIsOddInS() {
        double h = 0.01;
        for (double d : new double[] {-0.4, 0.8, 3.0, 9.5, 20.0}) {
            for (double s : new double[] {0.0, 0.4, 1.3, 2.7}) {
                double fieldD = (WakeField.height(HULL, 1.0, 1.0, d + h, s) - WakeField.height(HULL, 1.0, 1.0, d - h, s)) / (2 * h);
                double fieldS = (WakeField.height(HULL, 1.0, 1.0, d, s + h) - WakeField.height(HULL, 1.0, 1.0, d, s - h)) / (2 * h);
                assertEquals(fieldD, T.slopeD(d, s), 0.05, "slope along at " + d + "," + s);
                assertEquals(fieldS, T.slopeS(d, s), 0.05, "slope across at " + d + "," + s);
                assertEquals(-T.slopeS(d, s), T.slopeS(d, -s), 1e-9, "odd across the track");
            }
        }
    }

    @Test
    void nothingOutsideTheVAheadOfThePointOrBeyondTheGridsWidth() {
        assertEquals(0.0, T.height(-HULL * 1.2, 0.0));
        assertEquals(0.0, T.foam(-HULL * 1.2, 0.0));
        double outside = WakeField.halfWidth(HULL, 10.0) + WakeField.EDGE + 0.3;
        assertEquals(0.0, T.height(10.0, outside), 1e-6);
        assertEquals(0.0, T.height(10.0, 500.0));
        assertEquals(0.0, T.foam(10.0, 500.0));
    }

    @Test
    void beyondTheReachTheLastRowIsRead() {
        assertEquals(T.height(WakeTable.REACH + 1.0, 2.0), T.height(WakeTable.REACH + 30.0, 2.0), 1e-9);
        assertTrue(Math.abs(T.height(WakeTable.REACH + 30.0, 2.0)) < 0.01, "and there is nothing much left there");
    }

    @Test
    void theHullIsKeptAndBadOnesRefused() {
        assertEquals(HULL, T.hull());
        assertThrows(IllegalArgumentException.class, () -> WakeTable.of(0.0));
        assertThrows(IllegalArgumentException.class, () -> WakeTable.of(Double.POSITIVE_INFINITY));
    }
}
