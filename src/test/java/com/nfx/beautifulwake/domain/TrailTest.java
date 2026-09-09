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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Partitions. Samples: none, one, several; a sample not newer than the last
 * (dropped); pruning by age at add and on demand. Speed: fewer than two
 * samples, two a tick apart, two several ticks apart. Heading: no samples,
 * one, a move, a move then a stop (the last move's heading survives).
 */
final class TrailTest {

    @Test
    void theArcRunsOnFromTheLastSampleKeptAndStartsAfreshAfterATeleport() {
        Trail trail = new Trail(100);
        trail.add(new Sample(0, 63, 0, 1, 99.0));   // the caller's arc is ignored
        trail.add(new Sample(1.2, 63, 1.6, 2));       // two blocks on
        trail.add(new Sample(1.2, 63, 2.6, 3));       // and one more
        List<Sample> s = trail.samples();
        assertEquals(0.0, s.get(0).arc(), 1e-9);
        assertEquals(2.0, s.get(1).arc(), 1e-9);
        assertEquals(3.0, s.get(2).arc(), 1e-9);
        trail.add(new Sample(500, 63, 5, 4));        // teleported: a fresh trail
        assertEquals(1, trail.samples().size());
        assertEquals(0.0, trail.samples().get(0).arc(), 1e-9);
    }

    private static Sample at(double x, double z, long tick) {
        return new Sample(x, 62.0, z, tick);
    }

    @Test
    void aTrailNeedsALife() {
        assertThrows(IllegalArgumentException.class, () -> new Trail(0));
        assertEquals(80, new Trail(80).lifeTicks());
    }

    @Test
    void samplesAreKeptOldestFirstAndForgottenPastTheirLife() {
        Trail trail = new Trail(10);
        trail.add(at(0, 0, 100));
        trail.add(at(1, 0, 105));
        trail.add(at(2, 0, 111));                       // 11 ticks after the first: the first goes
        assertEquals(List.of(at(1, 0, 105).atArc(1.0), at(2, 0, 111).atArc(2.0)), trail.samples());
        assertEquals(Optional.of(at(2, 0, 111).atArc(2.0)), trail.latest());
        trail.prune(130);
        assertTrue(trail.isEmpty());
        assertEquals(Optional.empty(), trail.latest());
    }

    @Test
    void aJumpNoBoatCouldMakeStartsTheTrailAfresh() {
        Trail trail = new Trail(80);
        trail.add(at(0, 0, 1));
        trail.add(at(0.4, 0, 2));
        trail.add(at(40, 0, 3));                        // forty blocks in a tick: a teleport
        assertEquals(List.of(at(40, 0, 3)), trail.samples());
        trail.add(at(41, 0, 13));                       // ten blocks in ten ticks: fast, but a passage
        assertEquals(2, trail.samples().size());
    }

    @Test
    void aSampleNoNewerThanTheLastIsIgnored() {
        Trail trail = new Trail(80);
        trail.add(at(0, 0, 100));
        trail.add(at(5, 0, 100));
        trail.add(at(9, 0, 99));
        assertEquals(List.of(at(0, 0, 100)), trail.samples());
    }

    @Test
    void speedIsBlocksPerTickOverTheLastFewTicksOfSamples() {
        Trail trail = new Trail(80);
        assertEquals(0.0, trail.speed());
        trail.add(at(0, 0, 100));
        assertEquals(0.0, trail.speed());
        trail.add(at(0.3, 0.4, 101));
        assertEquals(0.5, trail.speed(), 1e-9);
        trail.add(at(0.3, 0.4, 111));                   // ten ticks, no move: the window holds only the last two
        assertEquals(0.0, trail.speed(), 1e-9);
        trail.add(at(2.3, 0.4, 121));                   // two blocks in ten ticks
        assertEquals(0.2, trail.speed(), 1e-9);
    }

    @Test
    void speedAveragesOverTheWindowSoAStepwiseMoveReadsSmooth() {
        // A remote boat moves in steps: 0.8 blocks every other tick, still between.
        Trail trail = new Trail(80);
        for (int t = 0; t < 12; t++) {
            trail.add(at((t / 2) * 0.8, 0, t));
        }
        // The last two samples (ticks 10 and 11) are the same place, but the
        // window (six ticks back, to tick 5) sees 2.4 blocks... in 6 ticks.
        assertEquals(0.4, trail.speed(), 0.05);
    }

    @Test
    void headingIsTheLastMoveAndEastBeforeAnyMove() {
        Trail trail = new Trail(80);
        assertEquals(1.0, trail.heading()[0], 1e-9);
        trail.add(at(0, 0, 1));
        assertEquals(1.0, trail.heading()[0], 1e-9);
        trail.add(at(0, -3, 2));                        // north
        assertEquals(0.0, trail.heading()[0], 1e-9);
        assertEquals(-1.0, trail.heading()[1], 1e-9);
        trail.add(at(0, -3, 3));                        // stopped: the last move still says north
        assertEquals(-1.0, trail.heading()[1], 1e-9);
    }
}
