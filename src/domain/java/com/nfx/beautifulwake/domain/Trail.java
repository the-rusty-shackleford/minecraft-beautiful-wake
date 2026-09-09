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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Where one boat has been lately: its samples, newest last, no older than
 * the wake lives.
 *
 * <p>Mutable, one per boat, owned by whoever tracks the boat. Two things
 * are read off it: the boat's speed, from its last two samples -- measured
 * from where the boat actually was, so a boat any mod moves any way makes
 * a wake -- and the run of samples the wake is drawn along.
 *
 * <p>RI: samples are in strictly increasing tick order; {@code lifeTicks >= 1};
 * no sample is older than {@code lifeTicks} before the newest, once pruned.<br>
 * AF: AF(samples) = "the boat passed through these points at these ticks".
 */
public final class Trail {

    private final int lifeTicks;
    private final Deque<Sample> samples = new ArrayDeque<>();

    /**
     * @param lifeTicks how long a sample stays: the wake's life
     * @throws IllegalArgumentException if {@code lifeTicks < 1}
     */
    public Trail(int lifeTicks) {
        if (lifeTicks < 1) {
            throw new IllegalArgumentException("lifeTicks must be >= 1, was " + lifeTicks);
        }
        this.lifeTicks = lifeTicks;
    }

    public int lifeTicks() {
        return lifeTicks;
    }

    /**
     * effects: appends {@code sample} and forgets every sample older than the
     * wake's life before it; a sample no newer than the last is ignored (the
     * boat was sampled twice in one tick, or the clock went backwards); a
     * sample farther from the last than {@link #MAX_SPEED} blocks a tick
     * could carry it starts the trail afresh -- the boat was teleported, and
     * no wake joins where it was to where it is. The kept sample's arc is
     * the last one's plus the distance between them, whatever the caller
     * set; a fresh trail starts at zero.
     */
    public void add(Sample sample) {
        Sample last = samples.peekLast();
        if (last != null && sample.tick() <= last.tick()) {
            return;
        }
        if (last != null && sample.distanceTo(last) > MAX_SPEED * (sample.tick() - last.tick())) {
            samples.clear();
            last = null;
        }
        // The arc runs on from the last sample kept: the caller's is ignored.
        samples.addLast(sample.atArc(last == null ? 0.0 : last.arc() + sample.distanceTo(last)));
        prune(sample.tick());
    }

    /** effects: forgets every sample older than the wake's life before {@code now} */
    public void prune(long now) {
        while (!samples.isEmpty() && now - samples.peekFirst().tick() > lifeTicks) {
            samples.pollFirst();
        }
    }

    /** effects: returns the samples, oldest first; never null */
    public List<Sample> samples() {
        return new ArrayList<>(samples);
    }

    /** effects: returns the newest sample, if any */
    public Optional<Sample> latest() {
        return Optional.ofNullable(samples.peekLast());
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    /** Over how many ticks the speed is measured: a client sees a boat's position in steps, not per tick. */
    public static final int SPEED_WINDOW = 6;
    /** Faster than this, in blocks per tick, a move is a teleport, not a passage through the water. */
    public static final double MAX_SPEED = 3.0;

    /**
     * effects: returns the boat's speed in blocks per tick over the last
     * {@link #SPEED_WINDOW} ticks of samples -- from the newest sample back
     * to the oldest within the window, or the oldest of all with fewer --
     * so the steps a client sees a remote boat move in average out; zero
     * with fewer than two samples
     */
    public double speed() {
        if (samples.size() < 2) {
            return 0.0;
        }
        Sample last = samples.peekLast();
        Sample from = null;      // the oldest sample within the window
        Sample before = null;    // the sample just before the last
        for (Sample s : samples) {
            if (s == last) {
                break;
            }
            before = s;
            if (from == null && last.tick() - s.tick() <= SPEED_WINDOW) {
                from = s;
            }
        }
        if (from == null) {
            from = before;
        }
        return from == null ? 0.0 : last.distanceTo(from) / (last.tick() - from.tick());
    }

    /**
     * effects: returns the direction of travel as a unit vector {@code (dx, dz)}
     * over the last two samples that are apart; east if the boat has not
     * moved
     */
    public double[] heading() {
        Sample last = samples.peekLast();
        if (last == null) {
            return new double[] {1.0, 0.0};
        }
        Sample[] all = samples.toArray(new Sample[0]);
        for (int i = all.length - 2; i >= 0; i--) {
            double dx = last.x() - all[i].x();
            double dz = last.z() - all[i].z();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 1e-6) {
                return new double[] {dx / length, dz / length};
            }
        }
        return new double[] {1.0, 0.0};
    }
}
