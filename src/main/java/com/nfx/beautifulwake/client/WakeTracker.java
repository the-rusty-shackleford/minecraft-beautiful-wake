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
import com.nfx.beautifulwake.domain.Sample;
import com.nfx.beautifulwake.domain.Trail;
import com.nfx.beautifulwake.domain.Wake;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Follows every wake-maker the client can see: one {@link Trail} per
 * entity, a sample added each tick from where the entity is and how high
 * the water stands there, and the bow's spray thrown as particles.
 *
 * <p>Cost, stated: one pass over the level's entities per tick, a trail
 * append per craft in water, and at most {@code sprayMax} particles per
 * craft per tick. Trails of entities gone from the level are dropped on
 * the tick they go; a craft out of water keeps its trail until the foam
 * has faded.
 */
public final class WakeTracker {
    private WakeTracker() {}

    /** A followed entity: its kind, its trail, and the last tick it was seen. */
    public record Tracked(Craft.Kind kind, Trail trail, double width, long seenAt) {}

    private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
    /** Above this intensity the bow throws water. */
    private static final double SPRAY_FROM = 0.3;

    /** effects: returns what is followed for entity {@code id}, if anything */
    public static Optional<Tracked> tracked(int id) {
        return Optional.ofNullable(TRACKED.get(id));
    }

    /** effects: returns how many entities are followed right now */
    public static int trackedCount() {
        return TRACKED.size();
    }

    /** effects: returns every followed entity, by id; the map is live and must not be kept */
    public static Map<Integer, Tracked> all() {
        return TRACKED;
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TRACKED.clear();
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        long now = level.getGameTime();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double reach = WakeConfig.MAX_DISTANCE.get();
        int lifeTicks = WakeConfig.lifeTicks();

        for (Entity entity : level.entitiesForRendering()) {
            Optional<Craft.Kind> kind = Craft.kindOf(entity);
            if (kind.isEmpty() || entity.distanceToSqr(eye) > reach * reach) {
                continue;
            }
            OptionalDouble surface = Craft.surface(level, entity);
            Tracked tracked = TRACKED.get(entity.getId());
            if (tracked == null || tracked.trail().lifeTicks() != lifeTicks) {
                tracked = new Tracked(kind.get(), new Trail(lifeTicks), entity.getBbWidth(), now);
            }
            if (surface.isPresent()) {
                tracked.trail().add(new Sample(entity.getX(), surface.getAsDouble(), entity.getZ(), now));
                spray(level, entity, tracked, surface.getAsDouble());
            }
            TRACKED.put(entity.getId(), new Tracked(kind.get(), tracked.trail(), entity.getBbWidth(), now));
        }

        // Drop what is gone, or has left the water and let its foam fade.
        Iterator<Map.Entry<Integer, Tracked>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Tracked> entry = it.next();
            Tracked t = entry.getValue();
            t.trail().prune(now);
            boolean gone = level.getEntity(entry.getKey()) == null;
            if (gone || (now - t.seenAt() > 1 && t.trail().isEmpty())) {
                it.remove();
            }
        }
    }

    /**
     * Water off the bow: droplets thrown up and outward from the front
     * corners of the hull, more the faster it goes, none at a paddle.
     */
    private static void spray(ClientLevel level, Entity entity, Tracked tracked, double surfaceY) {
        if (!WakeConfig.SPRAY.get()) {
            return;
        }
        var p = Craft.params(tracked.kind(), tracked.width());
        double intensity = Wake.intensity(Math.min(tracked.trail().speed(), 10.0), p.minSpeed(), p.fullSpeed(), p.floor()) * p.strength();
        int max = tracked.kind() == Craft.Kind.SWIMMER ? Math.min(3, WakeConfig.SPRAY_MAX.get()) : WakeConfig.SPRAY_MAX.get();
        int count = Wake.sprayCount(intensity, SPRAY_FROM, max);
        if (count == 0) {
            return;
        }
        double[] heading = tracked.trail().heading();
        double hx = heading[0];
        double hz = heading[1];
        double px = -hz;   // starboard
        double pz = hx;
        double half = Math.max(0.3, tracked.width() / 2.0);
        double bow = Math.max(0.4, entity.getBbWidth() * 0.6);
        RandomSource random = level.random;
        for (int i = 0; i < count; i++) {
            double side = random.nextBoolean() ? half : -half;
            double along = bow * (0.4 + 0.6 * random.nextDouble());
            double x = entity.getX() + hx * along + px * side;
            double z = entity.getZ() + hz * along + pz * side;
            double outward = 0.08 + 0.18 * intensity * random.nextDouble();
            double up = 0.12 + 0.28 * intensity * random.nextDouble();
            level.addParticle(ParticleTypes.SPLASH, x, surfaceY + 0.05, z,
                    hx * 0.05 + px * Math.signum(side) * outward, up, hz * 0.05 + pz * Math.signum(side) * outward);
        }
    }
}
