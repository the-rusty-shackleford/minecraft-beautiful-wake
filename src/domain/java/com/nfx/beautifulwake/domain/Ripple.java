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
 * One splash on the water: where, how strong, and when it happened.
 *
 * <p>RI: the coordinates are finite; {@code strength} in {@code [0, Splash.MAX_STRENGTH]}.<br>
 * AF: AF(x, surfaceY, z, strength, born) = "something of splash strength
 * {@code strength} went into the water at {@code (x, z)}, surface at
 * {@code surfaceY}, at tick {@code born}".
 */
public record Ripple(double x, double surfaceY, double z, double strength, long born) {
    public Ripple {
        if (!Double.isFinite(x) || !Double.isFinite(surfaceY) || !Double.isFinite(z)
                || !(strength >= 0.0 && strength <= Splash.MAX_STRENGTH)) {
            throw new IllegalArgumentException("bad ripple: " + x + " " + surfaceY + " " + z + " " + strength);
        }
    }

    /** effects: returns how old this ripple is at {@code now}, never negative */
    public long age(long now) {
        return Math.max(0L, now - born);
    }
}
