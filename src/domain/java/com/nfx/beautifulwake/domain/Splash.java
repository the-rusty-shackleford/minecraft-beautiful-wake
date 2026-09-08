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
 * What the water does when something goes into it: a splash sized by what
 * fell and how fast, a ring that spreads and fades, droplets thrown up and
 * bubbles left under.
 *
 * <p>Mass is a plain number, about 1 for a player, less for an apple, more
 * for a stack of ingots; whoever knows the thing supplies it. Vertical
 * speed is blocks per tick on entry.
 */
public final class Splash {
    private Splash() {}

    /** The most a splash can be, so a falling anvil is big and not absurd. */
    public static final double MAX_STRENGTH = 3.0;

    /**
     * effects: returns the splash's strength: the mass times a factor that
     * starts at one half for a thing set gently on the water and grows with
     * the speed it went in at, clamped to {@link #MAX_STRENGTH}<br>
     * throws: {@link IllegalArgumentException} if {@code mass} is negative or
     * not finite, or {@code verticalSpeed} is not finite
     */
    public static double strength(double mass, double verticalSpeed) {
        if (!(mass >= 0.0) || Double.isInfinite(mass) || !Double.isFinite(verticalSpeed)) {
            throw new IllegalArgumentException("bad splash arguments: mass " + mass + " speed " + verticalSpeed);
        }
        return Math.min(MAX_STRENGTH, mass * (0.5 + 2.5 * Math.abs(verticalSpeed)));
    }

    /**
     * effects: returns the ring's radius {@code age} ticks in: starting
     * small and spreading, quickly at first and slower later (the square
     * root of the way through its life), to a final radius that grows with
     * the strength<br>
     * throws: {@link IllegalArgumentException} if {@code strength} is outside
     * {@code [0, MAX_STRENGTH]}, {@code age < 0} or {@code lifeTicks < 1}
     */
    public static double ringRadius(double strength, long age, int lifeTicks) {
        check(strength, age, lifeTicks);
        double along = Math.min(1.0, (double) age / lifeTicks);
        double start = 0.25 + 0.15 * strength;
        double end = 0.4 + 1.4 * strength;
        return start + (end - start) * Math.sqrt(along);
    }

    /**
     * effects: returns how opaque the ring is {@code age} ticks in: the
     * strength (capped at one) at birth, falling to nothing at the end of
     * its life, faster toward the end<br>
     * throws: as {@link #ringRadius}
     */
    public static double ringAlpha(double strength, long age, int lifeTicks) {
        check(strength, age, lifeTicks);
        double along = Math.min(1.0, (double) age / lifeTicks);
        return Math.min(1.0, strength) * Math.pow(1.0 - along, 1.5);
    }

    /** effects: returns how many droplets to throw up on entry, none for nothing, at most 24 */
    public static int droplets(double strength) {
        check(strength, 0, 1);
        return (int) Math.min(24, Math.round(6.0 * strength));
    }

    /** effects: returns how many bubbles to leave under the surface on entry, at most 12 */
    public static int bubbles(double strength) {
        check(strength, 0, 1);
        return (int) Math.min(12, Math.round(3.0 * strength));
    }

    private static void check(double strength, long age, int lifeTicks) {
        if (!(strength >= 0.0 && strength <= MAX_STRENGTH) || age < 0 || lifeTicks < 1) {
            throw new IllegalArgumentException("bad ring arguments: strength " + strength + " age " + age + " life " + lifeTicks);
        }
    }
}
