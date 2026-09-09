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
package com.nfx.beautifulwake.gametest;

import com.nfx.beautifulwake.client.Craft;
import com.nfx.beautifulwake.client.SplashTracker;
import com.nfx.beautifulwake.client.WakeRenderer;
import com.nfx.beautifulwake.client.WakeTracker;
import com.nfx.beautifulwake.domain.Ripple;
import com.nfx.beautifulwake.domain.Splash;
import com.nfx.beautifulwake.domain.Wake;
import com.nfx.beautifulwake.domain.WakeMesh;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The check through the real path, with pictures. Act one, scripted for
 * the numbers: a flat world with a pool dug into it, a boat moved through
 * the pool at a paddle, at speed and round a turn, a cow swum across it,
 * an apple, an ingot and a block dropped in, photographed from above and
 * from the water. Act two, the player's own: the booth's player wades
 * across the shelf under a held key, then climbs into the boat and drives
 * it on the boat's own controls, slow, flat out and hard over, watched from
 * behind. One {@code booth: PASS} or {@code booth: FAIL} line per check,
 * which the build's gate reads. Active only under
 * {@code beautifulwake.photobooth}.
 */
@EventBusSubscriber(modid = BoothMod.MOD_ID, value = Dist.CLIENT)
public final class WakeBooth {
    private WakeBooth() {}

    private static final Logger LOG = LoggerFactory.getLogger("Beautiful Wake booth");
    private static final boolean ACTIVE = Boolean.getBoolean("beautifulwake.photobooth");

    /** The pool: two blocks of water where the flat world's grass and top dirt were, and a shelf one deep for wading. */
    private static final int POOL_X0 = -34, POOL_X1 = 44, POOL_Z0 = -26, POOL_Z1 = 26;
    private static final int SHELF_Z0 = 14;
    private static final int GRASS_Y = -61;
    private static final double SURFACE = GRASS_Y + 1 - 1.0 / 9.0;   // a source block's top
    private static final double BOAT_Y = SURFACE - 0.35;

    private enum Phase { TITLE, LOADING, RUNNING, DONE }

    private record Step(int at, Runnable action) {}

    /** Ticks the scene holds still after the pool is dug, for the client to re-render its terrain. */
    private static final int HOLD = 120;

    private static Phase phase = Phase.TITLE;
    private static int tick = 0;
    private static List<Step> steps;
    /** The tick the clock waits at for the pool's chunks to be rebuilt, after the rebuild was asked for at tick 10. */
    private static final int GATE = 40;
    private static final long GATE_LIMIT_MS = 75_000L;
    private static long gateOpened = 0L;
    private static Boat boat;
    private static Cow cow;
    /** Whether the script still moves the boat; the second act hands it to the player. */
    private static boolean scripted = true;
    private static double boatX, boatZ, boatYaw;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ACTIVE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (phase) {
            case TITLE -> {
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    phase = Phase.LOADING;
                    createWorld(mc);
                }
            }
            case LOADING -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (mc.level != null && mc.player != null && mc.screen == null && server != null
                        && mc.level.hasChunkAt(mc.player.blockPosition())) {
                    phase = Phase.RUNNING;
                    tick = 0;
                    steps = plan(mc);
                    onServer(mc, WakeBooth::setUp);
                }
            }
            case RUNNING -> {
                // The pool went in after the client had its chunks, and a
                // software renderer -- slower still under a shader pack, and
                // running ten ticks a frame to catch up -- may not have
                // rebuilt them by the time the first photo is due. Hold the
                // clock at the gate until every section over the pool is
                // compiled, or a wall-clock limit passes.
                if (tick == GATE && !poolCompiled(mc)) {
                    if (gateOpened == 0L) {
                        gateOpened = System.currentTimeMillis();
                    }
                    if (System.currentTimeMillis() - gateOpened < GATE_LIMIT_MS) {
                        return;
                    }
                    LOG.warn("booth: the pool's sections were not all compiled after {} s; going on", GATE_LIMIT_MS / 1000);
                }
                onServer(mc, WakeBooth::drive);
                for (Step step : steps) {
                    if (step.at() == tick) {
                        step.action().run();
                    }
                }
                tick++;
            }
            case DONE -> { }
        }
    }

    /** Whether every render section over the pool has been compiled, sampled every eight blocks. */
    private static boolean poolCompiled(Minecraft mc) {
        for (int x = POOL_X0; x <= POOL_X1; x += 8) {
            for (int z = POOL_Z0; z <= POOL_Z1; z += 8) {
                if (!mc.levelRenderer.isSectionCompiled(new BlockPos(x, GRASS_Y, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void createWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings("Beautiful Wake booth", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, rules, WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(1L, false, false);
        mc.createWorldOpenFlows().createFreshLevel("beautifulwake-booth", settings, options,
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                mc.screen);
    }

    /** The pool, the boat, the cow, and the player hovering over the middle looking down. */
    private static void setUp(ServerPlayer sp) {
        ServerLevel level = sp.serverLevel();
        level.setDayTime(6000L);
        for (int x = POOL_X0; x <= POOL_X1; x++) {
            for (int z = POOL_Z0; z <= POOL_Z1; z++) {
                level.setBlock(new BlockPos(x, GRASS_Y, z), Blocks.WATER.defaultBlockState(), 3);
                if (z < SHELF_Z0) {
                    level.setBlock(new BlockPos(x, GRASS_Y - 1, z), Blocks.WATER.defaultBlockState(), 3);
                }
            }
        }
        boatX = POOL_X0 + 6;
        boatZ = 0;
        boatYaw = -90.0f;   // facing east: Minecraft's yaw is 0 south, 90 west, -90 east
        boat = new Boat(level, boatX, BOAT_Y, boatZ);
        boat.setVariant(Boat.Type.OAK);
        boat.setYRot((float) boatYaw);
        level.addFreshEntity(boat);
        cow = EntityType.COW.create(level);
        if (cow != null) {
            cow.setPos(POOL_X0 + 6, SURFACE - 0.8, 10);
            cow.setNoAi(true);
            level.addFreshEntity(cow);
        }
        sp.getAbilities().mayfly = true;
        sp.getAbilities().flying = true;
        sp.onUpdateAbilities();
        lookDown(sp, 0, 0);
    }

    /** Hovers the player fourteen blocks over {@code (x, z)}, looking straight down. */
    private static void lookDown(ServerPlayer sp, double x, double z) {
        sp.teleportTo(sp.serverLevel(), x, SURFACE + 14.0, z, 0.0f, 90.0f);
    }

    /** Puts the player on the water six blocks astern of the boat, looking along its track. */
    private static void lookFromAstern(ServerPlayer sp) {
        double rad = Math.toRadians(boatYaw);
        double hx = Math.sin(-rad);
        double hz = Math.cos(rad);
        sp.teleportTo(sp.serverLevel(), boatX - hx * 7.0, SURFACE + 1.2, boatZ - hz * 7.0, (float) boatYaw, 8.0f);
    }

    /**
     * The boat's course, one server tick at a time: a paddle east, then
     * full speed, then a wide turn, then it stops and drifts; the cow swims
     * north-south across the pool meanwhile.
     */
    private static void drive(ServerPlayer sp) {
        if (boat == null || tick < HOLD || !scripted) {
            return;
        }
        int t = tick - HOLD;
        double speed;
        double turn = 0.0;
        if (t < 80) {
            speed = 0.1;
        } else if (t < 180) {
            speed = 0.4;
        } else if (t < 330) {
            speed = 0.35;
            turn = 1.4;    // degrees per tick: a 150-tick sweep of 210 degrees, radius 14
        } else {
            speed = 0.0;
        }
        boatYaw += turn;
        double rad = Math.toRadians(boatYaw);
        // Minecraft's yaw: 0 south (+z), 90 west (-x), -90 east (+x).
        boatX += -Math.sin(rad) * speed;
        boatZ += Math.cos(rad) * speed;
        boat.setPos(boatX, BOAT_Y, boatZ);
        boat.setYRot((float) boatYaw);
        boat.setDeltaMovement(Vec3.ZERO);
        if (cow != null && t > 60 && t < 260) {
            cow.setPos(POOL_X0 + 6 + (t - 60) * 0.12, SURFACE - 0.8, 10 - (t - 60) * 0.06);
            cow.setDeltaMovement(Vec3.ZERO);
        }
    }

    private static List<Step> plan(Minecraft mc) {
        List<Step> s = new ArrayList<>();
        // The pool went in after the client had these chunks: every section
        // over it is stale until rebuilt, and a software renderer rebuilds
        // slowly. Ask for all of them at once and give it the hold.
        s.add(new Step(10, () -> mc.levelRenderer.allChanged()));
        s.add(new Step(HOLD + 70, () -> {
            onServer(mc, p -> lookDown(p, boatX + 2, boatZ));
        }));
        s.add(new Step(HOLD + 76, () -> verdict("a boat at a paddle leaves a faint wake", () -> {
            WakeTracker.Tracked t = WakeTracker.tracked(boat.getId()).orElseThrow();
            double intensity = Wake.intensity(t.trail().speed(), 0.075, 0.35);
            return t.kind() == Craft.Kind.WATERCRAFT && intensity > 0.02 && intensity < 0.3 && WakeRenderer.lastQuadCount() > 0
                    ? null : "kind " + t.kind() + " speed " + t.trail().speed() + " quads " + WakeRenderer.lastQuadCount();
        })));
        s.add(new Step(HOLD + 78, () -> shoot(mc, "booth-wake-paddle")));
        s.add(new Step(HOLD + 170, () -> onServer(mc, p -> lookDown(p, boatX - 4, boatZ))));
        s.add(new Step(HOLD + 176, () -> verdict("at speed the wake is at full strength, drawn in relief, and the bow throws bubbles", () -> {
            WakeTracker.Tracked t = WakeTracker.tracked(boat.getId()).orElseThrow();
            double intensity = Wake.intensity(t.trail().speed(), 0.075, 0.35);
            if (intensity < 0.99 || WakeRenderer.lastQuadCount() < 400 || WakeRenderer.lastBubbleCount() == 0) {
                return "speed " + t.trail().speed() + " quads " + WakeRenderer.lastQuadCount() + " bubbles " + WakeRenderer.lastBubbleCount();
            }
            // The surface stands up: a bow wave over a tenth of a block, somewhere a slope.
            var params = Craft.params(t.kind(), t.width());
            var table = WakeTracker.table(params);
            if (table.isEmpty()) {
                return "the boat's table is still building";
            }
            WakeMesh.Mesh mesh = WakeMesh.build(t.trail().samples(), mc.level.getGameTime(), params, 25, table.get());
            double top = Double.NEGATIVE_INFINITY;
            double lean = 0.0;
            for (List<WakeMesh.Vertex> row : mesh.rows()) {
                for (WakeMesh.Vertex v : row) {
                    top = Math.max(top, v.y() - SURFACE);
                    lean = Math.max(lean, 1.0 - v.ny());
                }
            }
            return top > 0.1 && lean > 0.05 ? null : "top " + top + " above the water, most lean " + lean;
        })));
        s.add(new Step(HOLD + 178, () -> shoot(mc, "booth-wake-speed-top")));
        s.add(new Step(HOLD + 180, () -> onServer(mc, WakeBooth::lookFromAstern)));
        s.add(new Step(HOLD + 186, () -> shoot(mc, "booth-wake-speed-astern")));
        s.add(new Step(HOLD + 188, () -> verdict("the cow swimming across leaves a touch of a wake", () -> {
            if (cow == null) {
                return "no cow";
            }
            var tracked = WakeTracker.tracked(cow.getId());
            if (tracked.isEmpty()) {
                return "the cow is not tracked";
            }
            WakeTracker.Tracked t = tracked.get();
            return t.kind() == Craft.Kind.SWIMMER && t.trail().speed() > 0.1
                    ? null : "kind " + t.kind() + " speed " + t.trail().speed();
        })));
        s.add(new Step(HOLD + 290, () -> onServer(mc, p -> lookDown(p, boatX, boatZ))));
        s.add(new Step(HOLD + 296, () -> shoot(mc, "booth-wake-turn")));
        s.add(new Step(HOLD + 300, () -> onServer(mc, p -> {
            ServerLevel level = p.serverLevel();
            drop(level, POOL_X0 + 20, -4, new ItemStack(Items.APPLE));
            drop(level, POOL_X0 + 24, -4, new ItemStack(Items.IRON_INGOT));
            drop(level, POOL_X0 + 28, -4, new ItemStack(Items.IRON_BLOCK));
            lookDown(p, POOL_X0 + 24, -4);
        })));
        s.add(new Step(HOLD + 324, () -> shoot(mc, "booth-splash")));
        s.add(new Step(HOLD + 326, () -> verdict("an apple, an ingot and a block splash in that order of size", () -> {
            List<Ripple> ripples = SplashTracker.ripples();
            if (ripples.size() < 3) {
                return ripples.size() + " ripples";
            }
            double apple = ripples.get(ripples.size() - 3).strength();
            double ingot = ripples.get(ripples.size() - 2).strength();
            double block = ripples.get(ripples.size() - 1).strength();
            if (!(apple < ingot && ingot < block)) {
                return "apple " + apple + " ingot " + ingot + " block " + block;
            }
            // The apple's ring has no foam on it; the ingot's some, the block's more.
            double appleFoam = Splash.foamAlpha(apple, 0, SplashTracker.RING_LIFE_TICKS);
            double ingotFoam = Splash.foamAlpha(ingot, 0, SplashTracker.RING_LIFE_TICKS);
            double blockFoam = Splash.foamAlpha(block, 0, SplashTracker.RING_LIFE_TICKS);
            return appleFoam == 0.0 && ingotFoam > 0.0 && blockFoam > ingotFoam
                    ? null : "foam: apple " + appleFoam + " ingot " + ingotFoam + " block " + blockFoam;
        })));
        // The boat stopped at tick 330; its foam lives four seconds. Two ticks
        // past that, nothing of it should be drawn.
        s.add(new Step(HOLD + 330 + 80 + 12, () -> onServer(mc, p -> lookDown(p, boatX, boatZ))));
        s.add(new Step(HOLD + 330 + 80 + 18, () -> verdict("a stopped boat's wake is gone once its foam has lived out", () ->
                WakeRenderer.lastQuadCount() == 0 ? null : WakeRenderer.lastQuadCount() + " quads still drawn")));
        s.add(new Step(HOLD + 330 + 80 + 20, () -> shoot(mc, "booth-wake-faded")));

        // The second act, through the real path: the player wades east across
        // the shelf under a held key, watched from behind, then climbs into
        // the boat and drives it with the boat's own controls -- a few ticks
        // on the throttle, then flat out, then hard over.
        int act = HOLD + 330 + 80 + 30;
        s.add(new Step(act, () -> {
            scripted = false;
            onServer(mc, p -> {
                p.getAbilities().flying = false;
                p.onUpdateAbilities();
                p.teleportTo(p.serverLevel(), POOL_X0 + 8, GRASS_Y, SHELF_Z0 + 6, -90.0f, 20.0f);
            });
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }));
        s.add(new Step(act + 10, () -> KeyMapping.set(mc.options.keyUp.getKey(), true)));
        s.add(new Step(act + 70, () -> verdict("a wading player leaves a wake of their own", () -> {
            var tracked = WakeTracker.tracked(mc.player.getId());
            if (tracked.isEmpty()) {
                return "the player is not tracked (in water " + mc.player.isInWater() + ", under " + mc.player.isUnderWater() + ")";
            }
            WakeTracker.Tracked t = tracked.get();
            return t.kind() == Craft.Kind.SWIMMER && t.trail().speed() > 0.03 && WakeRenderer.lastQuadCount() > 0
                    ? null : "kind " + t.kind() + " speed " + t.trail().speed() + " quads " + WakeRenderer.lastQuadCount();
        })));
        s.add(new Step(act + 72, () -> shoot(mc, "booth-wade")));
        s.add(new Step(act + 74, () -> {
            KeyMapping.set(mc.options.keyUp.getKey(), false);
            onServer(mc, p -> {
                boat.setPos(p.getX() + 1.5, BOAT_Y, p.getZ() - 8.0);
                boat.setYRot(-90.0f);
                boat.setDeltaMovement(Vec3.ZERO);
                p.teleportTo(p.serverLevel(), boat.getX(), BOAT_Y + 0.5, boat.getZ(), -90.0f, 25.0f);
                p.startRiding(boat, true);
            });
        }));
        s.add(new Step(act + 90, () -> {
            // The player's boat is driven by this client, which ignores the
            // server's word on where it points: the heading is set here.
            var ridden = mc.level.getEntity(boat.getId());
            if (ridden != null) {
                ridden.setYRot(-90.0f);
                ridden.setYBodyRot(-90.0f);
            }
            mc.player.setYRot(-90.0f);
            mc.player.setXRot(28.0f);
            KeyMapping.set(mc.options.keyUp.getKey(), true);
        }));
        s.add(new Step(act + 97, () -> verdict("a few ticks on the throttle is a slow boat with a small wake", () -> {
            var tracked = WakeTracker.tracked(boat.getId());
            if (tracked.isEmpty()) {
                return "the boat is not tracked";
            }
            double speed = tracked.get().trail().speed();
            return mc.player.getVehicle() == mc.level.getEntity(boat.getId()) && speed > 0.05 && speed < 0.3
                    ? null : "riding " + (mc.player.getVehicle() != null) + " speed " + speed;
        })));
        s.add(new Step(act + 98, () -> shoot(mc, "booth-drive-slow")));
        s.add(new Step(act + 150, () -> verdict("flat out the boat is at speed with the whole wake and a bow full of bubbles", () -> {
            double speed = WakeTracker.tracked(boat.getId()).map(t -> t.trail().speed()).orElse(-1.0);
            return speed > 0.3 && WakeRenderer.lastQuadCount() > 400 && WakeRenderer.lastBubbleCount() > 10
                    ? null : "speed " + speed + " quads " + WakeRenderer.lastQuadCount() + " bubbles " + WakeRenderer.lastBubbleCount();
        })));
        s.add(new Step(act + 152, () -> shoot(mc, "booth-drive-fast")));
        s.add(new Step(act + 156, () -> KeyMapping.set(mc.options.keyLeft.getKey(), true)));
        s.add(new Step(act + 196, () -> shoot(mc, "booth-drive-turn")));
        s.add(new Step(act + 200, () -> {
            KeyMapping.set(mc.options.keyUp.getKey(), false);
            KeyMapping.set(mc.options.keyLeft.getKey(), false);
            LOG.info("booth: PASS all checks ran");
            phase = Phase.DONE;
            mc.stop();
        }));
        return s;
    }

    private static void drop(ServerLevel level, double x, double z, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, x, SURFACE + 5.0, z, stack, 0.0, 0.0, 0.0);
        item.setPickUpDelay(400);
        level.addFreshEntity(item);
    }

    private static void onServer(Minecraft mc, Consumer<ServerPlayer> action) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (sp != null) {
                action.accept(sp);
            }
        });
    }

    private static void shoot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
                message -> LOG.info("booth: {}", message.getString()));
    }

    /** Runs {@code check}; null is a pass, anything else the failure's detail. */
    private static void verdict(String what, java.util.function.Supplier<String> check) {
        String detail;
        try {
            detail = check.get();
        } catch (RuntimeException e) {
            detail = e.toString();
        }
        if (detail == null) {
            LOG.info("booth: PASS {}", what);
        } else {
            LOG.error("booth: FAIL {} -- {}", what, detail);
        }
    }
}
