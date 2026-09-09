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
 * How much wake a speed makes, and how a wake grows and fades.
 *
 * <p>Speeds are blocks per tick. A rowed boat makes about 0.1; a boat at
 * full speed on open water about 0.4. Everything scales with one number,
 * the intensity: nothing below {@code minSpeed}, everything at
 * {@code fullSpeed}, straight between -- so a wake grows as the boat
 * gathers way and dies as it drifts, rather than switching on and off.
 */
public final class Wake {
    private Wake() {}

    /**
     * The half-angle of a ship's wake, in radians: the diverging waves fan
     * out from the bow at 19.47 degrees either side of the track, whatever
     * the speed (Lord Kelvin, 1887). The visible V behind every boat.
     */
    public static final double KELVIN_HALF_ANGLE = Math.asin(1.0 / 3.0);

    /**
     * effects: returns the wake's intensity for {@code speed}: 0 at or below
     * {@code minSpeed}, 1 at or above {@code fullSpeed}, linear between<br>
     * throws: {@link IllegalArgumentException} if the speeds are not
     * {@code 0 <= minSpeed < fullSpeed} or {@code speed} is negative or not finite
     */
    public static double intensity(double speed, double minSpeed, double fullSpeed) {
        return intensity(speed, minSpeed, fullSpeed, 0.0);
    }

    /** The ramp's curve: below one, so the wake comes on quickly from nothing and eases toward full -- never a jump. */
    public static final double RAMP = 0.6;

    /**
     * effects: returns the wake's intensity for {@code speed} with a floor:
     * 0 at or below {@code minSpeed}, {@code floor} just above it, 1 at or
     * above {@code fullSpeed}, between them rising as the {@link #RAMP}
     * power of the way from one to the other -- quickly at first, easing
     * toward full, so a boat gathering way sees its wake build rather than
     * snap in. A floor above zero is a jump at the minimum speed, and is
     * kept only for callers that want one.<br>
     * throws: {@link IllegalArgumentException} as the three-argument form,
     * or if {@code floor} is outside {@code [0, 1]}
     */
    public static double intensity(double speed, double minSpeed, double fullSpeed, double floor) {
        if (!(minSpeed >= 0.0) || !(fullSpeed > minSpeed)) {
            throw new IllegalArgumentException("need 0 <= minSpeed < fullSpeed, had " + minSpeed + " and " + fullSpeed);
        }
        if (!(speed >= 0.0) || Double.isInfinite(speed)) {
            throw new IllegalArgumentException("speed must be finite and >= 0, was " + speed);
        }
        if (!(floor >= 0.0 && floor <= 1.0)) {
            throw new IllegalArgumentException("floor must be in [0, 1], was " + floor);
        }
        if (speed <= minSpeed) {
            return 0.0;
        }
        if (speed >= fullSpeed) {
            return 1.0;
        }
        return floor + (1.0 - floor) * Math.pow((speed - minSpeed) / (fullSpeed - minSpeed), RAMP);
    }

    /**
     * effects: returns how opaque foam is {@code age} ticks after the hull
     * passed, for a wake of {@code intensity}: the intensity at the stern,
     * falling to nothing at the end of the wake's life along a curve that
     * drops quickly at first and trails off -- solid white right behind the
     * hull, breaking into patches a boat's length back, a ghost of itself
     * by the end<br>
     * throws: {@link IllegalArgumentException} if {@code lifeTicks < 1},
     * {@code age < 0} or {@code intensity} outside {@code [0, 1]}
     */
    public static double foamAlpha(double intensity, long age, int lifeTicks) {
        if (lifeTicks < 1 || age < 0 || !(intensity >= 0.0 && intensity <= 1.0)) {
            throw new IllegalArgumentException("bad foamAlpha arguments: intensity " + intensity + " age " + age + " life " + lifeTicks);
        }
        double along = Math.min(1.0, (double) age / lifeTicks);
        return intensity * Math.pow(1.0 - along, 1.5);
    }

    /**
     * effects: returns how much of the wake is left {@code age} ticks --
     * fractional, so it moves every frame and not once a tick -- after the
     * hull passed, along {@link #foamAlpha}'s curve: 1 at the hull, 0 at the
     * end of its life<br>
     * throws: {@link IllegalArgumentException} if {@code lifeTicks < 1} or {@code age} is negative or not finite
     */
    public static double fade(double age, int lifeTicks) {
        if (lifeTicks < 1 || !(age >= 0.0) || Double.isInfinite(age)) {
            throw new IllegalArgumentException("bad fade arguments: age " + age + " life " + lifeTicks);
        }
        double along = Math.min(1.0, age / lifeTicks);
        return Math.pow(1.0 - along, 1.5);
    }

    /**
     * effects: returns how many spray droplets to throw this tick for a wake
     * of {@code intensity}, spray starting at {@code sprayFrom} intensity and
     * reaching {@code maxPerTick} at full: a bow throws nothing at a paddle
     * and sheets of it at speed<br>
     * throws: {@link IllegalArgumentException} if {@code intensity} is outside
     * {@code [0, 1]}, {@code sprayFrom} outside {@code [0, 1)} or
     * {@code maxPerTick < 0}
     */
    public static int sprayCount(double intensity, double sprayFrom, int maxPerTick) {
        if (!(intensity >= 0.0 && intensity <= 1.0) || !(sprayFrom >= 0.0 && sprayFrom < 1.0) || maxPerTick < 0) {
            throw new IllegalArgumentException("bad sprayCount arguments: intensity " + intensity + " from " + sprayFrom + " max " + maxPerTick);
        }
        if (intensity <= sprayFrom) {
            return 0;
        }
        double along = (intensity - sprayFrom) / (1.0 - sprayFrom);
        return (int) Math.round(maxPerTick * along * along);
    }
}
