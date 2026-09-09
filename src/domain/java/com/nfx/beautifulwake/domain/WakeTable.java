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
 * {@link WakeField} sampled once for one hull width, so the mesh can look
 * heights and foam up instead of working them out: the field is a dozen
 * exponentials a point, a mesh is a couple of thousand points, and it is
 * rebuilt every frame for every craft in sight. A table is built once per
 * hull width -- a few boats' and a few animals' -- and read from then on.
 *
 * <p>Sampled on a grid {@link #STEP} blocks apart, {@code d} from the V's
 * point ahead of the hull to {@link #REACH} behind it and {@code s} from
 * the track out to the V's edge there, and read back bilinearly; the
 * field is symmetric about the track, so only starboard is kept. The
 * intensity is applied by the reader -- heights scale with it and foam
 * with its square root -- and so is the relief. Past the grid's reach the
 * last sample is read, where the chevrons have died away to nothing much.
 * Beside each height, its slopes along and across the track, for normals.
 *
 * <p>Building one is a few hundred thousand evaluations of the field,
 * tens of milliseconds: more than a frame, so a client builds them off
 * the render thread. Once built a table never changes, so it can be read
 * from any thread.
 */
public final class WakeTable {
    /** The grid's spacing, in blocks: a chevron wavelength holds thirty samples, the bow's foam a few. */
    public static final double STEP = 0.1;
    /** How far behind the hull's centre the grid reaches, in blocks: a wake at speed over its life, and the chevrons long since died away. */
    public static final double REACH = 48.0;

    private final double hull;
    private final double dFrom;
    private final int nd;
    private final int ns;
    private final float[] height;
    private final float[] slopeD;
    private final float[] slopeS;
    private final float[] foam;

    private WakeTable(double hull) {
        this.hull = hull;
        this.dFrom = -hull * WakeField.POINT_AHEAD - STEP;
        this.nd = (int) Math.ceil((REACH - dFrom) / STEP) + 1;
        double sMax = WakeField.halfWidth(hull, REACH) + WakeField.EDGE + STEP;
        this.ns = (int) Math.ceil(sMax / STEP) + 1;
        this.height = new float[nd * ns];
        this.slopeD = new float[nd * ns];
        this.slopeS = new float[nd * ns];
        this.foam = new float[nd * ns];
        for (int i = 0; i < nd; i++) {
            double d = dFrom + i * STEP;
            for (int j = 0; j < ns; j++) {
                double s = j * STEP;
                int at = i * ns + j;
                height[at] = (float) WakeField.height(hull, 1.0, 1.0, d, s);
                foam[at] = (float) WakeField.foam(hull, 1.0, d, s);
            }
        }
        // Slopes from the grid's own neighbours: across the track the
        // field is even in s, so the sample at -s is the one at +s.
        for (int i = 0; i < nd; i++) {
            for (int j = 0; j < ns; j++) {
                int at = i * ns + j;
                float before = i > 0 ? height[at - ns] : 0.0f;
                float after = i + 1 < nd ? height[at + ns] : height[at];
                slopeD[at] = (float) ((after - before) / (2.0 * STEP));
                float inboard = j > 0 ? height[at - 1] : height[at + 1];
                float outboard = j + 1 < ns ? height[at + 1] : 0.0f;
                slopeS[at] = (float) ((outboard - inboard) / (2.0 * STEP));
            }
        }
    }

    /**
     * effects: returns the field tabulated for a hull {@code hull} wide<br>
     * throws: {@link IllegalArgumentException} if {@code hull <= 0}
     */
    public static WakeTable of(double hull) {
        if (!(hull > 0.0) || Double.isInfinite(hull)) {
            throw new IllegalArgumentException("hull must be finite and > 0, was " + hull);
        }
        return new WakeTable(hull);
    }

    /** effects: returns the hull width this was built for */
    public double hull() {
        return hull;
    }

    /** effects: returns the height at {@code (d, s)} at full intensity and relief */
    public double height(double d, double s) {
        return read(height, d, s);
    }

    /** effects: returns the slope along the track at {@code (d, s)}, rising with {@code d}, at full intensity and relief */
    public double slopeD(double d, double s) {
        return read(slopeD, d, s);
    }

    /** effects: returns the slope across the track at {@code (d, s)}, rising with {@code s}, at full intensity and relief; it is odd in {@code s} */
    public double slopeS(double d, double s) {
        return s < 0.0 ? -read(slopeS, d, -s) : read(slopeS, d, s);
    }

    /** effects: returns the foam at {@code (d, s)} at full intensity */
    public double foam(double d, double s) {
        return read(foam, d, s);
    }

    private double read(float[] table, double d, double s) {
        double fi = (d - dFrom) / STEP;
        double fj = Math.abs(s) / STEP;
        if (fi <= 0.0) {
            return 0.0;
        }
        if (fj >= ns - 1) {
            return 0.0;
        }
        if (fi >= nd - 1) {
            fi = nd - 1 - 1e-9;
        }
        int i = (int) fi;
        int j = (int) fj;
        double ti = fi - i;
        double tj = fj - j;
        int at = i * ns + j;
        double a = table[at] * (1.0 - tj) + table[at + 1] * tj;
        double b = table[at + ns] * (1.0 - tj) + table[at + ns + 1] * tj;
        return a * (1.0 - ti) + b * ti;
    }
}
