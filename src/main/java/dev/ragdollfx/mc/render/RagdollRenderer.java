package dev.ragdollfx.mc.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ragdollfx.core.RagdollBuilder;
import dev.ragdollfx.core.config.RagdollConfig;
import dev.ragdollfx.core.geom.RenderTag;
import dev.ragdollfx.mc.Corpse;
import dev.ragdollfx.mc.McWorldCollider;
import dev.ragdollfx.mc.RagdollManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class RagdollRenderer {
    private static long t0;
    private static final int OV = OverlayTexture.NO_OVERLAY;

    public static void render(SubmitNodeCollector out, Matrix4f vb) {
        var mc = Minecraft.getInstance();
        long t = System.nanoTime(), was = t0;
        t0 = t;
        if (mc.level == null || RagdollManager.active().isEmpty()) return;
        double dt = mc.isPaused() ? 0 : Math.min(.1, (t - was) / 1e9);
        var cm = mc.gameRenderer.mainCamera().position();
        int life = RagdollConfig.get().corpseLifetimeSeconds;

        for (Corpse c : RagdollManager.active()) {
            c.ageSeconds += dt;
            double a = c.ragdoll.advance(dt, new McWorldCollider(mc.level, c.ox, c.oy, c.oz));
            double sk = Math.max(0, c.ageSeconds - life + 1.5) / 1.5 * Math.max(.6, c.height);
            var v = new Matrix4f(vb).translate((float) (c.ox - cm.x), (float) (c.oy - cm.y - sk), (float) (c.oz - cm.z));
            for (var b : c.ragdoll.bodies) {
                double[] d = RagdollBuilder.delta(b, a);
                float[] f = new float[16];
                for (int i = 0; i < 16; i++) f[i] = (float) d[i];
                var g = new Matrix4f().set(f);
                var p = b.center(true, a);
                int lt = LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(c.ox + p.x, c.oy + p.y + .1, c.oz + p.z));
                switch (b.renderTag) {
                    case RenderTag.WholePart w -> {
                        int pi = w.partIndex();
                        // drawn later by the feature dispatcher: swap pose/visibility inside the callback
                        out.submitCustomGeometry(ps(v, g, c.parentWorld[pi]), c.renderType, (pose, vc) -> part(c, pi, pose, vc, lt));
                    }
                    case RenderTag.Shard s ->
                        out.submitCustomGeometry(ps(v, g, c.ownWorld[s.partIndex()]), c.renderType, (pose, vc) -> shard(s, pose, vc, lt));
                    default -> {}
                }
            }
            // parts welded to a body (villager crossed arms) ride that body's transform untouched
            for (var w : c.ragdoll.welds) {
                var hb = c.ragdoll.bodies.get(w.body());
                double[] d = RagdollBuilder.delta(hb, a);
                float[] f = new float[16];
                for (int i = 0; i < 16; i++) f[i] = (float) d[i];
                var g = new Matrix4f().set(f);
                var p = hb.center(true, a);
                int lt = LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(c.ox + p.x, c.oy + p.y + .1, c.oz + p.z));
                int pi = w.part();
                out.submitCustomGeometry(ps(v, g, c.parentWorld[pi]), c.renderType, (pose, vc) -> part(c, pi, pose, vc, lt));
            }
        }
    }

    private static PoseStack ps(Matrix4f v, Matrix4f g, Matrix4f m) {
        var s = new PoseStack();
        s.last().pose().set(v).mul(g).mul(m);
        s.last().normal().set(new Matrix3f(s.last().pose()).normal());
        return s;
    }

    private static void part(Corpse c, int i, PoseStack.Pose pose, VertexConsumer vc, int lt) {
        int n = c.parts.size();
        PartPose[] sv = new PartPose[n];
        boolean[] visWas = new boolean[n];
        for (int k = 0; k < n; k++) {
            var mp = c.parts.get(k);
            sv[k] = mp.storePose();
            visWas[k] = mp.visible;
            mp.loadPose(c.poses[k]);
            // Restore visibility: keep overlay parts visible if they were captured,
            // while preserving false for parts that were not captured or meant to be hidden.
            mp.visible = true;
        }
        int[] h = c.hideWhenDrawing[i];
        for (int k = 0; k < h.length; k++) c.parts.get(h[k]).visible = false;
        try {
            var s = new PoseStack();
            s.last().pose().set(pose.pose());
            s.last().normal().set(pose.normal());
            c.parts.get(i).render(s, vc, lt, OV);
        } finally {
            for (int k = 0; k < n; k++) {
                var mp = c.parts.get(k);
                mp.visible = visWas[k];
                mp.loadPose(sv[k]);
            }
        }
    }

    private static void shard(RenderTag.Shard s, PoseStack.Pose p, VertexConsumer vc, int lt) {
        for (var f : s.quads())
            for (int i = 0; i < 4; i++)
                vc.addVertex(p, f.pos()[i][0] / 16f, f.pos()[i][1] / 16f, f.pos()[i][2] / 16f).setColor(-1)
                  .setUv(f.uv()[i][0], f.uv()[i][1]).setOverlay(OV).setLight(lt).setNormal(p, f.nx(), f.ny(), f.nz());
    }
}
