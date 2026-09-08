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
 * AF: AF(x, surfaceY, z, tick) = "at game tick {@code tick} the hull was
 * over {@code (x, z)} and the water there stood at height {@code surfaceY}".
 *
 * @param x        east-west position
 * @param surfaceY the height of the water surface under the hull
 * @param z        north-south position
 * @param tick     the game tick
 */
public record Sample(double x, double surfaceY, double z, long tick) {
    public Sample {
        if (!Double.isFinite(x) || !Double.isFinite(surfaceY) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("a sample's coordinates must be finite");
        }
    }

    /** effects: returns the horizontal distance to {@code other} */
    public double distanceTo(Sample other) {
        double dx = other.x - x;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
