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
 * The shape of the water behind a hull: a height above the still surface
 * and a foam coverage at every point, in the hull's own frame -- {@code d}
 * blocks behind the bow along the track (negative ahead of it) and
 * {@code s} blocks across it, starboard positive.
 *
 * <p>What a hull does to water, each a term of the height:
 * <ul>
 *   <li>the <em>bow wave</em>: the hump the bow pushes up ahead of and
 *       beside it;</li>
 *   <li>the <em>stern trough</em>: the dip the hull leaves behind, which
 *       the churn fills back in;</li>
 *   <li>the <em>transverse waves</em>: a few crests across the track right
 *       behind the stern, dying away fast;</li>
 *   <li>the <em>chevrons</em>: the diverging waves, ridges running back
 *       and outward from the track at {@link #CHEVRON_ANGLE}, nested one
 *       behind another inside the V with their points toward the hull --
 *       the pattern every boat draws and the one a cel-shaded sea draws
 *       in white lines.</li>
 * </ul>
 * Distances are from the hull's centre, where a trail samples it: the bow
 * is half a hull ahead of {@code d = 0}, the stern half a hull behind.
 * The whole of it lives inside the V that opens from the bow at
 * {@link Wake#KELVIN_HALF_ANGLE}, with a crisp edge. Foam is the churn
 * straight behind the stern and the water thrown up at the bow's
 * shoulders. Every term scales with the intensity; the heights with the
 * relief too.
 */
public final class WakeField {
    private WakeField() {}

    /** The angle the chevron ridges make with the track, in radians: steeper than the V, so they end at its edge. */
    public static final double CHEVRON_ANGLE = Math.toRadians(42.0);
    /** The chevrons' spacing measured across them, in blocks. */
    public static final double CHEVRON_WAVELENGTH = 3.0;
    /** How far behind the bow, in hull widths, the first chevron begins. */
    public static final double CHEVRON_FROM = 0.5;
    /** The transverse crests' wavelength, in blocks: stylised, a real one at a boat's speed being far longer. */
    public static final double TRANSVERSE_WAVELENGTH = 2.2;
    /** Blocks behind the stern over which the chevrons lose most of their height. */
    public static final double CHEVRON_DECAY = 22.0;
    /** The edge outside the V, in blocks, over which everything fades to nothing: short, so the V has a line for an edge. */
    public static final double EDGE = 0.35;
    /** The bow wave's height at full intensity and relief, in blocks: the tallest thing in the field. */
    public static final double BOW_HEIGHT = 0.2;
    /** A chevron ridge's height at full intensity and relief, in blocks. */
    public static final double CHEVRON_HEIGHT = 0.12;

    private static final double TAN = Math.tan(Wake.KELVIN_HALF_ANGLE);
    private static final double CHEVRON_COS = Math.cos(CHEVRON_ANGLE);
    private static final double CHEVRON_SIN = Math.sin(CHEVRON_ANGLE);

    /** How far ahead of a hull's centre its outline's nose reaches, in hull widths: under the bow, where the boat hides it. */
    public static final double HULL_NOSE = 0.45;
    /** The same for a swimmer: inside the body, so nothing shows ahead of a wader. */
    public static final double SWIMMER_NOSE = 0.35;

    /**
     * effects: returns how far to either side of the track the wake reaches
     * {@code d} blocks behind the hull's centre: a little over half a hull
     * there, opening at the Kelvin angle behind it, and ahead of it
     * rounding off in a half-ellipse to nothing {@code nose} hulls ahead --
     * the outline hugs the hull and rounds off at the bow like the water
     * round a wader's legs, so nothing squared-off or pointed shows round
     * the body<br>
     * throws: {@link IllegalArgumentException} if {@code nose <= 0}
     */
    public static double halfWidth(double hull, double nose, double d) {
        if (!(nose > 0.0)) {
            throw new IllegalArgumentException("nose must be > 0, was " + nose);
        }
        if (d < 0.0) {
            double along = -d / (hull * nose);
            return along >= 1.0 ? 0.0 : hull * 0.65 * Math.sqrt(1.0 - along * along);
        }
        return hull * 0.65 + d * TAN;
    }

    /**
     * effects: returns 1 inside the V at {@code (d, s)}, falling smoothly to
     * 0 over {@link #EDGE} blocks outside it, and 0 at its nose's tip and ahead
     */
    public static double edgeFade(double hull, double nose, double d, double s) {
        if (d <= -hull * nose) {
            return 0.0;
        }
        double outside = Math.abs(s) - halfWidth(hull, nose, d);
        if (outside <= 0.0) {
            return 1.0;
        }
        if (outside >= EDGE) {
            return 0.0;
        }
        return 1.0 - smooth(outside / EDGE);
    }

    /**
     * effects: returns where {@code (d, s)} falls in the chevrons, in
     * wavelengths: the ridges are at whole numbers. The measure runs
     * perpendicular to the ridges, which run back and outward from the
     * track at {@link #CHEVRON_ANGLE} on either side -- so along a ridge
     * it is constant, along the track it grows with distance behind, and
     * it is the same on port and starboard, the two halves of each ridge
     * meeting in a point on the track with the point toward the hull.
     */
    public static double chevronPhase(double d, double s) {
        return (d * CHEVRON_SIN - Math.abs(s) * CHEVRON_COS) / CHEVRON_WAVELENGTH;
    }

    /**
     * effects: returns the water's height above the still surface at
     * {@code (d, s)} for a hull {@code hull} wide with a nose {@code nose}
     * hulls long making a wake of {@code intensity} at {@code relief}; the
     * pattern stands still in the hull's frame, as a wake's does<br>
     * throws: {@link IllegalArgumentException} if {@code hull <= 0},
     * {@code intensity} is outside {@code [0, 1]} or {@code relief < 0}
     */
    public static double height(double hull, double nose, double intensity, double relief, double d, double s) {
        check(hull, intensity, relief);
        if (intensity == 0.0 || relief == 0.0) {
            return 0.0;
        }
        double edge = edgeFade(hull, nose, d, s);
        if (edge == 0.0) {
            return 0.0;
        }
        double a = Math.abs(s);
        // The bow wave: a hump at the bow, the hull's width. Distances are
        // from the hull's centre, which is where a trail samples it; the bow
        // is half a hull ahead of that and the stern half a hull behind.
        double bowD = (d + hull * 0.3) / (hull * 0.4);
        double bowS = s / (hull * 0.45);
        double bow = BOW_HEIGHT * Math.exp(-bowD * bowD - bowS * bowS);
        double trough = 0.0;
        double transverse = 0.0;
        double chevron = 0.0;
        if (d > 0.0) {
            // Both of these begin under the stern half of the hull, rising from nothing at its centre.
            double rise = smooth(d / (hull * 0.5));
            // The stern trough: a dip right behind the hull, filling in over a length or two.
            double troughD = (d - hull * 0.5) / (hull * 1.1);
            double troughS = s / (hull * 0.6);
            trough = -0.1 * rise * Math.exp(-troughD * troughD - troughS * troughS);
            // Transverse crests across the churn, gone within a few wavelengths.
            double across = Math.exp(-(a / hull) * (a / hull));
            transverse = 0.06 * rise * Math.cos(2.0 * Math.PI * d / TRANSVERSE_WAVELENGTH) * Math.exp(-d / 7.0) * across;
            chevron = chevronHeight(hull, d, s);
        }
        return intensity * relief * edge * (bow + trough + transverse + chevron);
    }

    /**
     * effects: returns the chevron ridges' own height at {@code (d, s)},
     * before the intensity, the relief and the V's edge are applied: a
     * ridge {@link #CHEVRON_HEIGHT} high at a whole phase, a trough as
     * deep at a half, nothing until {@link #CHEVRON_FROM} hulls behind the
     * centre and rising over the next hull, dying away with distance --
     * the {@link #chevronEnvelope} times the cosine of the phase measured
     * from the hull
     */
    public static double chevronHeight(double hull, double d, double s) {
        return chevronEnvelope(hull, d) * Math.cos(2.0 * Math.PI * chevronPhase(d, s));
    }

    /**
     * effects: returns how tall the chevron ridges are {@code d} blocks
     * behind the hull's centre, whatever their phase: nothing until
     * {@link #CHEVRON_FROM} hulls back, rising to {@link #CHEVRON_HEIGHT}
     * over the next hull, dying away over {@link #CHEVRON_DECAY} blocks.
     * The phase may be measured from the hull, as {@link #chevronHeight}
     * does, or from a point fixed in the water, as a wake that stays where
     * it was left needs; the envelope is the same either way.
     */
    public static double chevronEnvelope(double hull, double d) {
        double from = d - hull * CHEVRON_FROM;
        if (from <= 0.0) {
            return 0.0;
        }
        double start = smooth(from / hull);
        return CHEVRON_HEIGHT * start * Math.exp(-d / CHEVRON_DECAY);
    }

    /**
     * effects: returns the water's height at {@code (d, s)} without the
     * chevrons: the bow wave, the trough and the transverse ripples, with
     * the intensity, the relief and the V's edge applied<br>
     * throws: as {@link #height}
     */
    public static double base(double hull, double nose, double intensity, double relief, double d, double s) {
        return height(hull, nose, intensity, relief, d, s) - intensity * relief * edgeFade(hull, nose, d, s) * chevronHeight(hull, d, s);
    }

    /** effects: returns the rate the chevron phase changes per block moved back along the track */
    public static double chevronPhasePerBlockBack() {
        return CHEVRON_SIN / CHEVRON_WAVELENGTH;
    }

    /** effects: returns the rate the chevron phase changes per block moved outward from the track */
    public static double chevronPhasePerBlockOut() {
        return -CHEVRON_COS / CHEVRON_WAVELENGTH;
    }

    /**
     * effects: returns the foam coverage at {@code (d, s)}, in {@code [0, 1]}:
     * the churn straight behind the stern -- solid at the stern, breaking up
     * and spreading over a few hull lengths -- and the water thrown up at
     * the bow's shoulders, where the hull meets the water<br>
     * throws: {@link IllegalArgumentException} if {@code hull <= 0} or
     * {@code intensity} is outside {@code [0, 1]}
     */
    public static double foam(double hull, double nose, double intensity, double d, double s) {
        return foam(hull, nose, intensity, d, s, 0.0);
    }

    /**
     * effects: as {@link #foam(double, double, double, double, double)},
     * with {@code wash} of white water churned up round the body itself --
     * a disc a hull across on the body, thrashed white by legs and arms --
     * which a clean hull does without<br>
     * throws: also if {@code wash} is outside {@code [0, 1]}
     */
    public static double foam(double hull, double nose, double intensity, double d, double s, double wash) {
        check(hull, intensity, 1.0);
        if (!(wash >= 0.0 && wash <= 1.0)) {
            throw new IllegalArgumentException("wash must be in [0, 1], was " + wash);
        }
        if (intensity == 0.0) {
            return 0.0;
        }
        double edge = edgeFade(hull, nose, d, s);
        if (edge == 0.0) {
            return 0.0;
        }
        double a = Math.abs(s);
        double body = wash * Math.exp(-Math.pow(Math.hypot(d, s) / (hull * 0.55), 4.0));
        // The churn: a band the hull's width and more, rising under the
        // stern half of the hull to solid at the stern, thinning behind.
        double stern = d - hull * 0.5;
        double churnWidth = hull * (0.55 + 0.05 * Math.max(0.0, stern));
        double rise = smooth((d + hull * 0.1) / (hull * 0.6));
        double churn = rise * Math.exp(-Math.pow(a / churnWidth, 4.0)) * Math.exp(-Math.max(0.0, stern) / (hull * 4.5));
        // The bow's shoulders: foam breaking either side of the bow and
        // streaming back along the hull's sides, thickest at the bow.
        double shoulderD = (d + hull * 0.3) / (hull * 0.5);
        double shoulderS = (a - hull * 0.52) / (hull * 0.3);
        double shoulder = Math.exp(-shoulderD * shoulderD - shoulderS * shoulderS);
        return Math.min(1.0, edge * Math.sqrt(intensity) * (churn + shoulder + body));
    }

    /** effects: returns {@code t} clamped to {@code [0, 1]} and eased at both ends: no corner where a ramp begins or ends */
    public static double smooth(double t) {
        if (t <= 0.0) {
            return 0.0;
        }
        if (t >= 1.0) {
            return 1.0;
        }
        return t * t * (3.0 - 2.0 * t);
    }

    private static void check(double hull, double intensity, double relief) {
        if (!(hull > 0.0) || !(intensity >= 0.0 && intensity <= 1.0) || !(relief >= 0.0) || Double.isInfinite(relief)) {
            throw new IllegalArgumentException("bad field arguments: hull " + hull + " intensity " + intensity + " relief " + relief);
        }
    }
}
