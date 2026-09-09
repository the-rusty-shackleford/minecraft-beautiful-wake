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
 * bow, behind, one per sample; every row the same length; the middle
 * column on the track. Heights: the bow's hump
 * above the water, ridges and dips behind, nothing at rest. Normals: unit,
 * upright on flat water, leaning on a slope. Skin and foam: inside the V,
 * none outside, fading with age. Mappings: the edge coordinate zero on the
 * V's edge and clamped inside, the chevron lines hidden before the
 * chevrons start and the phase continuous and pinned to the water, the
 * foam pinned to the tick. A curved track bends the
 * grid; a tight turn holds the inside short. Shade: flat, toward, away,
 * clamped, on a mesh. Size: fades sooner, stands lower, as wide at the
 * hull. Smoothness: over a steady run, no row over a sample vanishes and
 * returns, and positions and alphas move evenly frame to frame. A trail
 * at rest makes no mesh.
 */
final class WakeMeshTest {
    private static final WakeParams P = new WakeParams(1.4, 90, 0.075, 0.35, 20.0, 0.015);
    private static final WakeTable T = WakeTable.of(1.4, WakeField.HULL_NOSE);
    private static final int COLUMNS = 25;

    /** A straight run east at {@code speed} for {@code ticks}, the newest sample at tick {@code ticks}, the arc as a trail would set it. */
    private static List<Sample> run(double speed, int ticks) {
        List<Sample> samples = new ArrayList<>();
        for (int t = 0; t <= ticks; t++) {
            samples.add(new Sample(t * speed, 63.0, 0.0, t, t * speed));
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
    void theTableMustBeForTheSameHullAndNose() {
        assertThrows(IllegalArgumentException.class, () -> WakeMesh.build(run(0.4, 10), 10, P, COLUMNS, WakeTable.of(0.9, WakeField.HULL_NOSE)));
        assertThrows(IllegalArgumentException.class, () -> WakeMesh.build(run(0.4, 10), 10, P, COLUMNS, WakeTable.of(1.4, WakeField.SWIMMER_NOSE)));
    }

    @Test
    void columnsMustBeOddAndAtLeastThree() {
        assertThrows(IllegalArgumentException.class, () -> WakeMesh.build(run(0.4, 10), 10, P, 2, T));
        assertThrows(IllegalArgumentException.class, () -> WakeMesh.build(run(0.4, 10), 10, P, 4, T));
        assertTrue(WakeMesh.build(run(0.4, 10), 10, P, 3, T).quads() > 0);
    }

    @Test
    void theGridHasARowPerSampleAndSomeAheadEveryRowTheSameLength() {
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
    void everySampleIsARowTheNewestFirstBehindTheNoseAndTheOldestLast() {
        WakeMesh.Mesh mesh = WakeMesh.build(run(0.4, 90), 90, P, COLUMNS, T);
        assertEquals(91 + 5, mesh.rows().size(), "a row per sample and five ahead");
        assertEquals(36.0, mesh.rows().get(5).get(COLUMNS / 2).x(), 1e-9, "the newest sample is the bow row");
        assertEquals(0.0, mesh.rows().get(mesh.rows().size() - 1).get(COLUMNS / 2).x(), 1e-9, "the oldest is the last row");
        for (int r = 5; r + 1 < mesh.rows().size(); r++) {
            assertEquals(0.4, mesh.rows().get(r).get(COLUMNS / 2).x() - mesh.rows().get(r + 1).get(COLUMNS / 2).x(), 1e-9);
        }
    }

    @Test
    void theBowStandsUpAndNothingDrawsBelowTheLiftThoughTheTroughShadesItself() {
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
        assertTrue(bottom >= 63.0 + P.lift() && bottom < 63.0 + P.lift() + 0.021, "nothing below the still level and the lift, and the deepest trough is level: " + (bottom - 63.0 - P.lift()));
        assertEquals(0.0, WakeMesh.aboveLevel(-1.0), 1e-3);
        assertEquals(1.0, WakeMesh.aboveLevel(1.0), 1e-3);
        assertTrue(WakeMesh.aboveLevel(0.0) > 0.0 && WakeMesh.aboveLevel(0.0) < 0.021, "a small lift at the knee");
        // The trough is still there in the normals: somewhere level, the surface leans.
        boolean leaningWhileLevel = false;
        for (List<WakeMesh.Vertex> row : mesh.rows()) {
            for (WakeMesh.Vertex v : row) {
                if (Math.abs(v.y() - (63.0 + P.lift())) < 0.021 && v.ny() < 0.999) {
                    leaningWhileLevel = true;
                }
            }
        }
        assertTrue(leaningWhileLevel, "the trough shows in the shading");
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
        assertEquals(0.0f, mesh.rows().get(0).get(COLUMNS / 2).skin(), 1e-6f, "nothing at the nose's tip ahead of the bow");
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
        WakeMesh.Vertex bow = mesh.rows().get(5).get(COLUMNS / 2);
        assertEquals(0.0f, far.chevron(), 1e-6f, "the oldest sample began the track: phase zero");
        assertTrue(bow.chevron() < -1.0f, "the hull is many wavelengths along, was " + bow.chevron());
        assertEquals((float) (0 / P.textureTicks()), far.foamV(), "the oldest sample is tick 0");
        assertEquals((float) (80 / P.textureTicks()), mesh.rows().get(5).get(COLUMNS / 2).foamV());
    }

    @Test
    void theChevronsStayWhereTheWaterMadeThemAsTheHullMovesOn() {
        // The row over the sample at tick 40 keeps its phase whether the hull is at tick 60 or tick 90.
        WakeMesh.Mesh early = WakeMesh.build(run(0.4, 60), 60, P, COLUMNS, T);
        WakeMesh.Mesh late = WakeMesh.build(run(0.4, 90), 90, P, COLUMNS, T);
        WakeMesh.Vertex earlyRow = rowOver(early, 40 * 0.4).get(COLUMNS / 2);
        WakeMesh.Vertex lateRow = rowOver(late, 40 * 0.4).get(COLUMNS / 2);
        assertEquals(earlyRow.chevron(), lateRow.chevron(), 1e-6f, "the phase is the water's, not the hull's");
        // ... and the phase runs on at the chevrons' rate along the track.
        WakeMesh.Vertex next = rowOver(early, 41 * 0.4).get(COLUMNS / 2);
        assertEquals(0.4 * WakeField.chevronPhasePerBlockBack(), earlyRow.chevron() - next.chevron(), 1e-5, "a sample further along is a step lower in phase");
    }

    /** The row whose middle vertex is at x = {@code x}. */
    private static List<WakeMesh.Vertex> rowOver(WakeMesh.Mesh mesh, double x) {
        for (List<WakeMesh.Vertex> row : mesh.rows()) {
            if (Math.abs(row.get(COLUMNS / 2).x() - x) < 1e-9) {
                return row;
            }
        }
        throw new AssertionError("no row over x=" + x);
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
    void aSmallerSizeFadesTheSheetSoonerAndStandsLower() {
        WakeParams small = new WakeParams(1.4, 90, 0.075, 0.35, 20.0, 0.015, 1.0, 0.0, 1.0, 0.5, WakeField.HULL_NOSE, 0.0, 1.0);
        WakeMesh.Mesh full = WakeMesh.build(run(0.4, 80), 80, P, COLUMNS, T);
        WakeMesh.Mesh half = WakeMesh.build(run(0.4, 80), 80, small, COLUMNS, T);
        int far = full.rows().size() - 10;
        WakeMesh.Vertex fullFar = full.rows().get(far).get(COLUMNS / 2);
        WakeMesh.Vertex halfFar = half.rows().get(far).get(COLUMNS / 2);
        assertTrue(halfFar.skin() < fullFar.skin() * 0.8f, "fainter far back: " + halfFar.skin() + " vs " + fullFar.skin());
        assertTrue(halfFar.lines() < fullFar.lines() * 0.8f, "lines too");
        double fullTop = full.rows().stream().flatMap(List::stream).mapToDouble(WakeMesh.Vertex::y).max().orElseThrow() - 63.0 - P.lift();
        double halfTop = half.rows().stream().flatMap(List::stream).mapToDouble(WakeMesh.Vertex::y).max().orElseThrow() - 63.0 - P.lift();
        assertEquals(fullTop / 2.0, halfTop, 0.003, "half as tall");
        // At the hull the sheet is as wide either way: the outline is the hull's.
        assertEquals(full.rows().get(5).get(0).x(), half.rows().get(5).get(0).x(), 1e-9);
        assertEquals(full.rows().get(5).get(0).z(), half.rows().get(5).get(0).z(), 1e-9);
    }

    /**
     * The mesh a client would draw at {@code now} for a boat that has run
     * east at {@code speed} since tick 0: a sample per whole tick, and the
     * boat's interpolated position this frame on the end, as the renderer
     * adds it.
     */
    private static WakeMesh.Mesh frame(double speed, double now) {
        int tick = (int) Math.floor(now);
        List<Sample> samples = new ArrayList<>();
        for (int t = Math.max(0, tick - P.lifeTicks()); t <= tick; t++) {   // pruned as a trail prunes
            samples.add(new Sample(t * speed, 63.0, 0.0, t, t * speed));
        }
        double x = now * speed;
        samples.add(new Sample(x, 63.0, 0.0, tick + 1, x));
        return WakeMesh.build(samples, now, P, COLUMNS, T);
    }

    /** The rows of {@code mesh} that lie on whole-tick samples, keyed by the sample's x: the ahead rows and the head are left out. */
    private static java.util.Map<Long, List<WakeMesh.Vertex>> sampleRows(WakeMesh.Mesh mesh, double speed, double now) {
        java.util.Map<Long, List<WakeMesh.Vertex>> rows = new java.util.HashMap<>();
        for (List<WakeMesh.Vertex> row : mesh.rows()) {
            double x = row.get(COLUMNS / 2).x();
            double tick = x / speed;
            if (Math.abs(tick - Math.rint(tick)) < 1e-6 && tick <= Math.floor(now) + 1e-9) {
                rows.put(Math.round(tick), row);
            }
        }
        return rows;
    }

    @Test
    void aSteadyRunIsSmoothFrameToFrame() {
        // Frames a quarter tick apart over four ticks of a boat at speed. Between
        // one frame and the next, no row over a sample may vanish and come back,
        // and every vertex over a sample must move evenly -- the same step each
        // frame -- in position and in alpha: a flip-flop is a second difference.
        double speed = 0.4;
        List<WakeMesh.Mesh> frames = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        for (int k = 0; k <= 16; k++) {
            double now = 100.0 + k * 0.25;
            frames.add(frame(speed, now));
            times.add(now);
        }
        // A row over a sample, once drawn, is drawn in every following frame until it is pruned for good.
        java.util.Map<Long, List<Integer>> presence = new java.util.TreeMap<>();
        for (int k = 0; k < frames.size(); k++) {
            for (Long tick : sampleRows(frames.get(k), speed, times.get(k)).keySet()) {
                presence.computeIfAbsent(tick, key -> new ArrayList<>()).add(k);
            }
        }
        for (var entry : presence.entrySet()) {
            List<Integer> at = entry.getValue();
            assertEquals(at.get(at.size() - 1) - at.get(0) + 1, at.size(),
                    "the row over tick " + entry.getKey() + " comes and goes: drawn at frames " + at);
        }
        double worstPosition = 0.0;
        double worstAlpha = 0.0;
        String where = "";
        for (int k = 1; k + 1 < frames.size(); k++) {
            var before = sampleRows(frames.get(k - 1), speed, times.get(k - 1));
            var at = sampleRows(frames.get(k), speed, times.get(k));
            var after = sampleRows(frames.get(k + 1), speed, times.get(k + 1));
            for (Long tick : before.keySet()) {
                if (!after.containsKey(tick)) {
                    continue;   // pruned by age between the frames: allowed
                }
                assertTrue(at.containsKey(tick), "the row over tick " + tick + " vanished at frame " + k + " and came back");
                for (int j = 0; j < COLUMNS; j++) {
                    WakeMesh.Vertex a = before.get(tick).get(j);
                    WakeMesh.Vertex b = at.get(tick).get(j);
                    WakeMesh.Vertex c = after.get(tick).get(j);
                    double position = Math.hypot(Math.hypot(c.x() - 2 * b.x() + a.x(), c.z() - 2 * b.z() + a.z()), c.y() - 2 * b.y() + a.y());
                    double alpha = Math.max(Math.abs(c.skin() - 2 * b.skin() + a.skin()),
                            Math.max(Math.abs(c.lines() - 2 * b.lines() + a.lines()), Math.abs(c.foam() - 2 * b.foam() + a.foam())));
                    if (position > worstPosition) {
                        worstPosition = position;
                        where = "position at tick " + tick + " column " + j + " frame " + k;
                    }
                    if (alpha > worstAlpha) {
                        worstAlpha = alpha;
                    }
                }
            }
        }
        // The bow wave passing over a row is motion, and a hull's length of it is a few hundredths a frame; a flip-flop is more.
        assertTrue(worstPosition < 0.012, "vertices move evenly frame to frame; worst second difference " + worstPosition + " (" + where + ")");
        // The churn forms behind the stern over a couple of ticks: a steep but smooth onset.
        assertTrue(worstAlpha < 0.1, "alphas change evenly frame to frame; worst second difference " + worstAlpha);
        // And nothing flickers: over the run a vertex's height or alpha may turn round as a wave passes, not keep turning.
        int worstReversals = 0;
        String flicker = "";
        for (Long tick : presence.keySet()) {
            for (int j = 0; j < COLUMNS; j++) {
                for (int q = 0; q < 4; q++) {
                    int reversals = 0;
                    double previousStep = 0.0;
                    Double previous = null;
                    double floor = q == 0 ? 0.003 : 0.01;   // below a few millimetres, or a hundredth of alpha, nothing shows
                    for (int k = 0; k < frames.size(); k++) {
                        var rows = sampleRows(frames.get(k), speed, times.get(k));
                        if (!rows.containsKey(tick)) {
                            continue;
                        }
                        WakeMesh.Vertex v = rows.get(tick).get(j);
                        double value = q == 0 ? v.y() : q == 1 ? v.skin() : q == 2 ? v.lines() : v.foam();
                        if (previous != null) {
                            double step = value - previous;
                            if (Math.abs(step) > floor && previousStep != 0.0 && Math.signum(step) != Math.signum(previousStep)) {
                                reversals++;
                            }
                            if (Math.abs(step) > floor) {
                                previousStep = step;
                            }
                        }
                        previous = value;
                    }
                    if (reversals > worstReversals) {
                        worstReversals = reversals;
                        flicker = (q == 0 ? "height" : q == 1 ? "skin" : q == 2 ? "lines" : "foam") + " at tick " + tick + " column " + j;
                    }
                }
            }
        }
        assertTrue(worstReversals <= 2, "nothing flickers: most direction reversals " + worstReversals + " (" + flicker + ")");
    }

    @Test
    void aTrailAtRestMakesNoMesh() {
        assertEquals(0, WakeMesh.build(run(0.01, 20), 20, P, COLUMNS, T).quads());
    }
}
