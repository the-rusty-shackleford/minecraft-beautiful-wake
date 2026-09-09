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
 * The wake as a surface to draw: a grid of vertices laid over the water
 * along a trail, each with the water's height there, its normal, a foam
 * coverage and the texture coordinates -- from {@link WakeField}'s heights
 * and foam, in the frame the trail's samples give.
 *
 * <p>Rows follow the samples, one row per sample plus a few ahead of the
 * bow for the bow wave; each row runs across the track from one edge of
 * the V to the other, the same number of columns in every row, so the grid
 * fans out with the V and quads join neighbouring rows column by column.
 * The bow's row is the newest sample; distance behind the bow is the
 * track distance to it, so the pattern bends with the track.
 *
 * <p>Two texture mappings ride on every vertex. The <em>skin</em> mapping
 * is in the hull's frame: across, the distance outside the V's edge, so
 * a texture column falls on the edge wherever the V is and a line drawn
 * there stays a line however coarse the grid; along, the chevron phase,
 * so a line drawn at phase zero lies on every ridge -- the phase is
 * continuous over the whole grid, and where there are no chevrons yet
 * the lines are faded out by the vertex instead, so no quad ever spans a
 * jump in phase. The <em>foam</em>
 * mapping is pinned to the water: across in blocks, along by the tick the
 * water was passed, so the churn stays where the hull churned it.
 */
public final class WakeMesh {
    private WakeMesh() {}

    /**
     * A point on the surface.
     *
     * @param x       east-west position
     * @param y       height, the water surface plus the wake's own
     * @param z       north-south position
     * @param nx      the surface normal, unit length
     * @param ny      the surface normal
     * @param nz      the surface normal
     * @param edge    blocks outside the V's edge, negative inside, clamped at {@link #EDGE_INSIDE} inside
     * @param chevron the chevron phase in wavelengths, continuous over the whole grid
     * @param foamU   blocks across the track, for the foam texture
     * @param foamV   the tick the water here was passed, in texture repeats
     * @param skin    how visible the disturbed water is here, in {@code [0, 1]}
     * @param lines   how visible the chevron lines are here, in {@code [0, 1]}: nothing until they start
     * @param foam    how much foam there is here, in {@code [0, 1]}
     * @param shade   how the light falls on the slope here: 1 on flat water, more facing the light, less facing away
     */
    public record Vertex(double x, double y, double z, double nx, double ny, double nz, float edge, float chevron,
                         float foamU, float foamV, float skin, float lines, float foam, float shade) {}

    /** The surface: rows of vertices, bow first, every row {@code columns} long. */
    public record Mesh(List<List<Vertex>> rows, int columns) {
        /** effects: returns how many quads the mesh has */
        public int quads() {
            return rows.isEmpty() ? 0 : (rows.size() - 1) * (columns - 1);
        }
    }

    /** How far inside the V the edge coordinate goes before it stops, in blocks: past it the skin is all one thing. */
    public static final double EDGE_INSIDE = -3.0;
    /** Rows laid ahead of the hull's centre for the bow wave and the V's point, as fractions of the hull's width ahead. */
    private static final double[] AHEAD = {WakeField.POINT_AHEAD, 0.7, 0.5, 0.3, 0.1};
    /** Rows thin out with age: every sample this young, then every second, then every third. */
    public static final int EVERY_SAMPLE_UNTIL = 24;
    public static final int EVERY_SECOND_UNTIL = 60;
    /** How far past the V's edge the grid reaches, so the edge's fade has room. */
    private static final double MARGIN = WakeField.EDGE + 0.15;
    /** Blocks across one repeat of the foam texture. */
    public static final double FOAM_TILE = 3.0;
    /** The light the relief is shaded by: high, from the south-west, the same whichever way the hull heads. */
    private static final double[] LIGHT = unit(-0.45, 0.8, 0.4);
    /** How much a slope facing the light brightens, and one facing away darkens. */
    private static final double SHADE_STRENGTH = 1.6;
    /** The brightest a slope gets. */
    public static final float SHADE_MAX = 1.25f;
    /** The darkest. */
    public static final float SHADE_MIN = 0.55f;
    /** The tightest turn a row can follow on the inside, as a fraction of the turn's radius: past it rows would fold over each other. */
    private static final double INSIDE_OF_TURN = 0.85;

    /**
     * effects: returns the surface along {@code samples} at {@code now},
     * {@code columns} vertices across each row, {@code columns} odd so a
     * column runs down the track; no rows with fewer than two samples
     * apart, or no wake anywhere. On the inside of a turn a row stops
     * short of the V's edge where it would otherwise fold over the rows
     * before it. The intensity at each row is the wake
     * the speed there made; the skin and the foam fade with the sample's
     * age as foam does.
     *
     * <p>Rows thin out with age -- every sample for the youngest, then
     * every second, then every third -- the chevrons being wide enough
     * that a row a block apart still draws them.
     *
     * @param samples a trail's samples, oldest first
     * @param now     the game tick, with any fraction of the next
     * @param p       the shape
     * @param columns vertices across, odd and at least 3
     * @param table   the field, tabulated for {@code p}'s hull<br>
     * throws: {@link IllegalArgumentException} if {@code columns} is even or under 3, or {@code table} is for another hull
     */
    public static Mesh build(List<Sample> samples, double now, WakeParams p, int columns, WakeTable table) {
        if (columns < 3 || columns % 2 == 0) {
            throw new IllegalArgumentException("columns must be odd and >= 3, was " + columns);
        }
        if (table.hull() != p.hullWidth()) {
            throw new IllegalArgumentException("table is for a hull " + table.hull() + " wide, params for " + p.hullWidth());
        }
        long tickNow = (long) Math.floor(now);
        // The samples apart from one another, thinned with age: the intensity
        // still comes from the full trail, which the windowed speed reads.
        List<Sample> apart = new ArrayList<>();
        List<Integer> index = new ArrayList<>();
        int kept = -1;
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            if (!apart.isEmpty() && apart.get(apart.size() - 1).distanceTo(s) <= 1e-6) {
                continue;
            }
            long age = tickNow - s.tick();
            int every = age <= EVERY_SAMPLE_UNTIL ? 1 : age <= EVERY_SECOND_UNTIL ? 2 : 3;
            boolean last = i == samples.size() - 1;
            if (!last && kept >= 0 && i - kept < every) {
                continue;
            }
            apart.add(s);
            index.add(i);
            kept = i;
        }
        int n = apart.size();
        if (n < 2) {
            return new Mesh(List.of(), columns);
        }
        double[] behind = new double[n];
        for (int i = n - 2; i >= 0; i--) {
            behind[i] = behind[i + 1] + apart.get(i).distanceTo(apart.get(i + 1));
        }
        List<List<Vertex>> rows = new ArrayList<>();
        Sample head = apart.get(n - 1);
        double[] heading = heading(apart, n - 1);
        double headIntensity = intensityAt(samples, index.get(n - 1), p);
        double hull = p.hullWidth();
        for (double ahead : AHEAD) {
            rows.add(row(head.x() + heading[0] * ahead * hull, head.surfaceY(), head.z() + heading[1] * ahead * hull,
                    heading, -ahead * hull, head.tick(), headIntensity, 0L, p, columns, 0.0, table));
        }
        for (int i = n - 1; i >= 0; i--) {
            Sample s = apart.get(i);
            long age = Math.max(0L, tickNow - s.tick());
            rows.add(row(s.x(), s.surfaceY(), s.z(), heading(apart, i), behind[i], s.tick(), intensityAt(samples, index.get(i), p), age, p,
                    columns, curvature(apart, i), table));
        }
        for (List<Vertex> row : rows) {
            for (Vertex v : row) {
                if (v.skin() > 0.0f || v.foam() > 0.0f) {
                    return new Mesh(rows, columns);
                }
            }
        }
        return new Mesh(List.of(), columns);
    }

    /**
     * One row across the track at {@code (cx, cz)}, its middle vertex on the
     * track and half the columns either side, each side reaching the V's
     * edge and its margin -- or, on the inside of a turn of curvature
     * {@code curvature} (positive turning to starboard), only as far as the
     * turn's radius allows before rows would fold over one another.
     */
    private static List<Vertex> row(double cx, double surfaceY, double cz, double[] heading, double d, long tick,
                                    double intensity, long age, WakeParams p, int columns, double curvature, WakeTable table) {
        double hull = p.hullWidth();
        double px = -heading[1];   // starboard
        double pz = heading[0];
        double half = WakeField.halfWidth(hull, d) + MARGIN;
        double starboardHalf = half;
        double portHalf = half;
        if (curvature > 1e-6) {
            starboardHalf = Math.min(half, INSIDE_OF_TURN / curvature);
        } else if (curvature < -1e-6) {
            portHalf = Math.min(half, INSIDE_OF_TURN / -curvature);
        }
        double ageFade = Wake.foamAlpha(1.0, age, p.lifeTicks());
        double chevronStart = Math.max(0.0, Math.min(1.0, (d - hull * WakeField.CHEVRON_FROM) / hull));
        int side = columns / 2;
        List<Vertex> row = new ArrayList<>(columns);
        for (int j = 0; j < columns; j++) {
            double s = j < side ? -portHalf * (side - j) / side : starboardHalf * (j - side) / side;
            double scale = intensity * p.relief();
            double h = scale * table.height(d, s);
            double foam = Math.min(1.0, Math.sqrt(intensity) * table.foam(d, s)) * ageFade;
            // The normal from the slope along and across the track; d runs
            // backward along the track, so a rise with d falls along the heading.
            double hd = scale * table.slopeD(d, s);
            double hs = scale * table.slopeS(d, s);
            double nx = hd * heading[0] - hs * px;
            double nz = hd * heading[1] - hs * pz;
            double len = Math.sqrt(nx * nx + 1.0 + nz * nz);
            double edgeFade = WakeField.edgeFade(hull, d, s);
            double skin = edgeFade * ageFade * intensity;
            float edge = (float) Math.max(EDGE_INSIDE, Math.abs(s) - WakeField.halfWidth(hull, d));
            float chevron = (float) WakeField.chevronPhase(d, s);
            double lines = skin * chevronStart * Math.exp(-Math.max(0.0, d) / WakeField.CHEVRON_DECAY);
            row.add(new Vertex(cx + px * s, surfaceY + p.lift() + h, cz + pz * s, nx / len, 1.0 / len, nz / len,
                    edge, chevron, (float) (s / FOAM_TILE), (float) (tick / p.textureTicks()),
                    (float) Math.max(0.0, Math.min(1.0, skin)), (float) Math.max(0.0, Math.min(1.0, lines)),
                    (float) Math.max(0.0, Math.min(1.0, foam)), shade(nx / len, 1.0 / len, nz / len)));
        }
        return row;
    }

    /**
     * effects: returns how the light falls on a slope with unit normal
     * {@code (nx, ny, nz)}: exactly 1 on flat water, brighter facing the
     * light up to {@link #SHADE_MAX}, darker facing away down to
     * {@link #SHADE_MIN} -- the relief's own shading, so ridges read as
     * ridges whatever the renderer makes of the normals
     */
    public static float shade(double nx, double ny, double nz) {
        double flat = LIGHT[1];
        double here = nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2];
        double shade = 1.0 + SHADE_STRENGTH * (here - flat);
        return (float) Math.max(SHADE_MIN, Math.min(SHADE_MAX, shade));
    }

    /**
     * The track's curvature at sample {@code i}, in radians per block,
     * positive turning to starboard: the turn between the leg into the
     * sample and the leg out of it, over the distance from the middle of
     * one leg to the middle of the other.
     */
    private static double curvature(List<Sample> apart, int i) {
        int n = apart.size();
        if (i == 0 || i + 1 >= n) {
            return 0.0;
        }
        double[] in = heading(apart, i - 1);
        double[] out = heading(apart, i);
        double cross = in[0] * out[1] - in[1] * out[0];
        double dot = in[0] * out[0] + in[1] * out[1];
        double turn = Math.atan2(cross, dot);
        double over = (apart.get(i - 1).distanceTo(apart.get(i)) + apart.get(i).distanceTo(apart.get(i + 1))) / 2.0;
        return over < 1e-6 ? 0.0 : turn / over;
    }

    private static double[] unit(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / len, y / len, z / len};
    }

    /** The unit heading at sample {@code i}: toward the next sample, the last taking the one before. */
    private static double[] heading(List<Sample> apart, int i) {
        int n = apart.size();
        Sample from = i + 1 < n ? apart.get(i) : apart.get(i - 1);
        Sample to = i + 1 < n ? apart.get(i + 1) : apart.get(i);
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        return len < 1e-9 ? new double[] {1.0, 0.0} : new double[] {dx / len, dz / len};
    }

    /** The intensity at sample {@code i}: the speed over a window of ticks round it, through the wake's ramp. */
    private static double intensityAt(List<Sample> apart, int i, WakeParams p) {
        int n = apart.size();
        int half = Trail.SPEED_WINDOW / 2;
        Sample s = apart.get(i);
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
        return Wake.intensity(Math.min(speed, 100.0), p.minSpeed(), p.fullSpeed(), p.floor()) * p.strength();
    }
}
