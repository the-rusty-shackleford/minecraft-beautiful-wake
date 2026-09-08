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
     *                     touch of a wake
     */
    public record Params(double hullWidth, double spread, int lifeTicks, double minSpeed, double fullSpeed,
                         double textureTicks, double armWidth, double lift, double strength) {
        public Params {
            if (!(hullWidth > 0.0) || !(spread >= 1.0) || lifeTicks < 1 || !(minSpeed >= 0.0) || !(fullSpeed > minSpeed)
                    || !(textureTicks > 0.0) || !(armWidth > 0.0) || !Double.isFinite(lift) || !(strength > 0.0 && strength <= 1.0)) {
                throw new IllegalArgumentException("bad wake params: " + hullWidth + " " + spread + " " + lifeTicks + " "
                        + minSpeed + " " + fullSpeed + " " + textureTicks + " " + armWidth + " " + lift + " " + strength);
            }
        }

        /** A hull's params: full strength. */
        public Params(double hullWidth, double spread, int lifeTicks, double minSpeed, double fullSpeed,
                      double textureTicks, double armWidth, double lift) {
            this(hullWidth, spread, lifeTicks, minSpeed, fullSpeed, textureTicks, armWidth, lift, 1.0);
        }
    }

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
        List<Station> stations = stations(samples, now, p);
        List<Quad> quads = new ArrayList<>();
        for (int i = 0; i + 1 < stations.size(); i++) {
            Station s0 = stations.get(i);
            Station s1 = stations.get(i + 1);
            if (s0.intensity == 0.0 && s1.intensity == 0.0) {
                continue;
            }
            double w0 = Wake.foamWidth(p.hullWidth(), p.spread(), s0.intensity, s0.age, p.lifeTicks()) / 2.0;
            double w1 = Wake.foamWidth(p.hullWidth(), p.spread(), s1.intensity, s1.age, p.lifeTicks()) / 2.0;
            float a0 = (float) Wake.foamAlpha(s0.intensity, s0.age, p.lifeTicks());
            float a1 = (float) Wake.foamAlpha(s1.intensity, s1.age, p.lifeTicks());
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
        double tan = Math.tan(Wake.KELVIN_HALF_ANGLE);
        double half = p.armWidth() / 2.0;
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i + 1 < stations.size(); i++) {
                Station s0 = stations.get(i);
                Station s1 = stations.get(i + 1);
                if (s0.intensity == 0.0 && s1.intensity == 0.0) {
                    continue;
                }
                double out0 = side * s0.behind * tan;
                double out1 = side * s1.behind * tan;
                float a0 = (float) (Wake.foamAlpha(s0.intensity, s0.age, p.lifeTicks()) * 0.8);
                float a1 = (float) (Wake.foamAlpha(s1.intensity, s1.age, p.lifeTicks()) * 0.8);
                float v0 = (float) (s0.sample.tick() / p.textureTicks());
                float v1 = (float) (s1.sample.tick() / p.textureTicks());
                quads.add(new Quad(
                        corner(s0, out0 - half, 0.0f, v0, a0, p.lift()),
                        corner(s0, out0 + half, 1.0f, v0, a0, p.lift()),
                        corner(s1, out1 + half, 1.0f, v1, a1, p.lift()),
                        corner(s1, out1 - half, 0.0f, v1, a1, p.lift())));
            }
        }
        return quads;
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
            double intensity = Wake.intensity(Math.min(speed, 100.0), p.minSpeed(), p.fullSpeed()) * p.strength();
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
