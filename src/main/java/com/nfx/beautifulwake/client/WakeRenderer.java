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
import com.nfx.beautifulwake.domain.BowFoam;
import com.nfx.beautifulwake.domain.Ripple;
import com.nfx.beautifulwake.domain.Sample;
import com.nfx.beautifulwake.domain.Splash;
import com.nfx.beautifulwake.domain.WakeMesh;
import com.nfx.beautifulwake.domain.WakeParams;
import com.nfx.beautifulwake.domain.WakeTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Camera;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the wakes and the splashes, right after the level's translucent
 * blocks (the water among them) so they sit on the water rather than
 * under it. Per craft, in this order:
 * <ol>
 *   <li>the <em>skin</em>: the wake's surface as a mesh with the water's
 *       height at every vertex and its normal, so the bow wave, the trough
 *       and the chevron ridges stand up and take the light like water
 *       does; textured with the pale sheet inside the V and the white
 *       line along its edge;</li>
 *   <li>the <em>lines</em>: the same mesh again with the white lines that
 *       lie along the chevron ridges;</li>
 *   <li>the <em>foam</em>: the same mesh a third time with the bubble-cloud
 *       texture, pinned to the water, as opaque as the churn is there;</li>
 *   <li>the <em>bubbles</em>: the bow's burst, each a billboard facing the
 *       camera, standing up out of the water.</li>
 * </ol>
 * Splash rings are drawn first, under everything. Every vertex takes the
 * water's own light, so a wake in a cave is as dark as the cave. Nothing
 * is buffered between frames; the mesh is rebuilt each frame from the
 * trail, a couple of thousand vertices per craft at most.
 */
public final class WakeRenderer {
    private WakeRenderer() {}

    private static final ResourceLocation SKIN = ResourceLocation.fromNamespaceAndPath(BeautifulWake.MOD_ID, "textures/skin.png");
    private static final ResourceLocation LINES = ResourceLocation.fromNamespaceAndPath(BeautifulWake.MOD_ID, "textures/lines.png");
    private static final ResourceLocation FOAM = ResourceLocation.fromNamespaceAndPath(BeautifulWake.MOD_ID, "textures/foam.png");
    private static final ResourceLocation BUBBLE = ResourceLocation.fromNamespaceAndPath(BeautifulWake.MOD_ID, "textures/bubble.png");
    private static final ResourceLocation RING = ResourceLocation.fromNamespaceAndPath(BeautifulWake.MOD_ID, "textures/ring.png");
    private static final double RING_LIFT = 0.02;
    /** Vertices across the mesh: odd, so a column runs down the track and the chevrons meet in a point. */
    private static final int COLUMNS = 25;
    /**
     * How the skin and line textures map across the V: their width in
     * blocks from {@link WakeMesh#EDGE_INSIDE} inside the edge to
     * {@link #EDGE_OUTSIDE} outside it. The art is drawn to this scale.
     */
    public static final double EDGE_OUTSIDE = 0.5;
    private static final float EDGE_SPAN = (float) (EDGE_OUTSIDE - WakeMesh.EDGE_INSIDE);
    private static int lastQuadCount = 0;
    private static int lastBubbleCount = 0;

    /** effects: returns how many quads the last frame drew across every pass; for the booth's eyes */
    public static int lastQuadCount() {
        return lastQuadCount;
    }

    /** effects: returns how many bow bubbles the last frame drew; for the booth's eyes */
    public static int lastBubbleCount() {
        return lastBubbleCount;
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || (WakeTracker.trackedCount() == 0 && SplashTracker.ripples().isEmpty())) {
            lastQuadCount = 0;
            lastBubbleCount = 0;
            return;
        }
        boolean skin = WakeConfig.SKIN.get();
        boolean lines = WakeConfig.LINES.get();
        boolean foam = WakeConfig.FOAM.get();
        long now = level.getGameTime();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int quads = 0;
        int bubbles = 0;

        RenderType rings = RenderType.entityTranslucent(RING);
        quads += drawRings(buffers.getBuffer(rings), pose, level, now + partial);
        buffers.endBatch(rings);

        List<WakeMesh.Mesh> meshes = new ArrayList<>();
        List<WakeTracker.Tracked> crafts = new ArrayList<>();
        for (Map.Entry<Integer, WakeTracker.Tracked> entry : WakeTracker.all().entrySet()) {
            WakeTracker.Tracked tracked = entry.getValue();
            List<Sample> samples = headed(tracked, level.getEntity(entry.getKey()), partial, now);
            WakeParams p = Craft.params(tracked.kind(), tracked.width());
            Optional<WakeTable> table = WakeTracker.table(p);
            meshes.add(samples.size() < 2 || table.isEmpty() ? new WakeMesh.Mesh(List.of(), COLUMNS)
                    : WakeMesh.build(samples, now + partial, p, COLUMNS, table.get()));
            crafts.add(tracked);
        }

        if (skin) {
            RenderType type = RenderType.entityTranslucent(SKIN);
            VertexConsumer consumer = buffers.getBuffer(type);
            for (WakeMesh.Mesh mesh : meshes) {
                quads += drawMesh(consumer, pose, level, mesh, Pass.SKIN);
            }
            buffers.endBatch(type);
        }
        if (lines) {
            RenderType type = RenderType.entityTranslucent(LINES);
            VertexConsumer consumer = buffers.getBuffer(type);
            for (WakeMesh.Mesh mesh : meshes) {
                quads += drawMesh(consumer, pose, level, mesh, Pass.LINES);
            }
            buffers.endBatch(type);
        }
        if (foam) {
            RenderType type = RenderType.entityTranslucent(FOAM);
            VertexConsumer consumer = buffers.getBuffer(type);
            for (WakeMesh.Mesh mesh : meshes) {
                quads += drawMesh(consumer, pose, level, mesh, Pass.FOAM);
            }
            buffers.endBatch(type);

            RenderType bubbleType = RenderType.entityTranslucent(BUBBLE);
            VertexConsumer bubbleConsumer = buffers.getBuffer(bubbleType);
            for (WakeTracker.Tracked tracked : crafts) {
                bubbles += drawBubbles(bubbleConsumer, pose, level, camera, tracked.foam(), now, partial);
            }
            buffers.endBatch(bubbleType);
        }
        pose.popPose();
        lastQuadCount = quads;
        lastBubbleCount = bubbles;
    }

    /** Which of the three mesh passes is being drawn: they differ in the texture coordinates and the alpha they take. */
    private enum Pass { SKIN, LINES, FOAM }

    /**
     * The trail's samples with the craft's interpolated position this frame
     * on the end, one tick ahead of the newest, so the wake starts at the
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
     * One pass over the mesh: a quad between every pair of neighbouring
     * rows and columns, each vertex with the pass's texture coordinates and
     * alpha, its own normal, and the light on the water at its row.
     */
    private static int drawMesh(VertexConsumer consumer, PoseStack pose, ClientLevel level, WakeMesh.Mesh mesh, Pass pass) {
        List<List<WakeMesh.Vertex>> rows = mesh.rows();
        if (rows.size() < 2) {
            return 0;
        }
        PoseStack.Pose last = pose.last();
        int columns = mesh.columns();
        int[] light = new int[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            WakeMesh.Vertex middle = rows.get(r).get(columns / 2);
            light[r] = LevelRenderer.getLightColor(level, BlockPos.containing(middle.x(), middle.y() + 0.5, middle.z()));
        }
        int drawn = 0;
        for (int r = 0; r + 1 < rows.size(); r++) {
            List<WakeMesh.Vertex> near = rows.get(r);
            List<WakeMesh.Vertex> far = rows.get(r + 1);
            for (int j = 0; j + 1 < columns; j++) {
                WakeMesh.Vertex a = near.get(j);
                WakeMesh.Vertex b = near.get(j + 1);
                WakeMesh.Vertex c = far.get(j + 1);
                WakeMesh.Vertex d = far.get(j);
                if (alpha(a, pass) == 0 && alpha(b, pass) == 0 && alpha(c, pass) == 0 && alpha(d, pass) == 0) {
                    continue;
                }
                vertex(consumer, last, a, pass, light[r]);
                vertex(consumer, last, b, pass, light[r]);
                vertex(consumer, last, c, pass, light[r + 1]);
                vertex(consumer, last, d, pass, light[r + 1]);
                drawn++;
            }
        }
        return drawn;
    }

    private static int alpha(WakeMesh.Vertex v, Pass pass) {
        float a = switch (pass) {
            case SKIN -> v.skin();
            case LINES -> v.lines();
            case FOAM -> v.foam();
        };
        return Math.round(a * 255.0f);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, WakeMesh.Vertex v, Pass pass, int light) {
        float u;
        float tex;
        if (pass == Pass.FOAM) {
            u = v.foamU();
            tex = v.foamV();
        } else {
            u = (float) ((v.edge() - WakeMesh.EDGE_INSIDE) / EDGE_SPAN);
            tex = v.chevron();
        }
        int tone = pass == Pass.SKIN ? Math.min(255, Math.round(255.0f * v.shade())) : 255;
        consumer.addVertex(pose, (float) v.x(), (float) v.y(), (float) v.z())
                .setColor(tone, tone, tone, alpha(v, pass))
                .setUv(u, tex)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, (float) v.nx(), (float) v.ny(), (float) v.nz());
    }

    /**
     * The bow's bubbles: a square each, facing the camera, as big as the
     * bubble and as faint as its age makes it, carried on from its last
     * tick by its velocity.
     */
    private static int drawBubbles(VertexConsumer consumer, PoseStack pose, ClientLevel level, Camera camera, BowFoam foam,
                                   long now, float partial) {
        PoseStack.Pose last = pose.last();
        Quaternionf facing = camera.rotation();
        Vector3f right = new Vector3f(1, 0, 0).rotate(facing);
        Vector3f up = new Vector3f(0, 1, 0).rotate(facing);
        double frame = now + partial;
        int drawn = 0;
        for (BowFoam.Bubble b : foam.bubbles()) {
            int alpha = (int) Math.round(b.alpha(frame) * 255.0);
            if (alpha <= 0) {
                continue;
            }
            double t = b.settled() ? 0.0 : partial;
            float x = (float) (b.x() + b.vx() * t);
            float y = (float) (b.y() + b.vy() * t);
            float z = (float) (b.z() + b.vz() * t);
            float half = (float) (b.size() / 2.0);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y + 0.3, z));
            float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
            for (float[] c : corners) {
                float cx = x + half * (c[0] * right.x + c[1] * up.x);
                float cy = y + half + half * (c[0] * right.y + c[1] * up.y);
                float cz = z + half * (c[0] * right.z + c[1] * up.z);
                consumer.addVertex(last, cx, cy, cz)
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
}
