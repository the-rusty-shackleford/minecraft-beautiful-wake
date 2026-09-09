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
import com.nfx.beautifulwake.domain.BowFoam;
import com.nfx.beautifulwake.domain.Sample;
import com.nfx.beautifulwake.domain.Trail;
import com.nfx.beautifulwake.domain.Wake;
import com.nfx.beautifulwake.domain.WakeParams;
import com.nfx.beautifulwake.domain.WakeTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
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
 * the water stands there; one {@link BowFoam} per entity, bubbles thrown
 * off the bow each tick and moved on; and the bow's spray thrown as
 * particles.
 *
 * <p>Cost, stated: one pass over the level's entities per tick, a trail
 * append and a bubble step per craft in water, and at most
 * {@code sprayMax} particles per craft per tick. Trails of entities gone
 * from the level are dropped on the tick they go; a craft out of water
 * keeps its trail until the foam has faded.
 */
public final class WakeTracker {
    private WakeTracker() {}

    /**
     * A followed entity: its kind, its trail, its bow's bubbles, the water's
     * height last seen, the last tick it was seen, and the last tick it was
     * on the water making wake -- a diver's or a flier's trail fades where
     * it was left and is not carried along under them.
     */
    public record Tracked(Craft.Kind kind, Trail trail, BowFoam foam, double width, double surfaceY, long seenAt, long sampledAt) {}

    /** One table being built or built, for one hull width, nose and wash. */
    private record Building(double hull, double nose, double wash, CompletableFuture<WakeTable> future) {}

    /**
     * The field's tables, one per hull width and nose the client has met:
     * a boat's, a player's, a cow's -- a handful, each built once, off the
     * render thread, since one takes longer than a frame. A craft whose
     * table is still building is drawn without a wake for those few
     * frames. A list scanned by value, so asking allocates nothing.
     */
    private static final List<Building> TABLES = new ArrayList<>();

    /**
     * effects: returns the field tabulated for {@code p}'s hull and nose,
     * or empty while it is still being built -- the build is started the
     * first time that hull and nose are asked for
     */
    public static Optional<WakeTable> table(WakeParams p) {
        double hull = p.hullWidth();
        double nose = p.nose();
        double wash = p.wash();
        for (int i = 0; i < TABLES.size(); i++) {
            Building b = TABLES.get(i);
            if (b.hull() == hull && b.nose() == nose && b.wash() == wash) {
                return Optional.ofNullable(b.future().getNow(null));
            }
        }
        TABLES.add(new Building(hull, nose, wash, CompletableFuture.supplyAsync(() -> WakeTable.of(hull, nose, wash))));
        return Optional.empty();
    }

    /** effects: starts building the tables for the hulls every client meets first: a boat's and a player's */
    public static void warmTables() {
        table(Craft.params(Craft.Kind.WATERCRAFT, 1.375));
        table(Craft.params(Craft.Kind.SWIMMER, 0.6));
    }

    private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
    /**
     * The wake's clock: one per client tick, never set back. The level's
     * game time is the server's, rewritten on the client every second, and
     * a tick that repeats or is skipped there would drop a sample and the
     * head with it for a frame.
     */
    private static long ticks = 0L;

    /** effects: returns the wake's clock: the number of client ticks so far */
    public static long now() {
        return ticks;
    }
    /** The bubbles' own dice: the level's random is the game's, not a plain generator. */
    private static final Random BUBBLE_RANDOM = new Random();
    /** Above this intensity the bow throws water. */
    private static final double SPRAY_FROM = 0.3;
    /** The most bubbles a bow throws in a tick, a hull's and a swimmer's. */
    private static final int HULL_BUBBLES = 12;
    private static final int SWIMMER_BUBBLES = 6;

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
        ticks = 0L;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        long now = ++ticks;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double reach = WakeConfig.MAX_DISTANCE.get();
        int lifeTicks = WakeConfig.lifeTicks();

        for (Entity entity : level.entitiesForRendering()) {
            OptionalDouble surface = Craft.surface(level, entity);
            Optional<Craft.Kind> kind = Craft.kindOf(entity, surface);
            if (kind.isEmpty() || entity.distanceToSqr(eye) > reach * reach) {
                continue;
            }
            Tracked tracked = TRACKED.get(entity.getId());
            if (tracked == null || tracked.trail().lifeTicks() != lifeTicks) {
                tracked = new Tracked(kind.get(), new Trail(lifeTicks), new BowFoam(), entity.getBbWidth(), surface.orElse(entity.getY()), now, -1L);
            }
            double surfaceY = surface.orElse(tracked.surfaceY());
            tracked.foam().tick(now, surfaceY);
            long sampledAt = tracked.sampledAt();
            if (surface.isPresent()) {
                // The position at the START of this tick, the same point the
                // renderer's interpolation starts from: sampled at the end,
                // the head would sit behind the sample and every row's
                // distance behind the hull would sawtooth once a tick.
                tracked.trail().add(new Sample(entity.xo, surfaceY, entity.zo, now));
                bubbles(level, entity, tracked, surfaceY, now);
                spray(level, entity, tracked, surfaceY);
                sampledAt = now;
            }
            TRACKED.put(entity.getId(), new Tracked(kind.get(), tracked.trail(), tracked.foam(), entity.getBbWidth(), surfaceY, now, sampledAt));
        }

        // Drop what is gone, or has left the water and let its foam fade.
        Iterator<Map.Entry<Integer, Tracked>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Tracked> entry = it.next();
            Tracked t = entry.getValue();
            t.trail().prune(now);
            boolean gone = level.getEntity(entry.getKey()) == null;
            if (gone || (now - t.seenAt() > 1 && t.trail().isEmpty() && t.foam().bubbles().isEmpty())) {
                it.remove();
            } else if (now - t.seenAt() > 1) {
                t.foam().tick(now, t.surfaceY());
            }
        }
    }

    /** The wake's intensity for a craft this tick, from its trail's speed. */
    private static double intensity(Tracked tracked, WakeParams p) {
        return Wake.intensity(Math.min(tracked.trail().speed(), 10.0), p.minSpeed(), p.fullSpeed(), p.floor()) * p.strength();
    }

    /**
     * The bow's bubbles: a burst of round white foam off the hull's
     * shoulders, more and bigger the harder the bow pushes, none at a
     * paddle. They are drawn standing up, by the renderer.
     */
    private static void bubbles(ClientLevel level, Entity entity, Tracked tracked, double surfaceY, long now) {
        if (!WakeConfig.FOAM.get()) {
            return;
        }
        WakeParams p = Craft.params(tracked.kind(), tracked.width());
        double intensity = intensity(tracked, p);
        int most = (int) Math.round((tracked.kind() == Craft.Kind.SWIMMER ? SWIMMER_BUBBLES : HULL_BUBBLES) * p.size());
        int count = BowFoam.countFor(intensity, most);
        if (count == 0) {
            return;
        }
        double[] heading = tracked.trail().heading();
        tracked.foam().throwOff(BUBBLE_RANDOM, entity.xo, surfaceY, entity.zo, heading[0], heading[1],
                p.hullWidth(), intensity, count, now);
    }

    /**
     * Water off the bow: droplets thrown up and outward from the front
     * corners of the hull, more the faster it goes, none at a paddle.
     */
    private static void spray(ClientLevel level, Entity entity, Tracked tracked, double surfaceY) {
        if (!WakeConfig.SPRAY.get()) {
            return;
        }
        WakeParams p = Craft.params(tracked.kind(), tracked.width());
        double intensity = intensity(tracked, p);
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
            double x = entity.xo + hx * along + px * side;
            double z = entity.zo + hz * along + pz * side;
            double outward = 0.08 + 0.18 * intensity * random.nextDouble();
            double up = 0.12 + 0.28 * intensity * random.nextDouble();
            level.addParticle(ParticleTypes.SPLASH, x, surfaceY + 0.05, z,
                    hx * 0.05 + px * Math.signum(side) * outward, up, hz * 0.05 + pz * Math.signum(side) * outward);
        }
    }
}
