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
 * Partitions. The V: at the centre, behind it (opens at the Kelvin
 * angle), ahead (rounds off in a nose, a boat's and a swimmer's), inside,
 * on the edge, in the fade, beyond it, at the nose's tip, a bad nose. Height: no
 * intensity or relief, the bow wave (a hump ahead of the bow, the tallest
 * thing), the trough (a dip right behind), chevrons (ridges at whole
 * phases, troughs at halves, port and starboard alike, none before their
 * start, dying with distance), all of it inside the V and none outside,
 * scaling with intensity and relief, bad arguments. Chevron phase: the
 * same either side, growing back, falling outward, constant along a
 * ridge, the ridge pointing at the hull; the base and the envelope apart.
 * Foam: solid at the stern,
 * thinner behind, none outside the churn far back, none ahead of the bow,
 * the bow's shoulders, bounded by one, none at zero intensity, bad
 * arguments.
 */
final class WakeFieldTest {
    private static final double HULL = 1.4;
    private static final double NOSE = WakeField.HULL_NOSE;

    @Test
    void theVIsAlittleOverHalfAHullAtTheCentreOpensAtTheKelvinAngleAndRoundsOffAheadInANose() {
        assertEquals(HULL * 0.65, WakeField.halfWidth(HULL, NOSE, 0.0), 1e-9);
        double ten = WakeField.halfWidth(HULL, NOSE, 10.0);
        assertEquals(Math.tan(Wake.KELVIN_HALF_ANGLE), (ten - HULL * 0.65) / 10.0, 1e-9);
        // A half-ellipse: still nearly full a little ahead, rounding off to nothing at the nose's tip.
        assertEquals(HULL * 0.65 * Math.sqrt(0.75), WakeField.halfWidth(HULL, NOSE, -HULL * NOSE / 2.0), 1e-9, "half way to the tip, nearly as wide");
        assertTrue(WakeField.halfWidth(HULL, NOSE, -HULL * NOSE * 0.95) < HULL * 0.65 * 0.35, "rounding off near the tip");
        assertEquals(0.0, WakeField.halfWidth(HULL, NOSE, -HULL * NOSE), 1e-9, "the tip");
        assertEquals(0.0, WakeField.halfWidth(HULL, NOSE, -HULL * 2.0), 1e-9, "nothing beyond it");
        // A swimmer's nose is shorter, so nothing shows ahead of a wader's body.
        assertEquals(0.0, WakeField.halfWidth(0.9, WakeField.SWIMMER_NOSE, -0.9 * WakeField.SWIMMER_NOSE), 1e-9);
        assertTrue(0.9 * WakeField.SWIMMER_NOSE < 0.35, "inside a player's body");
        assertThrows(IllegalArgumentException.class, () -> WakeField.halfWidth(HULL, -0.1, 1.0));
        // A boat has no nose: nothing ahead of the centre, and the centre row itself is whole.
        assertEquals(0.0, WakeField.halfWidth(HULL, WakeField.BOAT_NOSE, -0.01), 1e-9);
        assertEquals(0.0, WakeField.edgeFade(HULL, WakeField.BOAT_NOSE, -0.01, 0.0), 1e-9);
        assertEquals(1.0, WakeField.edgeFade(HULL, WakeField.BOAT_NOSE, 0.0, 0.0), 1e-9);
        assertEquals(HULL * 0.65, WakeField.halfWidth(HULL, WakeField.BOAT_NOSE, 0.0), 1e-9);
    }

    @Test
    void theEdgeIsWholeInsideFadesOverTheEdgeAndIsNothingBeyondOrFarAhead() {
        double half = WakeField.halfWidth(HULL, NOSE, 8.0);
        assertEquals(1.0, WakeField.edgeFade(HULL, NOSE, 8.0, 0.0), 1e-9);
        assertEquals(1.0, WakeField.edgeFade(HULL, NOSE, 8.0, -half), 1e-9, "the edge itself is inside");
        double mid = WakeField.edgeFade(HULL, NOSE, 8.0, half + WakeField.EDGE / 2.0);
        assertTrue(mid > 0.0 && mid < 1.0, "half way out is half way faded, was " + mid);
        assertEquals(0.0, WakeField.edgeFade(HULL, NOSE, 8.0, half + WakeField.EDGE), 1e-9);
        assertEquals(0.0, WakeField.edgeFade(HULL, NOSE, -HULL * NOSE, 0.0), 1e-9, "at the nose's tip");
        assertEquals(1.0, WakeField.edgeFade(HULL, NOSE, -HULL * 0.4, 0.0), 1e-9, "on the track short of the tip");
    }

    @Test
    void nothingHappensWithoutIntensityOrRelief() {
        assertEquals(0.0, WakeField.height(HULL, NOSE, 0.0, 1.0, 1.0, 0.0));
        assertEquals(0.0, WakeField.height(HULL, NOSE, 1.0, 0.0, 1.0, 0.0));
        assertEquals(0.0, WakeField.foam(HULL, NOSE, 0.0, 1.0, 0.0));
    }

    @Test
    void theBowWaveIsAHumpAtTheBowAndTheTallestThingInTheField() {
        double bow = WakeField.height(HULL, NOSE, 1.0, 1.0, -HULL * 0.3, 0.0);
        assertEquals(WakeField.BOW_HEIGHT, bow, 0.02, "the hump's crest is the bow height");
        assertTrue(WakeField.height(HULL, NOSE, 1.0, 1.0, -HULL * 0.3, HULL * 0.3) < bow, "lower to the side");
        double tallest = 0.0;
        for (double d = -2.0; d < 30.0; d += 0.1) {
            for (double s = -12.0; s <= 12.0; s += 0.1) {
                tallest = Math.max(tallest, WakeField.height(HULL, NOSE, 1.0, 1.0, d, s));
            }
        }
        assertTrue(tallest <= bow + 1e-9 && tallest > bow * 0.95, "nothing taller than the bow: " + tallest + " vs " + bow);
    }

    @Test
    void theSternLeavesATroughRightBehindTheHull() {
        assertTrue(WakeField.height(HULL, NOSE, 1.0, 1.0, HULL * 0.6, 0.0) < -0.03, "a dip behind the stern");
    }

    @Test
    void theChevronsAreRidgesAtWholePhasesTroughsAtHalvesAndTheSameEitherSide() {
        double d = 20.0;
        // Pick s so the phase is whole, then half: outward from the track the phase falls.
        double whole = Math.floor(WakeField.chevronPhase(d, 0.0)) - 1.0;
        double sRidge = solveS(d, whole);
        double sTrough = solveS(d, whole - 0.5);
        assertTrue(sTrough < WakeField.halfWidth(HULL, NOSE, d), "both inside the V");
        double ridge = WakeField.height(HULL, NOSE, 1.0, 1.0, d, sRidge);
        double trough = WakeField.height(HULL, NOSE, 1.0, 1.0, d, sTrough);
        assertTrue(ridge > 0.02, "a ridge stands up, was " + ridge);
        assertTrue(trough < -0.02, "a trough dips, was " + trough);
        assertEquals(ridge, WakeField.height(HULL, NOSE, 1.0, 1.0, d, -sRidge), 1e-9, "port is starboard's mirror");
    }

    @Test
    void theChevronsStartHalfAHullBackAndDieAwayWithDistance() {
        for (double d = -2.0; d <= HULL * WakeField.CHEVRON_FROM; d += 0.1) {
            assertEquals(0.0, WakeField.chevronHeight(HULL, d, 0.0), 0.0, "none before they start, at d=" + d);
        }
        double rising = Math.abs(WakeField.chevronHeight(HULL, HULL * (WakeField.CHEVRON_FROM + 0.3), solveS(HULL * (WakeField.CHEVRON_FROM + 0.3), 0.0)));
        assertTrue(rising > 0.0 && rising < WakeField.CHEVRON_HEIGHT * 0.5, "rising over the next hull, was " + rising);
        double near = ridgeHeight(6.0);
        double far = ridgeHeight(40.0);
        assertTrue(near > far * 2.0, "ridges die away: near " + near + " far " + far);
        assertEquals(WakeField.CHEVRON_HEIGHT * Math.exp(-6.0 / WakeField.CHEVRON_DECAY), WakeField.chevronHeight(HULL, 6.0, solveS(6.0, 1.0)), 1e-9, "a ridge at a whole phase");
    }

    @Test
    void theBaseIsTheHeightWithoutTheChevronsAndTheEnvelopeIsTheirHeight() {
        double d = 10.0;
        double s = 1.0;
        assertEquals(WakeField.height(HULL, NOSE, 1.0, 1.0, d, s),
                WakeField.base(HULL, NOSE, 1.0, 1.0, d, s) + WakeField.chevronHeight(HULL, d, s), 1e-12);
        assertEquals(0.0, WakeField.chevronEnvelope(HULL, HULL * WakeField.CHEVRON_FROM), 0.0);
        assertEquals(WakeField.CHEVRON_HEIGHT * Math.exp(-d / WakeField.CHEVRON_DECAY), WakeField.chevronEnvelope(HULL, d), 1e-12);
        assertEquals(WakeField.chevronEnvelope(HULL, d) * Math.cos(2 * Math.PI * WakeField.chevronPhase(d, s)), WakeField.chevronHeight(HULL, d, s), 1e-12);
        assertTrue(WakeField.chevronPhasePerBlockBack() > 0.0, "back along the track the phase grows");
        assertTrue(WakeField.chevronPhasePerBlockOut() < 0.0, "outward it falls");
    }

    @Test
    void everythingIsInsideTheVAndScalesWithIntensityAndRelief() {
        for (double d = -3.0; d < 40.0; d += 0.5) {
            double outside = WakeField.halfWidth(HULL, NOSE, d) + WakeField.EDGE + 0.01;
            assertEquals(0.0, WakeField.height(HULL, NOSE, 1.0, 1.0, d, outside), 0.0, "outside at d=" + d);
            assertEquals(0.0, WakeField.foam(HULL, NOSE, 1.0, d, outside), 0.0, "no foam outside at d=" + d);
        }
        double full = WakeField.height(HULL, NOSE, 1.0, 1.0, 8.0, 1.0);
        assertEquals(full / 2.0, WakeField.height(HULL, NOSE, 0.5, 1.0, 8.0, 1.0), 1e-9);
        assertEquals(full / 4.0, WakeField.height(HULL, NOSE, 1.0, 0.25, 8.0, 1.0), 1e-9);
    }

    @Test
    void theChevronPhaseGrowsBackFallsOutwardAndIsConstantAlongARidgePointingAtTheHull() {
        assertEquals(WakeField.chevronPhase(5.0, 2.0), WakeField.chevronPhase(5.0, -2.0), 1e-12);
        assertTrue(WakeField.chevronPhase(6.0, 0.0) > WakeField.chevronPhase(5.0, 0.0));
        assertTrue(WakeField.chevronPhase(5.0, 1.0) < WakeField.chevronPhase(5.0, 0.0));
        // Along a ridge the phase is constant: the ridge runs back and outward at the chevron angle.
        double along = 3.0;
        double dd = along * Math.cos(WakeField.CHEVRON_ANGLE);
        double ds = along * Math.sin(WakeField.CHEVRON_ANGLE);
        assertEquals(WakeField.chevronPhase(5.0, 1.0), WakeField.chevronPhase(5.0 + dd, 1.0 + ds), 1e-9);
        // So a ridge's point, on the track, is nearer the hull than its ends: the chevron points forward.
        double ridgeOnTrack = solveD(0.0, 3.0);
        double ridgeOutboard = solveD(2.0, 3.0);
        assertTrue(ridgeOutboard > ridgeOnTrack, "outboard the ridge is further back");
    }

    @Test
    void foamIsSolidAtTheSternThinsBehindAndSitsOnTheBowsShoulders() {
        double stern = WakeField.foam(HULL, NOSE, 1.0, HULL * 0.6, 0.0);
        double back = WakeField.foam(HULL, NOSE, 1.0, HULL * 4.0, 0.0);
        double farBack = WakeField.foam(HULL, NOSE, 1.0, 40.0, 0.0);
        assertTrue(stern > 0.9, "solid at the stern, was " + stern);
        assertTrue(back < stern && back > 0.1, "thinner a few hulls back, was " + back);
        assertTrue(farBack < 0.02, "gone far back, was " + farBack);
        assertTrue(WakeField.foam(HULL, NOSE, 1.0, -HULL * 0.3, HULL * 0.52) > 0.8, "the bow's shoulder foams hard");
        assertTrue(WakeField.foam(HULL, NOSE, 1.0, -HULL * 0.3, 0.0) < WakeField.foam(HULL, NOSE, 1.0, -HULL * 0.3, HULL * 0.52), "less between the shoulders");
        assertTrue(WakeField.foam(HULL, NOSE, 1.0, HULL * 0.2, HULL * 0.55) > 0.3, "streaming back along the side");
        assertEquals(0.0, WakeField.foam(HULL, NOSE, 1.0, -HULL * 0.8, 0.0), 0.0, "nothing ahead of the nose");
        for (double d = -2.0; d < 20.0; d += 0.3) {
            for (double s = -6.0; s <= 6.0; s += 0.3) {
                double f = WakeField.foam(HULL, NOSE, 1.0, d, s);
                assertTrue(f >= 0.0 && f <= 1.0, "foam in [0, 1] at " + d + "," + s + " was " + f);
            }
        }
    }

    @Test
    void washIsWhiteWaterRoundTheBodyItself() {
        assertEquals(WakeField.foam(HULL, NOSE, 1.0, 0.2, 0.0), WakeField.foam(HULL, NOSE, 1.0, 0.2, 0.0, 0.0), 1e-12, "no wash is the plain foam");
        assertTrue(WakeField.foam(HULL, NOSE, 1.0, 0.0, HULL * 0.4, 1.0) > WakeField.foam(HULL, NOSE, 1.0, 0.0, HULL * 0.4, 0.0) + 0.3, "wash whitens beside the body");
        assertEquals(WakeField.foam(HULL, NOSE, 1.0, 12.0, 0.0, 1.0), WakeField.foam(HULL, NOSE, 1.0, 12.0, 0.0, 0.0), 1e-6, "and not far behind it");
        assertThrows(IllegalArgumentException.class, () -> WakeField.foam(HULL, NOSE, 1.0, 0.0, 0.0, 1.5));
    }

    @Test
    void badArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> WakeField.height(0.0, NOSE, 1.0, 1.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> WakeField.height(HULL, NOSE, 1.1, 1.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> WakeField.height(HULL, NOSE, 1.0, -1.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> WakeField.foam(HULL, NOSE, -0.1, 1.0, 0.0));
    }

    /** The starboard offset at which the phase is {@code phase}, {@code d} behind the hull's centre. */
    private static double solveS(double d, double phase) {
        return (d * Math.sin(WakeField.CHEVRON_ANGLE) - phase * WakeField.CHEVRON_WAVELENGTH) / Math.cos(WakeField.CHEVRON_ANGLE);
    }

    /** The distance behind at which the phase is {@code phase}, {@code s} off the track. */
    private static double solveD(double s, double phase) {
        return (phase * WakeField.CHEVRON_WAVELENGTH + Math.abs(s) * Math.cos(WakeField.CHEVRON_ANGLE)) / Math.sin(WakeField.CHEVRON_ANGLE);
    }

    /** How far the ridge nearest the track stands above the trough inside it, {@code d} behind. */
    private static double ridgeHeight(double d) {
        double phase = Math.floor(WakeField.chevronPhase(d, 0.0) - 0.5);
        return WakeField.chevronHeight(HULL, d, solveS(d, phase)) - WakeField.chevronHeight(HULL, d, solveS(d, phase + 0.5));
    }
}
