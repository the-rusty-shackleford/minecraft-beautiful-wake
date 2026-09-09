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
 * The shape of one kind of wake-maker's wake.
 *
 * @param hullWidth    the width of the body making the wake, in blocks
 * @param lifeTicks    how long the wake lasts on the water
 * @param minSpeed     the speed, blocks per tick, below which there is no wake
 * @param fullSpeed    the speed at which the wake is at full strength
 * @param textureTicks how many ticks one repeat of the foam texture covers along the trail
 * @param lift         how far above the water surface the wake is drawn, against z-fighting
 * @param strength     a scale on the whole wake, {@code (0, 1]}: a swimmer's is a fraction of a hull's
 * @param floor        the intensity just above {@code minSpeed}, so the slowest wake is still one
 * @param relief       a scale on the wake's heights: 1 is a hull's, a swimmer's less
 */
public record WakeParams(double hullWidth, int lifeTicks, double minSpeed, double fullSpeed, double textureTicks,
                         double lift, double strength, double floor, double relief) {
    public WakeParams {
        if (!(hullWidth > 0.0) || lifeTicks < 1 || !(minSpeed >= 0.0) || !(fullSpeed > minSpeed) || !(textureTicks > 0.0)
                || !Double.isFinite(lift) || !(strength > 0.0 && strength <= 1.0) || !(floor >= 0.0 && floor <= 1.0)
                || !(relief >= 0.0) || Double.isInfinite(relief)) {
            throw new IllegalArgumentException("bad wake params: " + hullWidth + " " + lifeTicks + " " + minSpeed + " "
                    + fullSpeed + " " + textureTicks + " " + lift + " " + strength + " " + floor + " " + relief);
        }
    }

    /** A hull's: full strength, no floor, full relief. */
    public WakeParams(double hullWidth, int lifeTicks, double minSpeed, double fullSpeed, double textureTicks, double lift) {
        this(hullWidth, lifeTicks, minSpeed, fullSpeed, textureTicks, lift, 1.0, 0.0, 1.0);
    }
}
