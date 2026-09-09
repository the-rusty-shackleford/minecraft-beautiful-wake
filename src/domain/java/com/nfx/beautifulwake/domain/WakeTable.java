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
 * <p>Sampled on a grid {@link #STEP} blocks apart, {@code d} from the
 * nose's tip ahead of the hull to {@link #REACH} behind it and {@code s} from
 * the track out to the V's edge there, and read back bilinearly; the
 * field is symmetric about the track, so only starboard is kept. The
 * intensity is applied by the reader -- heights scale with it and foam
 * with its square root -- and so is the relief. Past the grid's reach the
 * last sample is read, where the chevrons have died away to nothing much.
 * The chevrons are kept apart from the rest -- their envelope, how tall a
 * ridge is at each point, beside the base of bow wave, trough and ripples
 * -- because their phase is not the table's to know: a wake that stays
 * where the water left it measures the phase from a point fixed in the
 * water, and the mesh puts the cosine in. Beside each part, its slopes
 * along and across the track, for normals.
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
    private final double nose;
    private final double wash;
    private final double dFrom;
    private final int nd;
    private final int ns;
    private final float[] base;
    private final float[] baseD;
    private final float[] baseS;
    private final float[] envelope;
    private final float[] envelopeD;
    private final float[] envelopeS;
    private final float[] foam;

    private WakeTable(double hull, double nose, double wash) {
        this.hull = hull;
        this.nose = nose;
        this.wash = wash;
        this.dFrom = -hull * nose - STEP;
        this.nd = (int) Math.ceil((REACH - dFrom) / STEP) + 1;
        double sMax = WakeField.halfWidth(hull, nose, REACH) + WakeField.EDGE + STEP;
        this.ns = (int) Math.ceil(sMax / STEP) + 1;
        this.base = new float[nd * ns];
        this.baseD = new float[nd * ns];
        this.baseS = new float[nd * ns];
        this.envelope = new float[nd * ns];
        this.envelopeD = new float[nd * ns];
        this.envelopeS = new float[nd * ns];
        this.foam = new float[nd * ns];
        for (int i = 0; i < nd; i++) {
            double d = dFrom + i * STEP;
            for (int j = 0; j < ns; j++) {
                double s = j * STEP;
                int at = i * ns + j;
                base[at] = (float) WakeField.base(hull, nose, 1.0, 1.0, d, s);
                envelope[at] = (float) (WakeField.edgeFade(hull, nose, d, s) * WakeField.chevronEnvelope(hull, d));
                foam[at] = (float) WakeField.foam(hull, nose, 1.0, d, s, wash);
            }
        }
        // Slopes from the grid's own neighbours: across the track the
        // field is even in s, so the sample at -s is the one at +s.
        slopes(base, baseD, baseS);
        slopes(envelope, envelopeD, envelopeS);
    }

    private void slopes(float[] of, float[] alongD, float[] acrossS) {
        for (int i = 0; i < nd; i++) {
            for (int j = 0; j < ns; j++) {
                int at = i * ns + j;
                float before = i > 0 ? of[at - ns] : 0.0f;
                float after = i + 1 < nd ? of[at + ns] : of[at];
                alongD[at] = (float) ((after - before) / (2.0 * STEP));
                float inboard = j > 0 ? of[at - 1] : of[at + 1];
                float outboard = j + 1 < ns ? of[at + 1] : 0.0f;
                acrossS[at] = (float) ((outboard - inboard) / (2.0 * STEP));
            }
        }
    }

    /**
     * effects: returns the field tabulated for a hull {@code hull} wide with a nose {@code nose} hulls long<br>
     * throws: {@link IllegalArgumentException} if {@code hull <= 0} or {@code nose <= 0}
     */
    public static WakeTable of(double hull, double nose) {
        return of(hull, nose, 0.0);
    }

    /**
     * effects: returns the field tabulated for a hull {@code hull} wide with a nose {@code nose} hulls long and {@code wash} round the body<br>
     * throws: {@link IllegalArgumentException} if {@code hull <= 0}, {@code nose <= 0} or {@code wash} is outside {@code [0, 1]}
     */
    public static WakeTable of(double hull, double nose, double wash) {
        if (!(hull > 0.0) || Double.isInfinite(hull) || !(nose > 0.0) || Double.isInfinite(nose) || !(wash >= 0.0 && wash <= 1.0)) {
            throw new IllegalArgumentException("hull and nose must be finite and > 0 and wash in [0, 1], were " + hull + ", " + nose + " and " + wash);
        }
        return new WakeTable(hull, nose, wash);
    }

    /** effects: returns whether this table was built for {@code p}'s hull, nose and wash */
    public boolean fits(WakeParams p) {
        return hull == p.hullWidth() && nose == p.nose() && wash == p.wash();
    }

    /** effects: returns the wash this was built with */
    public double wash() {
        return wash;
    }

    /** effects: returns the hull width this was built for */
    public double hull() {
        return hull;
    }

    /** effects: returns the nose length this was built for, in hulls */
    public double nose() {
        return nose;
    }

    /** effects: returns the height at {@code (d, s)} without the chevrons, at full intensity and relief */
    public double base(double d, double s) {
        return read(base, d, s);
    }

    /** effects: returns the base's slope along the track at {@code (d, s)}, rising with {@code d} */
    public double baseD(double d, double s) {
        return read(baseD, d, s);
    }

    /** effects: returns the base's slope across the track at {@code (d, s)}, rising with {@code s}; odd in {@code s} */
    public double baseS(double d, double s) {
        return s < 0.0 ? -read(baseS, d, -s) : read(baseS, d, s);
    }

    /** effects: returns the chevrons' envelope at {@code (d, s)}: how tall a ridge is there, the V's edge applied */
    public double envelope(double d, double s) {
        return read(envelope, d, s);
    }

    /** effects: returns the envelope's slope along the track at {@code (d, s)} */
    public double envelopeD(double d, double s) {
        return read(envelopeD, d, s);
    }

    /** effects: returns the envelope's slope across the track at {@code (d, s)}; odd in {@code s} */
    public double envelopeS(double d, double s) {
        return s < 0.0 ? -read(envelopeS, d, -s) : read(envelopeS, d, s);
    }

    /** effects: returns the height at {@code (d, s)} at full intensity and relief, the chevrons at the phase measured from the hull */
    public double height(double d, double s) {
        return base(d, s) + envelope(d, s) * Math.cos(2.0 * Math.PI * WakeField.chevronPhase(d, s));
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
