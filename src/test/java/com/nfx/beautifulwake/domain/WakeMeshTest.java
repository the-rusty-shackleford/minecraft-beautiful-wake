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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Partitions. Samples: none, one, two, many, coincident. Columns: even,
 * too few, odd. The table: another hull's. Rows: ahead of the bow, the
 * bow, behind, thinned with age; every row the same length; the middle
 * column on the track. Heights: the bow's hump
 * above the water, ridges and dips behind, nothing at rest. Normals: unit,
 * upright on flat water, leaning on a slope. Skin and foam: inside the V,
 * none outside, fading with age. Mappings: the edge coordinate zero on the
 * V's edge and clamped inside, the chevron lines hidden before the
 * chevrons start and the phase continuous, the foam pinned to the tick. A curved track bends the
 * grid; a tight turn holds the inside short. Shade: flat, toward, away,
 * clamped, on a mesh. A trail at rest makes no mesh.
 */
final class WakeMeshTest {
    private static final WakeParams P = new WakeParams(1.4, 90, 0.075, 0.35, 20.0, 0.015);
    private static final WakeTable T = WakeTable.of(1.4);
    private static final int COLUMNS = 25;

    /** A straight run east at {@code speed} for {@code ticks}, the newest sample at tick {@code ticks}. */
    private static List<Sample> run(double speed, int ticks) {
        List<Sample> samples = new ArrayList<>();
        for (int t = 0; t <= ticks; t++) {
            samples.add(new Sample(t * speed, 63.0, 0.0, t));
        }
        return samples;
    }

    @Test
    void fewerThanTwoSamplesApartMakeNoMesh() {
        assertEquals(0, WakeMesh.build(List.of(), 10, P, COLUMNS, T).quads());
        assertEquals(0, WakeMesh.build(List.of(new Sample(0, 63, 0, 1)), 10, P, COLUMNS, T).quads());
        assertEquals(0, WakeMesh.build(List.of(new Sample(0, 63, 0, 1), new Sample(0, 63, 0, 2)), 10, P, COLUMNS, T).quads());
    }

    @Test
    void theTableMustBeForTheSameHull() {
        assertThrows(IllegalArgumentException.class, () -> WakeMesh.build(run(0.4, 10), 10, P, COLUMNS, WakeTable.of(0.9)));
    }

    @Test
    void columnsMustBeOddAndAtLeastThree() {
        assertThrows(IllegalArgumentException.class, () -> WakeMesh.build(run(0.4, 10), 10, P, 2, T));
        assertThrows(IllegalArgumentException.class, () -> WakeMesh.build(run(0.4, 10), 10, P, 4, T));
        assertTrue(WakeMesh.build(run(0.4, 10), 10, P, 3, T).quads() > 0);
    }

    @Test
    void theGridHasARowPerYoungSampleAndSomeAheadEveryRowTheSameLength() {
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 20), 20, P, COLUMNS, T);
        assertTrue(mesh.rows().size() > 21, "rows ahead of the bow too, had " + mesh.rows().size());
        for (List<WakeMesh.Vertex> row : mesh.rows()) {
            assertEquals(COLUMNS, row.size());
        }
        assertEquals((mesh.rows().size() - 1) * (COLUMNS - 1), mesh.quads());
        // Bow first: the first behind-the-bow row is the newest sample, at x = 8.
        WakeMesh.Vertex bowMiddle = mesh.rows().get(mesh.rows().size() - 21).get(COLUMNS / 2);
        assertEquals(8.0, bowMiddle.x(), 1e-9);
        assertEquals(0.0, bowMiddle.z(), 1e-9, "the middle column is on the track");
        // Rows ahead are ahead.
        assertTrue(mesh.rows().get(0).get(COLUMNS / 2).x() > 8.0);
    }

    @Test
    void oldRowsThinOutButTheOldestAndNewestStay() {
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 90), 90, P, COLUMNS, T);
        int behind = mesh.rows().size() - 5;
        // 25 young rows, then every second of the next 36 ticks, then every third of the last 30, and the oldest.
        assertTrue(behind < 91 && behind > 50, "thinned, had " + behind + " rows behind the bow");
        assertEquals(36.0, mesh.rows().get(5).get(COLUMNS / 2).x(), 1e-9, "the newest sample is the bow row");
        assertEquals(0.0, mesh.rows().get(mesh.rows().size() - 1).get(COLUMNS / 2).x(), 1e-9, "the oldest is the last row");
        // Young rows are every sample: consecutive x a sample apart.
        for (int r = 5; r < 5 + WakeMesh.EVERY_SAMPLE_UNTIL; r++) {
            assertEquals(0.4, mesh.rows().get(r).get(COLUMNS / 2).x() - mesh.rows().get(r + 1).get(COLUMNS / 2).x(), 1e-9);
        }
    }

    @Test
    void theBowStandsUpAndTheWaterBehindRisesAndDipsAroundItsLift() {
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 60), 60, P, COLUMNS, T);
        double top = Double.NEGATIVE_INFINITY;
        double bottom = Double.POSITIVE_INFINITY;
        for (List<WakeMesh.Vertex> row : mesh.rows()) {
            for (WakeMesh.Vertex v : row) {
                top = Math.max(top, v.y());
                bottom = Math.min(bottom, v.y());
            }
        }
        assertTrue(top > 63.0 + P.lift() + 0.1, "a bow wave stands up, top was " + top);
        assertTrue(bottom < 63.0 + P.lift() - 0.03, "a trough dips, bottom was " + bottom);
    }

    @Test
    void normalsAreUnitUprightOnFlatWaterAndLeaningOnASlope() {
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 60), 60, P, COLUMNS, T);
        double mostLean = 0.0;
        for (List<WakeMesh.Vertex> row : mesh.rows()) {
            for (WakeMesh.Vertex v : row) {
                double len = Math.sqrt(v.nx() * v.nx() + v.ny() * v.ny() + v.nz() * v.nz());
                assertEquals(1.0, len, 1e-6);
                assertTrue(v.ny() > 0.0, "normals point up");
                mostLean = Math.max(mostLean, 1.0 - v.ny());
            }
        }
        assertTrue(mostLean > 0.02, "somewhere the surface leans, most was " + mostLean);
        // The outermost vertex of a far row is outside the V on flat water: upright.
        WakeMesh.Vertex outer = mesh.rows().get(mesh.rows().size() - 1).get(0);
        assertEquals(1.0, outer.ny(), 1e-5);
    }

    @Test
    void skinAndFoamLiveInsideTheVAndFadeWithAge() {
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 80), 80, P, COLUMNS, T);
        int bowRow = 5;
        List<WakeMesh.Vertex> bow = mesh.rows().get(bowRow);
        List<WakeMesh.Vertex> old = mesh.rows().get(mesh.rows().size() - 1);
        assertEquals(0.0f, bow.get(0).skin(), "outside the V");
        assertEquals(0.0f, bow.get(0).foam());
        assertTrue(bow.get(COLUMNS / 2).skin() > 0.9f, "full skin on the track at the bow");
        assertTrue(mesh.rows().get(bowRow + 2).get(COLUMNS / 2).foam() > 0.8f, "churn behind the stern");
        assertTrue(old.get(COLUMNS / 2).skin() < bow.get(COLUMNS / 2).skin() * 0.4f, "the oldest row has faded");
        assertEquals(0.0f, mesh.rows().get(0).get(COLUMNS / 2).skin(), 1e-6f, "nothing at the V's point ahead of the bow");
        assertEquals(0.0f, mesh.rows().get(0).get(COLUMNS / 2).foam(), 1e-6f);
    }

    @Test
    void theEdgeCoordinateIsZeroOnTheEdgeAndClampedWellInside() {
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 80), 80, P, COLUMNS, T);
        List<WakeMesh.Vertex> far = mesh.rows().get(mesh.rows().size() - 1);
        assertEquals((float) WakeMesh.EDGE_INSIDE, far.get(COLUMNS / 2).edge(), "the track, well inside, is clamped");
        assertTrue(far.get(0).edge() > 0.0f, "the outermost column is outside");
        // Somewhere between, the coordinate crosses zero: a line drawn at zero lands on the V's edge.
        boolean crosses = false;
        for (int j = 0; j < COLUMNS / 2; j++) {
            if (far.get(j).edge() > 0.0f && far.get(j + 1).edge() <= 0.0f) {
                crosses = true;
            }
        }
        assertTrue(crosses);
    }

    @Test
    void chevronLinesAreHiddenUntilTheChevronsStartAndTheFoamIsPinnedToTheTick() {
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 80), 80, P, COLUMNS, T);
        int bowRow = 5;
        assertEquals(0.0f, mesh.rows().get(bowRow).get(COLUMNS / 2).lines());
        assertEquals(0.0f, mesh.rows().get(0).get(COLUMNS / 2).lines(), "none ahead of the bow");
        assertTrue(mesh.rows().get(bowRow + 10).get(COLUMNS / 2).lines() > 0.5f, "lines a few blocks back");
        // The phase runs on continuously, row to row, so no quad spans a jump in it.
        for (int r = 1; r < mesh.rows().size(); r++) {
            float step = mesh.rows().get(r).get(COLUMNS / 2).chevron() - mesh.rows().get(r - 1).get(COLUMNS / 2).chevron();
            assertTrue(Math.abs(step) < 0.5f, "phase steps by a fraction of a wavelength per row, was " + step);
        }
        WakeMesh.Vertex far = mesh.rows().get(mesh.rows().size() - 1).get(COLUMNS / 2);
        assertTrue(far.chevron() > 1.0f, "phase far back, was " + far.chevron());
        assertEquals((float) (0 / P.textureTicks()), far.foamV(), "the oldest sample is tick 0");
        assertEquals((float) (80 / P.textureTicks()), mesh.rows().get(5).get(COLUMNS / 2).foamV());
    }

    @Test
    void aCurvedTrackBendsTheGrid() {
        List<Sample> arc = new ArrayList<>();
        for (int t = 0; t <= 40; t++) {
            double a = t * 0.05;
            arc.add(new Sample(10.0 * Math.sin(a), 63.0, 10.0 - 10.0 * Math.cos(a), t));
        }
        WakeMesh.Mesh mesh = WakeMesh.build(arc, 40, P, COLUMNS, T);
        List<WakeMesh.Vertex> first = mesh.rows().get(5);
        List<WakeMesh.Vertex> last = mesh.rows().get(mesh.rows().size() - 1);
        // A row runs across its track: the bow's, heading north-west by now, lies mostly east-west;
        // the oldest, where the track ran east, lies north-south.
        double firstAngle = Math.atan2(first.get(COLUMNS - 1).z() - first.get(0).z(), first.get(COLUMNS - 1).x() - first.get(0).x());
        double lastAngle = Math.atan2(last.get(COLUMNS - 1).z() - last.get(0).z(), last.get(COLUMNS - 1).x() - last.get(0).x());
        double between = Math.abs(Math.atan2(Math.sin(firstAngle - lastAngle), Math.cos(firstAngle - lastAngle)));
        assertTrue(between > 1.5, "rows turn with the track: " + between + " rad apart");
    }

    @Test
    void theShadeIsOneOnFlatWaterBrighterTowardTheLightAndDarkerAway() {
        assertEquals(1.0f, WakeMesh.shade(0.0, 1.0, 0.0));
        float toward = WakeMesh.shade(-0.3, 0.9, 0.3);
        float away = WakeMesh.shade(0.3, 0.9, -0.3);
        assertTrue(toward > 1.0f && toward <= WakeMesh.SHADE_MAX, "toward the light, was " + toward);
        assertTrue(away < 1.0f && away >= WakeMesh.SHADE_MIN, "away from it, was " + away);
        double len = Math.sqrt(0.45 * 0.45 + 0.8 * 0.8 + 0.4 * 0.4);
        assertEquals(WakeMesh.SHADE_MAX, WakeMesh.shade(-0.45 / len, 0.8 / len, 0.4 / len), "facing the light square on: clamped at the brightest");
        assertEquals(WakeMesh.SHADE_MIN, WakeMesh.shade(0.7, 0.2, -0.7), "clamped at the darkest");
        // Every vertex of a real mesh carries it.
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 40), 40, P, COLUMNS, T);
        boolean varied = false;
        for (List<WakeMesh.Vertex> row : mesh.rows()) {
            for (WakeMesh.Vertex v : row) {
                assertTrue(v.shade() >= WakeMesh.SHADE_MIN && v.shade() <= WakeMesh.SHADE_MAX);
                varied |= v.shade() != 1.0f;
            }
        }
        assertTrue(varied, "the relief is shaded somewhere");
    }

    @Test
    void theInsideOfATightTurnStopsShortSoRowsDoNotFold() {
        // A hard turn to port (counter-clockwise seen from above, heading east then north) of radius 3.
        List<Sample> arc = new ArrayList<>();
        for (int t = 0; t <= 40; t++) {
            double a = t * 0.12;
            arc.add(new Sample(3.0 * Math.sin(a), 63.0, 3.0 - 3.0 * Math.cos(a), t));
        }
        // ... and one to starboard.
        List<Sample> other = new ArrayList<>();
        for (int t = 0; t <= 40; t++) {
            double a = t * 0.12;
            other.add(new Sample(3.0 * Math.sin(a), 63.0, -3.0 + 3.0 * Math.cos(a), t));
        }
        WakeMesh.Mesh mesh = WakeMesh.build(arc, 40, P, COLUMNS, T);
        WakeMesh.Mesh mirror = WakeMesh.build(other, 40, P, COLUMNS, T);
        // Mid-way back the V is a few blocks wide; the inside is held to under the radius, the outside not.
        List<WakeMesh.Vertex> row = mesh.rows().get(mesh.rows().size() - 12);
        List<WakeMesh.Vertex> mirrorRow = mirror.rows().get(mirror.rows().size() - 12);
        WakeMesh.Vertex middle = row.get(COLUMNS / 2);
        double first = Math.hypot(row.get(0).x() - middle.x(), row.get(0).z() - middle.z());
        double last = Math.hypot(row.get(COLUMNS - 1).x() - middle.x(), row.get(COLUMNS - 1).z() - middle.z());
        assertTrue(Math.min(first, last) < 3.0, "the inside stops short of the turn's centre: " + first + " / " + last);
        assertTrue(Math.max(first, last) > 3.0, "the outside reaches the V's edge: " + first + " / " + last);
        WakeMesh.Vertex mirrorMiddle = mirrorRow.get(COLUMNS / 2);
        double mirrorFirst = Math.hypot(mirrorRow.get(0).x() - mirrorMiddle.x(), mirrorRow.get(0).z() - mirrorMiddle.z());
        double mirrorLast = Math.hypot(mirrorRow.get(COLUMNS - 1).x() - mirrorMiddle.x(), mirrorRow.get(COLUMNS - 1).z() - mirrorMiddle.z());
        assertEquals(first, mirrorLast, 1e-6, "the other way round, the other side is the inside");
        assertEquals(last, mirrorFirst, 1e-6);
    }

    @Test
    void aTrailAtRestMakesNoMesh() {
        assertEquals(0, WakeMesh.build(run(0.01, 20), 20, P, COLUMNS, T).quads());
    }
}
