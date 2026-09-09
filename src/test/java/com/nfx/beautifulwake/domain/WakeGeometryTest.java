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

import com.nfx.beautifulwake.domain.WakeGeometry.Params;
import com.nfx.beautifulwake.domain.WakeGeometry.Quad;
import com.nfx.beautifulwake.domain.WakeGeometry.Vertex;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Partitions. Trails: fewer than two samples, samples on top of each other,
 * a straight run, a run too slow for a wake, a mixed run. The strip: one quad
 * per segment, corners either side of the track, width by age and intensity,
 * alpha by age, v pinned to ticks, lift applied. The arms: two per segment,
 * opening at the Kelvin angle, mirrored port and starboard, following a
 * turn. Params RI.
 */
final class WakeGeometryTest {

    private static final Params P = new Params(1.4, 2.5, 80, 0.075, 0.35, 10.0, 0.35, 0.02);

    /** A boat heading east at {@code speed} blocks a tick, sampled every tick for {@code ticks} ticks, from tick 0. */
    private static List<Sample> eastward(double speed, int ticks) {
        Sample[] out = new Sample[ticks];
        for (int t = 0; t < ticks; t++) {
            out[t] = new Sample(t * speed, 62.0, 0.0, t);
        }
        return List.of(out);
    }

    @Test
    void paramsRefuseNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new Params(0, 2.5, 80, 0.1, 0.3, 10, 0.3, 0));
        assertThrows(IllegalArgumentException.class, () -> new Params(1, 0.9, 80, 0.1, 0.3, 10, 0.3, 0));
        assertThrows(IllegalArgumentException.class, () -> new Params(1, 2.5, 0, 0.1, 0.3, 10, 0.3, 0));
        assertThrows(IllegalArgumentException.class, () -> new Params(1, 2.5, 80, 0.3, 0.1, 10, 0.3, 0));
        assertThrows(IllegalArgumentException.class, () -> new Params(1, 2.5, 80, 0.1, 0.3, 0, 0.3, 0));
        assertThrows(IllegalArgumentException.class, () -> new Params(1, 2.5, 80, 0.1, 0.3, 10, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Params(1, 2.5, 80, 0.1, 0.3, 10, 0.3, 0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new Params(1, 2.5, 80, 0.1, 0.3, 10, 0.3, 0, 1.5));
        assertEquals(1.0, P.strength(), "a hull is at full strength");
    }

    @Test
    void aSwimmersStrengthScalesTheWholeWakeDown() {
        Params touch = new Params(0.6, 2.5, 80, 0.075, 0.35, 10.0, 0.35, 0.02, 0.5);
        List<Sample> run = eastward(0.4, 5);
        Vertex newest = WakeGeometry.foamStrip(run, 4, touch).get(3).c();
        assertEquals(0.5f, newest.alpha(), 1e-6);
        assertEquals(0.6 * WakeGeometry.STERN_FACTOR * Math.sqrt(0.5) / 2.0, newest.z(), 1e-9);
    }

    @Test
    void aFloorLiftsTheSlowestWakeAndLeavesTheFastestAlone() {
        Params floored = new Params(1.4, 2.5, 80, 0.075, 0.35, 10.0, 0.35, 0.02, 1.0, 0.35);
        Vertex slow = WakeGeometry.foamStrip(eastward(0.08, 5), 4, floored).get(3).c();     // just above the minimum
        assertEquals(0.35f + 0.65f * (0.08f - 0.075f) / 0.275f, slow.alpha(), 0.002f);
        Vertex fast = WakeGeometry.foamStrip(eastward(0.4, 5), 4, floored).get(3).c();
        assertEquals(1.0f, fast.alpha(), 1e-6);
        assertTrue(WakeGeometry.foamStrip(eastward(0.05, 5), 4, floored).isEmpty(), "below the minimum is still nothing");
    }

    @Test
    void theHaloIsTheStripScaledWiderAndFainter() {
        List<Sample> run = eastward(0.4, 5);
        Vertex core = WakeGeometry.foamStrip(run, 4, P).get(3).c();
        Vertex halo = WakeGeometry.foamStrip(run, 4, P, 1.7, 0.35).get(3).c();
        assertEquals(core.z() * 1.7, halo.z(), 1e-9);
        assertEquals(core.alpha() * 0.35f, halo.alpha(), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> WakeGeometry.foamStrip(run, 4, P, 0.0, 1.0));
    }

    @Test
    void theBowCrestSitsAheadOfTheBowAcrossTheTrackAndOnlyWhenThereIsAWake() {
        List<Sample> run = eastward(0.4, 5);                 // the bow at x = 1.6, heading east
        List<Quad> crest = WakeGeometry.bowCrest(run, 4, P);
        assertEquals(1, crest.size());
        Quad q = crest.get(0);
        assertEquals(1.6 + 1.4 * 0.55, q.a().x(), 1e-9);
        assertEquals(1.6 + 1.4 * 0.55 + WakeGeometry.BOW_CREST_LENGTH, q.c().x(), 1e-9);
        assertEquals(-1.4 * 0.75, q.a().z(), 1e-9);
        assertEquals(1.4 * 0.75, q.b().z(), 1e-9);
        assertEquals(1.0f, q.a().alpha(), 1e-6);
        assertTrue(WakeGeometry.bowCrest(eastward(0.05, 5), 4, P).isEmpty(), "no crest without a wake");
        assertTrue(WakeGeometry.bowCrest(List.of(), 4, P).isEmpty());
    }

    @Test
    void nothingIsDrawnForFewerThanTwoPlacesOrForAStandingBoat() {
        assertTrue(WakeGeometry.foamStrip(List.of(), 10, P).isEmpty());
        assertTrue(WakeGeometry.foamStrip(List.of(new Sample(0, 62, 0, 1)), 10, P).isEmpty());
        List<Sample> still = List.of(new Sample(0, 62, 0, 1), new Sample(0, 62, 0, 2), new Sample(0, 62, 0, 3));
        assertTrue(WakeGeometry.foamStrip(still, 10, P).isEmpty());
        assertTrue(WakeGeometry.arms(still, 10, P).isEmpty());
        // Moving, but below the minimum: no wake either.
        assertTrue(WakeGeometry.foamStrip(eastward(0.05, 10), 10, P).isEmpty());
    }

    @Test
    void theStripIsOneQuadPerSegmentEitherSideOfTheTrack() {
        List<Sample> run = eastward(0.4, 5);              // full speed, ticks 0..4
        List<Quad> quads = WakeGeometry.foamStrip(run, 4, P);
        assertEquals(4, quads.size());
        Quad last = quads.get(3);                         // between ticks 3 and 4
        // Heading east: starboard is south (+z). a is port at the older sample, b starboard.
        assertEquals(1.2, last.a().x(), 1e-9);
        assertTrue(last.a().z() < 0 && last.b().z() > 0, "corners either side of the track");
        assertEquals(-last.a().z(), last.b().z(), 1e-9);
        assertEquals(62.02, last.a().y(), 1e-9);
        // The newest sample: age 0, full intensity, the churned stern's width, fully opaque.
        assertEquals(1.4 * WakeGeometry.STERN_FACTOR / 2.0, last.c().z(), 1e-9);
        assertEquals(1.0f, last.c().alpha());
        assertEquals(0.4f, last.c().v(), 1e-6);
        assertEquals(0.3f, last.a().v(), 1e-6);
        assertEquals(0.0f, last.a().u());
        assertEquals(1.0f, last.b().u());
    }

    @Test
    void foamWidensAndFadesWithAge() {
        List<Sample> run = eastward(0.4, 81);             // ticks 0..80
        List<Quad> quads = WakeGeometry.foamStrip(run, 80, P);
        Vertex oldest = quads.get(0).a();                 // tick 0, age 80: the end of its life
        Vertex newest = quads.get(quads.size() - 1).c();  // tick 80, age 0
        assertEquals(0.0f, oldest.alpha(), 1e-6);
        assertEquals(1.0f, newest.alpha(), 1e-6);
        assertEquals(1.4 * WakeGeometry.STERN_FACTOR * 2.5 / 2.0, Math.abs(oldest.z()), 1e-9);
        assertEquals(1.4 * WakeGeometry.STERN_FACTOR / 2.0, Math.abs(newest.z()), 1e-9);
    }

    @Test
    void theArmsOpenAtTheKelvinAngleAndMirrorEachOther() {
        List<Sample> run = eastward(0.4, 11);             // ticks 0..10, 4 blocks long
        List<Quad> arms = WakeGeometry.arms(run, 10, P);
        assertEquals(20, arms.size(), "two arms of ten segments");
        // The oldest sample lies 4 blocks behind the bow: its arm point is 4 tan(19.47) out.
        double expected = 4.0 * Math.tan(Wake.KELVIN_HALF_ANGLE);
        Quad port = arms.get(0);
        Quad starboard = arms.get(10);
        double portCentre = (port.a().z() + port.b().z()) / 2.0;
        double starboardCentre = (starboard.a().z() + starboard.b().z()) / 2.0;
        assertEquals(-expected, portCentre, 1e-9);
        assertEquals(expected, starboardCentre, 1e-9);
        // The far end of an arm has tapered to a fraction of its width at the bow.
        assertEquals(0.35 * WakeGeometry.ARM_TAPER, port.b().z() - port.a().z(), 1e-9);
        // At the bow the arms meet on the track, at their full width.
        Quad bow = arms.get(9);
        assertEquals(0.0, (bow.c().z() + bow.d().z()) / 2.0, 1e-9);
        assertEquals(0.35, bow.c().z() - bow.d().z(), 1e-9);
        assertTrue(bow.c().alpha() < 1.0f && bow.c().alpha() > 0.9f, "arm foam nearly as solid as the strip's");
    }

    @Test
    void theArmsFollowATurn() {
        // East for five blocks, then north for five: the arm points of the
        // eastward leg are pushed out along z, the northward leg's along x.
        List<Sample> track = List.of(
                new Sample(0, 62, 0, 0), new Sample(5, 62, 0, 10),
                new Sample(5, 62, -5, 20));
        List<Quad> arms = WakeGeometry.arms(track, 20, P);
        assertEquals(4, arms.size());
        Quad eastLegPort = arms.get(0);
        Quad northLegPort = arms.get(1);
        double tan = Math.tan(Wake.KELVIN_HALF_ANGLE);
        // The first sample lies 10 blocks behind along the track; heading east, port is north (-z).
        assertEquals(-10.0 * tan, (eastLegPort.a().z() + eastLegPort.b().z()) / 2.0, 1e-9);
        // The corner sample lies 5 behind and its heading is north; port of north is west (-x).
        assertEquals(5.0 - 5.0 * tan, (northLegPort.a().x() + northLegPort.b().x()) / 2.0, 1e-9);
    }

    @Test
    void samplesOnTopOfEachOtherAreCollapsedSoEveryQuadHasAHeading() {
        List<Sample> stopAndGo = List.of(
                new Sample(0, 62, 0, 0), new Sample(0, 62, 0, 1), new Sample(0, 62, 0, 2), new Sample(0.4, 62, 0, 3));
        List<Quad> quads = WakeGeometry.foamStrip(stopAndGo, 3, P);
        assertEquals(1, quads.size());
        for (Vertex v : List.of(quads.get(0).a(), quads.get(0).b(), quads.get(0).c(), quads.get(0).d())) {
            assertTrue(Double.isFinite(v.x()) && Double.isFinite(v.z()), "finite corners");
        }
    }
}
