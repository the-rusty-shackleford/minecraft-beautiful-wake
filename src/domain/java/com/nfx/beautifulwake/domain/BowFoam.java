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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The water a bow throws up: a burst of round white bubbles off the hull's
 * shoulders, tossed up and outward, falling back to the surface and
 * floating there a moment before they pop. Drawn as billboards, so they
 * stand up out of the water rather than lying on it -- the one part of a
 * wake that is not a surface.
 *
 * <p>One of these per craft. Bubbles are spawned by the tracker each tick
 * from where the bow is and advanced once a tick; between ticks the drawer
 * carries each on by its velocity. The count is bounded by
 * {@link #MAX_BUBBLES}, the oldest making way.
 */
public final class BowFoam {
    /**
     * One bubble.
     *
     * @param x    position at the last tick
     * @param y    position at the last tick
     * @param z    position at the last tick
     * @param vx   velocity per tick, zero once it has settled on the water
     * @param vy   velocity per tick
     * @param vz   velocity per tick
     * @param size its diameter, in blocks
     * @param born the tick it was thrown
     * @param life how many ticks it lasts
     */
    public record Bubble(double x, double y, double z, double vx, double vy, double vz, double size, long born, int life) {
        public Bubble {
            if (!(size > 0.0) || life < 1) {
                throw new IllegalArgumentException("bad bubble: size " + size + " life " + life);
            }
        }

        /** effects: returns how opaque the bubble is at {@code now}: whole for most of its life, then gone over the last {@link #FADE_FRACTION} of it */
        public double alpha(double now) {
            double along = (now - born) / life;
            if (along <= 1.0 - FADE_FRACTION) {
                return 1.0;
            }
            return Math.max(0.0, (1.0 - along) / FADE_FRACTION);
        }

        /** effects: returns whether the bubble is still on the water at {@code now} */
        public boolean alive(long now) {
            return now - born < life;
        }

        /** effects: returns whether the bubble has come to rest on the water */
        public boolean settled() {
            return vx == 0.0 && vy == 0.0 && vz == 0.0;
        }
    }

    /** Ticks a bubble lasts, at the least and the most. */
    public static final int MIN_LIFE = 12;
    public static final int MAX_LIFE = 22;
    /** The last part of a bubble's life over which it fades. */
    public static final double FADE_FRACTION = 0.35;
    /** How much of a bubble's upward speed each tick takes away. */
    public static final double GRAVITY = 0.022;
    /** The most bubbles one bow keeps. */
    public static final int MAX_BUBBLES = 220;
    /** Above this intensity the bow throws bubbles. */
    public static final double FROM = 0.25;
    /** The biggest a bubble can be, in blocks: a tenth of a block, not a snowball. */
    public static final double MAX_SIZE = 0.1;

    private final List<Bubble> bubbles = new ArrayList<>();

    /** effects: returns the live bubbles, oldest first; the list is the foam's own and must not be kept */
    public List<Bubble> bubbles() {
        return bubbles;
    }

    /**
     * effects: returns how many bubbles a bow throws in a tick at
     * {@code intensity}, up to {@code max}: none below {@link #FROM}, the
     * full count at full intensity, rising steeply between<br>
     * throws: {@link IllegalArgumentException} if {@code intensity} is outside {@code [0, 1]} or {@code max < 0}
     */
    public static int countFor(double intensity, int max) {
        if (!(intensity >= 0.0 && intensity <= 1.0) || max < 0) {
            throw new IllegalArgumentException("bad count arguments: intensity " + intensity + " max " + max);
        }
        if (intensity <= FROM) {
            return 0;
        }
        double along = (intensity - FROM) / (1.0 - FROM);
        return (int) Math.ceil(max * along * along);
    }

    /**
     * effects: throws {@code count} bubbles off the shoulders of a bow at
     * {@code (x, surfaceY, z)} heading {@code (hx, hz)}, a unit vector,
     * for a hull {@code hull} wide at {@code intensity}, at tick
     * {@code now}: each from one shoulder, up and outward, faster and
     * bigger the harder the bow is pushing; the oldest are dropped past
     * {@link #MAX_BUBBLES}
     */
    public void throwOff(RandomGenerator random, double x, double surfaceY, double z, double hx, double hz, double hull,
                         double intensity, int count, long now) {
        if (!(intensity >= 0.0 && intensity <= 1.0) || !(hull > 0.0) || count < 0) {
            throw new IllegalArgumentException("bad throw arguments: intensity " + intensity + " hull " + hull + " count " + count);
        }
        double px = -hz;   // starboard
        double pz = hx;
        double push = 0.5 + intensity;
        for (int i = 0; i < count; i++) {
            double side = random.nextBoolean() ? 1.0 : -1.0;
            // Most from the bow's shoulders, a few from the churn off the stern.
            boolean stern = random.nextDouble() < 0.2;
            double along = stern ? -hull * (0.5 + 0.4 * random.nextDouble()) : hull * (0.45 - 0.55 * random.nextDouble());
            double across = stern ? side * hull * 0.35 * random.nextDouble() : side * hull * (0.42 + 0.16 * random.nextDouble());
            double up = (stern ? 0.03 : 0.05 + 0.13 * random.nextDouble()) * push;
            double out = stern ? 0.0 : side * (0.01 + 0.05 * random.nextDouble()) * push;
            // A cloud of small ones, a few a little bigger -- never a snowball.
            double roll = random.nextDouble();
            double size = (0.04 + 0.06 * roll * roll * roll) * (0.7 + 0.3 * intensity);
            int life = MIN_LIFE + random.nextInt(MAX_LIFE - MIN_LIFE + 1);
            bubbles.add(new Bubble(x + hx * along + px * across, surfaceY + 0.02, z + hz * along + pz * across,
                    hx * 0.02 + px * out, up, hz * 0.02 + pz * out, size, now, life));
        }
        while (bubbles.size() > MAX_BUBBLES) {
            bubbles.remove(0);
        }
    }

    /**
     * effects: moves every bubble on one tick to {@code now}: the airborne
     * fall under gravity and, reaching the water at {@code surfaceY},
     * settle there and stop; the dead are dropped
     */
    public void tick(long now, double surfaceY) {
        Iterator<Bubble> it = bubbles.iterator();
        List<Bubble> moved = new ArrayList<>(bubbles.size());
        while (it.hasNext()) {
            Bubble b = it.next();
            it.remove();
            if (!b.alive(now)) {
                continue;
            }
            if (b.settled()) {
                moved.add(b);
                continue;
            }
            double vy = b.vy() - GRAVITY;
            double y = b.y() + vy;
            if (y <= surfaceY + 0.02) {
                moved.add(new Bubble(b.x() + b.vx(), surfaceY + 0.02, b.z() + b.vz(), 0.0, 0.0, 0.0, b.size(), b.born(), b.life()));
            } else {
                moved.add(new Bubble(b.x() + b.vx(), y, b.z() + b.vz(), b.vx(), vy, b.vz(), b.size(), b.born(), b.life()));
            }
        }
        bubbles.addAll(moved);
    }

    /** effects: drops every bubble */
    public void clear() {
        bubbles.clear();
    }
}
