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
import com.nfx.beautifulwake.domain.Ripple;
import com.nfx.beautifulwake.domain.Splash;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Watches for things going into the water and makes their splashes: the
 * tick an entity that was dry is in water, a ring is born on the surface
 * where it went in, sized by its {@link Mass} and the speed it fell at,
 * with droplets thrown up and bubbles left under.
 *
 * <p>The vanilla game splashes only for living things; a dropped item
 * enters the water in silence. Here everything splashes, in proportion.
 * A thing already in water when first seen is not a splash; a thing that
 * went in far from the camera is not looked at.
 */
public final class SplashTracker {
    private SplashTracker() {}

    /** How long a ring lasts. */
    public static final int RING_LIFE_TICKS = 18;

    /** The entities seen out of the water last tick: the only ones that can go in this tick. */
    private static final Set<Integer> DRY = new HashSet<>();
    private static final List<Ripple> RIPPLES = new ArrayList<>();

    /** effects: returns the live ripples, for the renderer and the booth; not to be kept */
    public static List<Ripple> ripples() {
        return RIPPLES;
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DRY.clear();
        RIPPLES.clear();
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        long now = WakeTracker.now();
        RIPPLES.removeIf(r -> r.age(now) > RING_LIFE_TICKS);
        if (!WakeConfig.SPLASHES.get()) {
            DRY.clear();
            return;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double reach = WakeConfig.MAX_DISTANCE.get();
        Set<Integer> dryNow = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.isInWater()) {
                dryNow.add(entity.getId());
                continue;
            }
            // Seen out of the water last tick, in it now: it just went in.
            // A thing first seen in the water -- loaded with its chunk, or
            // spawned there -- did not fall in, and splashes nothing.
            if (DRY.contains(entity.getId()) && entity.distanceToSqr(eye) <= reach * reach) {
                splash(level, entity, now);
            }
        }
        DRY.clear();
        DRY.addAll(dryNow);
    }

    private static void splash(ClientLevel level, Entity entity, long now) {
        OptionalDouble surface = Craft.surface(level, entity);
        if (surface.isEmpty()) {
            return;
        }
        double vy = entity.getDeltaMovement().y;
        double strength = Splash.strength(Mass.of(entity), Math.abs(vy));
        if (strength < 0.05) {
            return;
        }
        double y = surface.getAsDouble();
        RIPPLES.add(new Ripple(entity.getX(), y, entity.getZ(), strength, now));
        RandomSource random = level.random;
        double spreadOut = 0.15 + 0.25 * strength;
        for (int i = 0; i < Splash.droplets(strength); i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double out = spreadOut * (0.3 + 0.7 * random.nextDouble());
            level.addParticle(ParticleTypes.SPLASH, entity.getX(), y + 0.05, entity.getZ(),
                    Math.cos(angle) * out, 0.15 + 0.3 * strength * random.nextDouble(), Math.sin(angle) * out);
        }
        for (int i = 0; i < Splash.bubbles(strength); i++) {
            level.addParticle(ParticleTypes.BUBBLE, entity.getX() + (random.nextDouble() - 0.5) * 0.4,
                    y - 0.2 - random.nextDouble() * 0.4, entity.getZ() + (random.nextDouble() - 0.5) * 0.4,
                    0.0, 0.05, 0.0);
        }
    }
}
