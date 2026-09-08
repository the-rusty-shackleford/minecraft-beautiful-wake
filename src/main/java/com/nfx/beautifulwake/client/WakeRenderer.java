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

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nfx.beautifulwake.BeautifulWake;
import com.nfx.beautifulwake.WakeConfig;
import com.nfx.beautifulwake.domain.Ripple;
import com.nfx.beautifulwake.domain.Sample;
import com.nfx.beautifulwake.domain.Splash;
import com.nfx.beautifulwake.domain.WakeGeometry;
import com.nfx.beautifulwake.domain.WakeGeometry.Params;
import com.nfx.beautifulwake.domain.WakeGeometry.Quad;
import com.nfx.beautifulwake.domain.WakeGeometry.Vertex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the wakes and the splashes: the foam strip and the arms of every
 * followed craft, and a ring for every recent splash, as translucent
 * textured quads on the water surface, right after the level's translucent
 * blocks (the water among them) so they sit on the water rather than under
 * it.
 *
 * <p>The newest point of each trail is the craft's position this frame,
 * interpolated, so the strip meets the hull without a tick's gap. The
 * quads take the water's own light, so a wake in a cave is as dark as the
 * cave. Nothing is buffered between frames; the geometry is a few dozen
 * quads per craft and is rebuilt each frame from the trail.
 */
public final class WakeRenderer {
    private WakeRenderer() {}

    private static final ResourceLocation FOAM = ResourceLocation.fromNamespaceAndPath(BeautifulWake.MOD_ID, "textures/foam.png");
    private static final ResourceLocation RING = ResourceLocation.fromNamespaceAndPath(BeautifulWake.MOD_ID, "textures/ring.png");
    private static final double RING_LIFT = 0.02;
    private static int lastQuadCount = 0;

    /** effects: returns how many quads the last frame drew; for the booth's eyes */
    public static int lastQuadCount() {
        return lastQuadCount;
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || (WakeTracker.trackedCount() == 0 && SplashTracker.ripples().isEmpty())) {
            lastQuadCount = 0;
            return;
        }
        boolean foam = WakeConfig.FOAM.get();
        boolean arms = WakeConfig.ARMS.get();
        long now = level.getGameTime();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int quads = 0;

        RenderType rings = RenderType.entityTranslucent(RING);
        quads += drawRings(buffers.getBuffer(rings), pose, level, now + partial);
        buffers.endBatch(rings);

        RenderType type = RenderType.entityTranslucent(FOAM);
        VertexConsumer consumer = buffers.getBuffer(type);
        for (Map.Entry<Integer, WakeTracker.Tracked> entry : WakeTracker.all().entrySet()) {
            if (!foam && !arms) {
                break;
            }
            WakeTracker.Tracked tracked = entry.getValue();
            List<Sample> samples = headed(tracked, level.getEntity(entry.getKey()), partial, now);
            if (samples.size() < 2) {
                continue;
            }
            Params p = Craft.params(tracked.kind(), tracked.width());
            if (foam) {
                quads += draw(consumer, pose, level, WakeGeometry.foamStrip(samples, now, p));
            }
            if (arms && tracked.kind() == Craft.Kind.WATERCRAFT) {
                quads += draw(consumer, pose, level, WakeGeometry.arms(samples, now, p));
            }
        }
        buffers.endBatch(type);
        pose.popPose();
        lastQuadCount = quads;
    }

    /**
     * The trail's samples with the craft's interpolated position this frame
     * on the end, one tick ahead of the newest, so the foam starts at the
     * hull and not where the hull was at the last tick.
     */
    private static List<Sample> headed(WakeTracker.Tracked tracked, Entity entity, float partial, long now) {
        List<Sample> samples = new ArrayList<>(tracked.trail().samples());
        if (entity != null && !samples.isEmpty()) {
            Sample last = samples.get(samples.size() - 1);
            Vec3 at = entity.getPosition(partial);
            Sample head = new Sample(at.x, last.surfaceY(), at.z, now + 1);
            if (last.distanceTo(head) > 1e-4) {
                samples.add(head);
            }
        }
        return samples;
    }

    /**
     * The splash rings: one square each, the ring texture across it, as wide
     * as twice the ring's radius this frame and as faint as its age makes it.
     */
    private static int drawRings(VertexConsumer consumer, PoseStack pose, ClientLevel level, double now) {
        PoseStack.Pose last = pose.last();
        int drawn = 0;
        for (Ripple ripple : SplashTracker.ripples()) {
            long age = Math.max(0L, (long) Math.floor(now - ripple.born()));
            if (age > SplashTracker.RING_LIFE_TICKS) {
                continue;
            }
            float radius = (float) Splash.ringRadius(ripple.strength(), age, SplashTracker.RING_LIFE_TICKS);
            int alpha = Math.round((float) Splash.ringAlpha(ripple.strength(), age, SplashTracker.RING_LIFE_TICKS) * 255.0f);
            if (alpha == 0) {
                continue;
            }
            float x = (float) ripple.x();
            float y = (float) (ripple.surfaceY() + RING_LIFT);
            float z = (float) ripple.z();
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(ripple.x(), ripple.surfaceY() + 0.5, ripple.z()));
            float[][] corners = {{-radius, -radius, 0, 0}, {radius, -radius, 1, 0}, {radius, radius, 1, 1}, {-radius, radius, 0, 1}};
            for (float[] c : corners) {
                consumer.addVertex(last, x + c[0], y, z + c[1])
                        .setColor(255, 255, 255, alpha)
                        .setUv(c[2], c[3])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(last, 0.0f, 1.0f, 0.0f);
            }
            drawn++;
        }
        return drawn;
    }

    private static int draw(VertexConsumer consumer, PoseStack pose, ClientLevel level, List<Quad> quads) {
        PoseStack.Pose last = pose.last();
        for (Quad quad : quads) {
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(quad.a().x(), quad.a().y() + 0.5, quad.a().z()));
            for (Vertex v : new Vertex[] {quad.a(), quad.b(), quad.c(), quad.d()}) {
                consumer.addVertex(last, (float) v.x(), (float) v.y(), (float) v.z())
                        .setColor(255, 255, 255, Math.round(v.alpha() * 255.0f))
                        .setUv(v.u(), v.v())
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(last, 0.0f, 1.0f, 0.0f);
            }
        }
        return quads.size();
    }
}
