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
import java.util.List;

/**
 * Where the wake is drawn: quads on the water surface, from a trail.
 *
 * <p>Two shapes, both laid along the samples so they follow a turning
 * boat's track rather than its present heading:
 * <ul>
 *   <li>the foam strip -- the turbulent water straight behind the hull,
 *       the hull's width at the stern, spreading and fading with age;</li>
 *   <li>the arms -- the two lines of the Kelvin V, each sample's point
 *       pushed out to the side by the distance it lies behind the bow times
 *       the tangent of the Kelvin half-angle, so the V opens at 39 degrees
 *       whatever the speed and bends where the track bends.</li>
 * </ul>
 * Every quad's corners carry the texture's u across and a v along that is
 * pinned to the sample's tick, so foam stays where the water put it while
 * the boat moves on, and streaks longer the faster the boat was going.
 *
 * <p>The same shapes serve a swimmer -- a player or an animal crossing the
 * surface -- with a body's width for the hull's and a strength below one
 * for a touch of a wake rather than a boat's.
 */
public final class WakeGeometry {
    private WakeGeometry() {}

    /** A corner of a quad: position, texture coordinates and opacity in {@code [0, 1]}. */
    public record Vertex(double x, double y, double z, float u, float v, float alpha) {}

    /** Four corners in order round the quad. */
    public record Quad(Vertex a, Vertex b, Vertex c, Vertex d) {}

    /**
     * What shapes the wake. Immutable.
     *
     * <p>RI: {@code hullWidth > 0}; {@code spread >= 1}; {@code lifeTicks >= 1};
     * {@code 0 <= minSpeed < fullSpeed}; {@code textureTicks > 0};
     * {@code armWidth > 0}; {@code lift} finite; {@code strength} in {@code (0, 1]}.
     *
     * @param hullWidth    the hull's beam, the foam's width at the stern
     * @param spread       how many hull widths the foam spreads to by the end of its life
     * @param lifeTicks    how long foam lasts
     * @param minSpeed     the speed below which there is no wake, blocks per tick
     * @param fullSpeed    the speed at which the wake is at full strength
     * @param textureTicks ticks of travel per repeat of the foam texture along the strip
     * @param armWidth     the width of each arm of the V
     * @param lift         how far above the water surface the quads sit, against z-fighting
     * @param strength     what the intensity is scaled by: 1 for a hull, less for a swimmer's
     *                     smaller wake
     * @param floor        the intensity just above {@code minSpeed}: the slowest wake there is
     */
    public record Params(double hullWidth, double spread, int lifeTicks, double minSpeed, double fullSpeed,
                         double textureTicks, double armWidth, double lift, double strength, double floor) {
        public Params {
            if (!(hullWidth > 0.0) || !(spread >= 1.0) || lifeTicks < 1 || !(minSpeed >= 0.0) || !(fullSpeed > minSpeed)
                    || !(textureTicks > 0.0) || !(armWidth > 0.0) || !Double.isFinite(lift) || !(strength > 0.0 && strength <= 1.0)
                    || !(floor >= 0.0 && floor <= 1.0)) {
                throw new IllegalArgumentException("bad wake params: " + hullWidth + " " + spread + " " + lifeTicks + " "
                        + minSpeed + " " + fullSpeed + " " + textureTicks + " " + armWidth + " " + lift + " " + strength + " " + floor);
            }
        }

        /** A hull's params: full strength, no floor. */
        public Params(double hullWidth, double spread, int lifeTicks, double minSpeed, double fullSpeed,
                      double textureTicks, double armWidth, double lift) {
            this(hullWidth, spread, lifeTicks, minSpeed, fullSpeed, textureTicks, armWidth, lift, 1.0, 0.0);
        }

        /** Params with a floor and strength. */
        public Params(double hullWidth, double spread, int lifeTicks, double minSpeed, double fullSpeed,
                      double textureTicks, double armWidth, double lift, double strength) {
            this(hullWidth, spread, lifeTicks, minSpeed, fullSpeed, textureTicks, armWidth, lift, strength, 0.0);
        }
    }

    /** The churned water right behind a hull is wider than the hull: the foam starts at this many beams. */
    public static final double STERN_FACTOR = 1.35;
    /** An arm's width at its far end, as a fraction of its width at the bow. */
    public static final double ARM_TAPER = 0.4;
    /** The bow wave's length along the track, in blocks. */
    public static final double BOW_CREST_LENGTH = 0.7;

    /** One sample's place in the wake: its point, local heading, intensity when it was made, and age now. */
    private record Station(Sample sample, double dx, double dz, double intensity, long age, double behind) {}

    /**
     * effects: returns the foam strip along {@code samples} at {@code now}:
     * one quad per pair of consecutive samples that are apart, each corner
     * pushed out to half the foam width at its sample and faded by its age.
     * Empty with fewer than two samples, or when no sample was made above
     * the minimum speed.
     *
     * @param samples a trail's samples, oldest first
     * @param now     the game tick
     * @param p       the shape
     */
    public static List<Quad> foamStrip(List<Sample> samples, long now, Params p) {
        return foamStrip(samples, now, p, 1.0, 1.0);
    }

    /**
     * effects: as {@link #foamStrip(List, long, Params)}, the foam's width
     * scaled by {@code widthScale} and its opacity by {@code alphaScale}:
     * a wider, fainter pass under the strip is the water disturbed either
     * side of the foam proper<br>
     * throws: {@link IllegalArgumentException} if either scale is not positive
     */
    public static List<Quad> foamStrip(List<Sample> samples, long now, Params p, double widthScale, double alphaScale) {
        if (!(widthScale > 0.0) || !(alphaScale > 0.0)) {
            throw new IllegalArgumentException("scales must be positive, were " + widthScale + " and " + alphaScale);
        }
        List<Station> stations = stations(samples, now, p);
        List<Quad> quads = new ArrayList<>();
        double stern = p.hullWidth() * STERN_FACTOR * widthScale;
        for (int i = 0; i + 1 < stations.size(); i++) {
            Station s0 = stations.get(i);
            Station s1 = stations.get(i + 1);
            if (s0.intensity == 0.0 && s1.intensity == 0.0) {
                continue;
            }
            double w0 = Wake.foamWidth(stern, p.spread(), s0.intensity, s0.age, p.lifeTicks()) / 2.0;
            double w1 = Wake.foamWidth(stern, p.spread(), s1.intensity, s1.age, p.lifeTicks()) / 2.0;
            float a0 = (float) (Wake.foamAlpha(s0.intensity, s0.age, p.lifeTicks()) * alphaScale);
            float a1 = (float) (Wake.foamAlpha(s1.intensity, s1.age, p.lifeTicks()) * alphaScale);
            float v0 = (float) (s0.sample.tick() / p.textureTicks());
            float v1 = (float) (s1.sample.tick() / p.textureTicks());
            quads.add(new Quad(
                    corner(s0, -w0, 0.0f, v0, a0, p.lift()),
                    corner(s0, w0, 1.0f, v0, a0, p.lift()),
                    corner(s1, w1, 1.0f, v1, a1, p.lift()),
                    corner(s1, -w1, 0.0f, v1, a1, p.lift())));
        }
        return quads;
    }

    /**
     * effects: returns the two arms of the V along {@code samples} at
     * {@code now}, port and starboard: one quad per pair of consecutive
     * samples, each sample's point pushed out sideways by the distance it
     * lies behind the newest sample times tan(19.47 degrees), the arm's
     * width, faded by age and by the sample's own intensity. Empty with
     * fewer than two samples.
     */
    public static List<Quad> arms(List<Sample> samples, long now, Params p) {
        List<Station> stations = stations(samples, now, p);
        List<Quad> quads = new ArrayList<>();
        if (stations.isEmpty()) {
            return quads;
        }
        double tan = Math.tan(Wake.KELVIN_HALF_ANGLE);
        double length = Math.max(1e-6, stations.get(0).behind);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i + 1 < stations.size(); i++) {
                Station s0 = stations.get(i);
                Station s1 = stations.get(i + 1);
                if (s0.intensity == 0.0 && s1.intensity == 0.0) {
                    continue;
                }
                double out0 = side * s0.behind * tan;
                double out1 = side * s1.behind * tan;
                // Thick at the bow, thinning toward the end of the arm.
                double half0 = armHalfWidth(p, s0.behind / length, s0.intensity);
                double half1 = armHalfWidth(p, s1.behind / length, s1.intensity);
                float a0 = (float) (Wake.foamAlpha(s0.intensity, s0.age, p.lifeTicks()) * 0.95);
                float a1 = (float) (Wake.foamAlpha(s1.intensity, s1.age, p.lifeTicks()) * 0.95);
                float v0 = (float) (s0.sample.tick() / p.textureTicks());
                float v1 = (float) (s1.sample.tick() / p.textureTicks());
                quads.add(new Quad(
                        corner(s0, out0 - half0, 0.0f, v0, a0, p.lift()),
                        corner(s0, out0 + half0, 1.0f, v0, a0, p.lift()),
                        corner(s1, out1 + half1, 1.0f, v1, a1, p.lift()),
                        corner(s1, out1 - half1, 0.0f, v1, a1, p.lift())));
            }
        }
        return quads;
    }

    /** Half an arm's width at {@code along} of its length (0 at the bow, 1 at the end), for the intensity there. */
    private static double armHalfWidth(Params p, double along, double intensity) {
        double taper = 1.0 - (1.0 - ARM_TAPER) * Math.min(1.0, along);
        return p.armWidth() * taper * Math.sqrt(Math.max(intensity, 0.0)) / 2.0;
    }

    /**
     * effects: returns the bow wave: one quad across the bow, ahead of the
     * newest sample by a little more than half the hull, as wide as the
     * hull and a half at full intensity, {@link #BOW_CREST_LENGTH} long,
     * as opaque as the intensity at the bow; empty with fewer than two
     * samples or no wake
     */
    public static List<Quad> bowCrest(List<Sample> samples, long now, Params p) {
        List<Station> stations = stations(samples, now, p);
        if (stations.isEmpty()) {
            return List.of();
        }
        Station head = stations.get(stations.size() - 1);
        if (head.intensity == 0.0) {
            return List.of();
        }
        double ahead = p.hullWidth() * 0.55;
        double half = p.hullWidth() * 0.75 * Math.sqrt(head.intensity);
        float alpha = (float) head.intensity;
        Sample s = head.sample;
        double bx = s.x() + head.dx * ahead;
        double bz = s.z() + head.dz * ahead;
        double fx = s.x() + head.dx * (ahead + BOW_CREST_LENGTH);
        double fz = s.z() + head.dz * (ahead + BOW_CREST_LENGTH);
        double px = -head.dz;
        double pz = head.dx;
        float v = (float) (s.tick() / p.textureTicks());
        return List.of(new Quad(
                new Vertex(bx - px * half, s.surfaceY() + p.lift(), bz - pz * half, 0.0f, v, alpha),
                new Vertex(bx + px * half, s.surfaceY() + p.lift(), bz + pz * half, 1.0f, v, alpha),
                new Vertex(fx + px * half, s.surfaceY() + p.lift(), fz + pz * half, 1.0f, v + 0.5f, alpha),
                new Vertex(fx - px * half, s.surfaceY() + p.lift(), fz - pz * half, 0.0f, v + 0.5f, alpha)));
    }

    /**
     * effects: the samples with a heading, an intensity and an age each. A
     * sample's heading is toward the next sample (the last takes the one
     * before); its intensity is the wake the speed around it made -- the
     * speed over a window of {@link Trail#SPEED_WINDOW} ticks centred on it,
     * since a client sees a remote boat move in steps -- and its "behind"
     * is the track distance from the newest sample back to it. Samples on
     * top of the previous one are dropped, so headings exist.
     */
    private static List<Station> stations(List<Sample> samples, long now, Params p) {
        List<Sample> apart = new ArrayList<>();
        for (Sample s : samples) {
            if (apart.isEmpty() || apart.get(apart.size() - 1).distanceTo(s) > 1e-6) {
                apart.add(s);
            }
        }
        int n = apart.size();
        if (n < 2) {
            return List.of();
        }
        double[] behind = new double[n];
        for (int i = n - 2; i >= 0; i--) {
            behind[i] = behind[i + 1] + apart.get(i).distanceTo(apart.get(i + 1));
        }
        List<Station> out = new ArrayList<>(n);
        int half = Trail.SPEED_WINDOW / 2;
        for (int i = 0; i < n; i++) {
            Sample s = apart.get(i);
            Sample from = i + 1 < n ? s : apart.get(i - 1);
            Sample to = i + 1 < n ? apart.get(i + 1) : s;
            double dx = to.x() - from.x();
            double dz = to.z() - from.z();
            double length = Math.sqrt(dx * dx + dz * dz);
            // The speed over a window round the sample, in ticks, not samples.
            int lo = i;
            int hi = i;
            while (lo > 0 && s.tick() - apart.get(lo - 1).tick() <= half) {
                lo--;
            }
            while (hi + 1 < n && apart.get(hi + 1).tick() - s.tick() <= half) {
                hi++;
            }
            if (lo == hi) {
                lo = Math.max(0, i - 1);
                hi = Math.min(n - 1, i + 1);
            }
            double speed = apart.get(lo).distanceTo(apart.get(hi)) / Math.max(1L, apart.get(hi).tick() - apart.get(lo).tick());
            double intensity = Wake.intensity(Math.min(speed, 100.0), p.minSpeed(), p.fullSpeed(), p.floor()) * p.strength();
            out.add(new Station(s, dx / length, dz / length, intensity, Math.max(0L, now - s.tick()), behind[i]));
        }
        return out;
    }

    /** A corner {@code across} blocks to starboard of the station (negative: port). */
    private static Vertex corner(Station st, double across, float u, float v, float alpha, double lift) {
        // Starboard of a heading (dx, dz) on Minecraft's axes (x east, z south) is (-dz, dx).
        double px = -st.dz;
        double pz = st.dx;
        return new Vertex(st.sample.x() + px * across, st.sample.surfaceY() + lift, st.sample.z() + pz * across,
                u, v, Math.max(0.0f, Math.min(1.0f, alpha)));
    }
}
