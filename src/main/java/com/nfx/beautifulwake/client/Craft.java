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
package com.nfx.beautifulwake.client;

import com.nfx.beautifulwake.WakeConfig;
import com.nfx.beautifulwake.domain.WakeField;
import com.nfx.beautifulwake.domain.WakeParams;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * What makes a wake, and what kind.
 *
 * <p>A <em>watercraft</em> is any boat -- the vanilla class, which nearly
 * every modded boat extends -- or any entity type the config lists. A
 * <em>swimmer</em> is a living thing with its feet in the water and its
 * eyes out of it, moving along the surface under its own power: not
 * diving, and not sitting in a boat, whose wake is the boat's. Neither
 * needs to be in water this tick to keep its trail -- a boat pulled onto
 * the bank leaves its foam behind it to fade.
 */
public final class Craft {
    private Craft() {}

    /** The two kinds of wake-maker. */
    public enum Kind { WATERCRAFT, SWIMMER }

    /** How far above the water surface the wake's still level sits, against z-fighting with the water's own face. */
    private static final double LIFT = 0.02;

    /** effects: returns what kind of wake-maker {@code entity} is, if any */
    public static Optional<Kind> kindOf(Entity entity) {
        if (entity instanceof Boat) {
            return Optional.of(Kind.WATERCRAFT);
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (WakeConfig.EXTRA_WATERCRAFT.get().contains(id)) {
            return Optional.of(Kind.WATERCRAFT);
        }
        if (WakeConfig.SWIMMERS.get() && entity instanceof LivingEntity && entity.getVehicle() == null
                && entity.isInWater() && !entity.isUnderWater()) {
            return Optional.of(Kind.SWIMMER);
        }
        return Optional.empty();
    }

    /**
     * No floor on the intensity: a floor was a jump the moment a boat passed
     * the minimum speed ("the wake just suddenly snapped in"); the ramp's
     * curve gives a paddling boat its proper wake instead.
     */
    private static final double HULL_FLOOR = 0.0;
    private static final double SWIMMER_FLOOR = 0.0;

    /** A swimmer's wake stands this much lower than a hull's. */
    private static final double SWIMMER_RELIEF = 0.8;
    /** A swimmer's wake is made by a hull this many times the body's width: the water a wader pushes is wider than their legs. */
    private static final double SWIMMER_HULL = 2.0;

    /**
     * effects: returns the shape of {@code kind}'s wake for a body
     * {@code width} wide: a hull's at full strength and relief with a
     * boat's nose, a swimmer's from twice the body's width, quicker to
     * reach full at a swimmer's speeds, at the configured fraction of a
     * boat's, standing lower, with a nose short enough to stay inside the
     * body
     */
    public static WakeParams params(Kind kind, double width) {
        double relief = WakeConfig.RELIEF.get();
        double size = WakeConfig.SCALE.get();
        return switch (kind) {
            case WATERCRAFT -> new WakeParams(Math.max(0.6, width), WakeConfig.lifeTicks(),
                    WakeConfig.MIN_SPEED.get(), WakeConfig.FULL_SPEED.get(), 20.0, LIFT, 1.0, HULL_FLOOR, relief, size, WakeField.HULL_NOSE);
            case SWIMMER -> new WakeParams(Math.max(0.6, width * SWIMMER_HULL), WakeConfig.lifeTicks(),
                    WakeConfig.SWIMMER_MIN_SPEED.get(), WakeConfig.SWIMMER_FULL_SPEED.get(), 12.0, LIFT,
                    WakeConfig.SWIMMER_STRENGTH.get(), SWIMMER_FLOOR, relief * SWIMMER_RELIEF, size, WakeField.SWIMMER_NOSE);
        };
    }

    /**
     * effects: returns the height of the water surface under {@code entity}:
     * the top of the water at its feet, or in the block above or below when
     * a hull rides high or low in it; empty when it is not over water
     */
    public static OptionalDouble surface(Level level, Entity entity) {
        BlockPos feet = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
        for (BlockPos pos : new BlockPos[] {feet, feet.above(), feet.below()}) {
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER)) {
                // The top of this water; the block above being water too means
                // the surface is higher still, which the next iteration finds.
                if (!level.getFluidState(pos.above()).isEmpty()) {
                    continue;
                }
                return OptionalDouble.of(pos.getY() + fluid.getHeight(level, pos));
            }
        }
        return OptionalDouble.empty();
    }
}
