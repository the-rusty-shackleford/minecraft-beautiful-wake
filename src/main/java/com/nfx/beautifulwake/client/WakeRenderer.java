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
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
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
 * Splash rings are drawn first, under everything, and their foam flecks
 * over them. Every vertex takes the
 * water's own light, so a wake in a cave is as dark as the cave. Nothing
 * is buffered between frames; the mesh is rebuilt each frame from the
 * trail, a couple of thousand vertices per craft at most.
 */
public final class WakeRenderer {
    private WakeRenderer() {}

    /**
     * Every texture is drawn through the game's particle shader, on a render
     * type of our own: position, texture, colour and light, translucent, no
     * depth write, back faces culled. Not the entity translucent type, on
     * purpose. Shader packs make assumptions about entity geometry that a
     * sheet on the water breaks: Complementary flips the normal of a back
     * face before lighting it, and pushes any vertex whose colour alpha is
     * under a half behind whatever is beneath it -- which put a swimmer's
     * whole faded wake under the water and made a boat's far V vanish.
     * Particles are the one translucent thing every pack draws at every
     * alpha, lit by the water's light, with nothing to flip.
     *
     * <p>The churn is animated by frames: two, the bubbles nudged differently,
     * shown turn and turn about every {@link #FRAME_TICKS} ticks so it
     * boils; the splash flecks have three, cycled per splash. The edge line
     * and the chevron lines are still: a straight line that stepped between
     * frames read as a vibration, not as foam.
     */
    private static final RenderType SKIN = sheet("skin");
    private static final RenderType LINES = sheet("lines");
    private static final RenderType[] FOAM = frames("foam", 2);
    private static final RenderType[] FLECKS = frames("flecks", 3);
    private static final RenderType BUBBLE = sheet("bubble");
    private static final RenderType RING = sheet("ring");
    /**
     * The white of the foam: a shade under pure white. Complementary's
     * particle program takes pure white at a middling alpha for falling snow
     * and thins it.
     */
    private static final int WHITE = 254;
    /** Ticks each frame of the wake's foam is shown for. */
    private static final int FRAME_TICKS = 4;
    /** Ticks each frame of a splash's flecks is shown for. */
    private static final int FLECK_FRAME_TICKS = 3;
    /** Within this many blocks of the camera a bubble starts to shrink and fade, and at {@link #BUBBLE_GONE} it is gone. */
    private static final double BUBBLE_NEAR = 1.8;
    private static final double BUBBLE_GONE = 0.5;
    /** In first person, the viewer's own sheet, lines and foam start to fade this many blocks from the eye, and at {@link #EYE_GONE} are gone. */
    private static final double EYE_NEAR = 3.0;
    private static final double EYE_GONE = 1.2;
    private static final double RING_LIFT = 0.02;
    /** The foam sits on the ring. */
    private static final double FLECK_LIFT = 0.025;
    /** A splash this strong gets a second sprinkling of flecks, turned a quarter, for twice the foam. */
    private static final double DOUBLE_FLECKS_FROM = 1.6;
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
    /** The camera's position this frame: every vertex is emitted relative to it. */
    private static Vec3 origin = Vec3.ZERO;
    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(BeautifulWake.MOD_ID, "textures/" + name + ".png");
    }

    /** A render type for one of the wake's textures, as described on the class. */
    private static RenderType sheet(String name) {
        return RenderType.create("beautifulwake_" + name, DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS, 4096, false, true,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getParticleShader))
                        .setTextureState(new RenderStateShard.TextureStateShard(texture(name), false, false))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setCullState(RenderStateShard.CULL)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setOutputState(RenderStateShard.PARTICLES_TARGET)
                        .createCompositeState(false));
    }

    private static RenderType[] frames(String name, int count) {
        RenderType[] types = new RenderType[count];
        for (int i = 0; i < count; i++) {
            types[i] = sheet(name + "_" + i);
        }
        return types;
    }

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
        long now = WakeTracker.now();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        // Vertices are taken to the camera in doubles and only then cast to
        // float: cast in world coordinates, far from the origin, they would
        // shake by the float's grain.
        pose.pushPose();
        origin = cam;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int quads = 0;
        int bubbles = 0;

        quads += drawRings(buffers, pose, level, now + partial);
        int frame = (int) ((now / FRAME_TICKS) % 2);

        List<WakeMesh.Mesh> meshes = new ArrayList<>();
        List<WakeTracker.Tracked> crafts = new ArrayList<>();
        List<Boolean> ownInFirstPerson = new ArrayList<>();
        Entity viewer = camera.getEntity();
        for (Map.Entry<Integer, WakeTracker.Tracked> entry : WakeTracker.all().entrySet()) {
            WakeTracker.Tracked tracked = entry.getValue();
            Entity entity = level.getEntity(entry.getKey());
            // The viewer's own wake in first person: the eye is at the water
            // line, and the sheet, lines and foam right in front of it would
            // fill the view; they fade out near the eye instead.
            ownInFirstPerson.add(entity != null && !camera.isDetached()
                    && (entity == viewer || (viewer != null && viewer.getVehicle() == entity)));
            List<Sample> samples = headed(tracked, entity, partial, now);
            WakeParams p = Craft.params(tracked.kind(), tracked.width());
            Optional<WakeTable> table = WakeTracker.table(p);
            meshes.add(samples.size() < 2 || table.isEmpty() ? new WakeMesh.Mesh(List.of(), COLUMNS)
                    : WakeMesh.build(samples, now + partial, p, COLUMNS, table.get()));
            crafts.add(tracked);
        }

        if (skin) {
            VertexConsumer consumer = buffers.getBuffer(SKIN);
            for (int i = 0; i < meshes.size(); i++) {
                quads += drawMesh(consumer, pose, level, meshes.get(i), Pass.SKIN, ownInFirstPerson.get(i));
            }
            buffers.endBatch(SKIN);
        }
        if (lines) {
            VertexConsumer consumer = buffers.getBuffer(LINES);
            for (int i = 0; i < meshes.size(); i++) {
                quads += drawMesh(consumer, pose, level, meshes.get(i), Pass.LINES, ownInFirstPerson.get(i));
            }
            buffers.endBatch(LINES);
        }
        if (foam) {
            VertexConsumer consumer = buffers.getBuffer(FOAM[frame]);
            for (int i = 0; i < meshes.size(); i++) {
                quads += drawMesh(consumer, pose, level, meshes.get(i), Pass.FOAM, ownInFirstPerson.get(i));
            }
            buffers.endBatch(FOAM[frame]);

            VertexConsumer bubbleConsumer = buffers.getBuffer(BUBBLE);
            for (WakeTracker.Tracked tracked : crafts) {
                bubbles += drawBubbles(bubbleConsumer, pose, level, camera, tracked.foam(), now, partial);
            }
            buffers.endBatch(BUBBLE);
        }
        pose.popPose();
        origin = Vec3.ZERO;
        lastQuadCount = quads;
        lastBubbleCount = bubbles;
    }

    /** Which of the three mesh passes is being drawn: they differ in the texture coordinates and the alpha they take. */
    private enum Pass { SKIN, LINES, FOAM }

    /**
     * The trail's samples with the craft's interpolated position this frame
     * on the end, one tick ahead of the newest, so the wake starts at the
     * hull and not where the hull was at the last tick -- but only while the
     * craft is on the water making wake this tick. A diver's or a flier's
     * trail stays where it was left and fades there; it is not dragged
     * along the surface under them.
     */
    private static List<Sample> headed(WakeTracker.Tracked tracked, Entity entity, float partial, long now) {
        List<Sample> samples = new ArrayList<>(tracked.trail().samples());
        if (entity != null && !samples.isEmpty() && tracked.sampledAt() == now) {
            Sample last = samples.get(samples.size() - 1);
            Vec3 at = entity.getPosition(partial);
            Sample head = new Sample(at.x, last.surfaceY(), at.z, now + 1);
            if (last.distanceTo(head) > 1e-4) {
                samples.add(head.atArc(last.arc() + last.distanceTo(head)));
            }
        }
        return samples;
    }

    /**
     * One pass over the mesh: a quad between every pair of neighbouring
     * rows and columns, each vertex with the pass's texture coordinates and
     * alpha, its own normal, and the light on the water at its row. Every
     * quad is wound counter-clockwise seen from above.
     */
    private static int drawMesh(VertexConsumer consumer, PoseStack pose, ClientLevel level, WakeMesh.Mesh mesh, Pass pass, boolean fadeNearEye) {
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
                // Wound counter-clockwise seen from above, so the face is
                // front-facing from above: a shader pack that flips the
                // normal of a back face (Complementary does) would otherwise
                // light the whole wake from below and draw it dark.
                vertex(consumer, last, a, pass, light[r], fadeNearEye);
                vertex(consumer, last, d, pass, light[r + 1], fadeNearEye);
                vertex(consumer, last, c, pass, light[r + 1], fadeNearEye);
                vertex(consumer, last, b, pass, light[r], fadeNearEye);
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

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, WakeMesh.Vertex v, Pass pass, int light, boolean fadeNearEye) {
        float u;
        float tex;
        if (pass == Pass.FOAM) {
            u = v.foamU();
            tex = v.foamV();
        } else {
            u = (float) ((v.edge() - WakeMesh.EDGE_INSIDE) / EDGE_SPAN);
            tex = v.chevron();
        }
        int tone = pass == Pass.SKIN ? Math.min(WHITE, Math.round(WHITE * v.shade())) : WHITE;
        int alpha = alpha(v, pass);
        double dx = v.x() - origin.x;
        double dy = v.y() - origin.y;
        double dz = v.z() - origin.z;
        if (fadeNearEye && alpha > 0) {
            double near = Math.min(1.0, Math.max(0.0, (Math.sqrt(dx * dx + dy * dy + dz * dz) - EYE_GONE) / (EYE_NEAR - EYE_GONE)));
            alpha = (int) Math.round(alpha * near);
        }
        consumer.addVertex(pose, (float) dx, (float) dy, (float) dz)
                .setUv(u, tex)
                .setColor(tone, tone, tone, alpha)
                .setLight(light);
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
        Vec3 eye = camera.getPosition();
        double frame = now + partial;
        int drawn = 0;
        for (BowFoam.Bubble b : foam.bubbles()) {
            double t = b.settled() ? 0.0 : partial;
            double wx = b.x() + b.vx() * t;
            double wy = b.y() + b.vy() * t;
            double wz = b.z() + b.vz() * t;
            float x = (float) (wx - origin.x);
            float y = (float) (wy - origin.y);
            float z = (float) (wz - origin.z);
            // A bubble right by the eye -- the bow's, in first person -- would
            // fill a hand's breadth of screen: it shrinks and fades out over
            // the last block and a half instead.
            double near = Math.min(1.0, Math.max(0.0, (eye.distanceTo(new Vec3(wx, wy, wz)) - BUBBLE_GONE) / (BUBBLE_NEAR - BUBBLE_GONE)));
            int alpha = (int) Math.round(b.alpha(frame) * near * 255.0);
            if (alpha <= 0) {
                continue;
            }
            float half = (float) (b.size() * near / 2.0);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(wx, wy + 0.3, wz));
            float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
            for (float[] c : corners) {
                float cx = x + half * (c[0] * right.x + c[1] * up.x);
                float cy = y + half + half * (c[0] * right.y + c[1] * up.y);
                float cz = z + half * (c[0] * right.z + c[1] * up.z);
                consumer.addVertex(last, cx, cy, cz)
                        .setUv(c[2], c[3])
                        .setColor(WHITE, WHITE, WHITE, alpha)
                        .setLight(light);
            }
            drawn++;
        }
        return drawn;
    }

    /**
     * The splash rings: one square each, the ring texture across it, as wide
     * as twice the ring's radius this frame and as faint as its age makes it,
     * then the foam flecks over each ring that has any, as opaque as the
     * splash's foam. The flecks cycle through their frames every few ticks,
     * each splash starting on its own frame and turned its own quarter, so
     * no two splashes foam alike and none sits still; a heavy splash gets a
     * second square turned another quarter, for twice the foam.
     */
    private static int drawRings(MultiBufferSource.BufferSource buffers, PoseStack pose, ClientLevel level, double now) {
        PoseStack.Pose last = pose.last();
        int drawn = 0;
        VertexConsumer rings = buffers.getBuffer(RING);
        for (Ripple ripple : SplashTracker.ripples()) {
            double age = Math.max(0.0, now - ripple.born());
            if (age > SplashTracker.RING_LIFE_TICKS) {
                continue;
            }
            int alpha = (int) Math.round(Splash.ringAlpha(ripple.strength(), age, SplashTracker.RING_LIFE_TICKS) * 255.0);
            if (alpha == 0) {
                continue;
            }
            float radius = (float) Splash.ringRadius(ripple.strength(), age, SplashTracker.RING_LIFE_TICKS);
            drawn += square(rings, last, ripple, RING_LIFT, radius, alpha, light(level, ripple), 0);
        }
        buffers.endBatch(RING);
        for (int frame = 0; frame < FLECKS.length; frame++) {
            VertexConsumer flecks = buffers.getBuffer(FLECKS[frame]);
            for (Ripple ripple : SplashTracker.ripples()) {
                double age = Math.max(0.0, now - ripple.born());
                if (age > SplashTracker.RING_LIFE_TICKS || ((long) Math.floor(age) / FLECK_FRAME_TICKS + ripple.born()) % FLECKS.length != frame) {
                    continue;
                }
                int alpha = (int) Math.round(Splash.foamAlpha(ripple.strength(), age, SplashTracker.RING_LIFE_TICKS) * 255.0);
                if (alpha == 0) {
                    continue;
                }
                float radius = (float) Splash.ringRadius(ripple.strength(), age, SplashTracker.RING_LIFE_TICKS);
                int turn = (int) (ripple.born() % 4);
                int light = light(level, ripple);
                drawn += square(flecks, last, ripple, FLECK_LIFT, radius, alpha, light, turn);
                if (ripple.strength() >= DOUBLE_FLECKS_FROM) {
                    drawn += square(flecks, last, ripple, FLECK_LIFT, radius, alpha, light, turn + 1);
                }
            }
            buffers.endBatch(FLECKS[frame]);
        }
        return drawn;
    }

    private static int light(ClientLevel level, Ripple ripple) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(ripple.x(), ripple.surfaceY() + 0.5, ripple.z()));
    }

    /** One square on the water over {@code ripple}, {@code lift} above it, {@code radius} to each side, its texture turned {@code quarterTurns}. */
    private static int square(VertexConsumer consumer, PoseStack.Pose pose, Ripple ripple, double lift, float radius, int alpha, int light, int quarterTurns) {
        float x = (float) (ripple.x() - origin.x);
        float y = (float) (ripple.surfaceY() + lift - origin.y);
        float z = (float) (ripple.z() - origin.z);
        // Counter-clockwise seen from above, as the mesh's quads are, for the same reason.
        float[][] corners = {{-radius, -radius, 0, 0}, {-radius, radius, 0, 1}, {radius, radius, 1, 1}, {radius, -radius, 1, 0}};
        for (int i = 0; i < 4; i++) {
            float[] c = corners[i];
            float[] uv = corners[(i + quarterTurns) % 4];
            consumer.addVertex(pose, x + c[0], y, z + c[1])
                    .setUv(uv[2], uv[3])
                    .setColor(WHITE, WHITE, WHITE, alpha)
                    .setLight(light);
        }
        return 1;
    }
}
