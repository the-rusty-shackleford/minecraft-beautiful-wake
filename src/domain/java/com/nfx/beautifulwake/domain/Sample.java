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

/**
 * Where a boat was at one tick: its position, the water surface there, and
 * the tick.
 *
 * <p>RI: the coordinates are finite.<br>
 * AF: AF(x, surfaceY, z, tick, arc) = "at game tick {@code tick} the hull was
 * over {@code (x, z)}, {@code arc} blocks along its track, and the water there
 * stood at height {@code surfaceY}".
 *
 * @param x        east-west position
 * @param surfaceY the height of the water surface under the hull
 * @param z        north-south position
 * @param tick     the game tick
 * @param arc      how far along the track the hull had come, in blocks, since its trail began:
 *                 fixed for good once sampled, so a pattern laid down by it stays where the water is
 */
public record Sample(double x, double surfaceY, double z, long tick, double arc) {
    public Sample {
        if (!Double.isFinite(x) || !Double.isFinite(surfaceY) || !Double.isFinite(z) || !Double.isFinite(arc)) {
            throw new IllegalArgumentException("a sample's coordinates must be finite");
        }
    }

    /** A sample at the start of a track: arc zero. {@link Trail#add} sets the arc of what it keeps. */
    public Sample(double x, double surfaceY, double z, long tick) {
        this(x, surfaceY, z, tick, 0.0);
    }

    /** effects: returns this sample with its arc set to {@code arc} */
    public Sample atArc(double arc) {
        return new Sample(x, surfaceY, z, tick, arc);
    }

    /** effects: returns the horizontal distance to {@code other} */
    public double distanceTo(Sample other) {
        double dx = other.x - x;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
