package dev.ragdollfx.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ragdollfx.core.RagdollBuilder;
import dev.ragdollfx.core.geom.ModelSnapshot;
import dev.ragdollfx.mc.mixin.CubeAccessor;
import dev.ragdollfx.mc.mixin.ModelPartAccessor;
import java.util.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Matrix4f;

public final class ModelCapture {
    public final List<ModelPart> parts = new ArrayList<>();
    public final List<PartPose> poses = new ArrayList<>();
    public final List<Matrix4f> parentWorld = new ArrayList<>(), ownWorld = new ArrayList<>();
    public final List<ModelSnapshot.Part> snap = new ArrayList<>();

    public static ModelCapture capture(ModelPart rt, Matrix4f m) {
        var c = new ModelCapture();
        var s = new PoseStack();
        s.last().pose().set(m);
        c.go(rt, "root", -1, s);
        return c;
    }

    private void go(ModelPart p, String n, int up, PoseStack s) {
        // If the part is invisible, we only capture it if it's an overlay/clothing layer
        // (like villager robe/hat, jackets, sleeves).
        // Alternative mob state parts (armadillo's "cube", unequipped llama chests, etc.)
        // are invisible when not in use. Capturing them when invisible causes duplicate/ghost
        // bodies (e.g. armadillo rolled-up cube fused with unrolled body!).
        if (!p.visible && !RagdollBuilder.isOverlay(n)) return;

        int i = parts.size();
        var a = new Matrix4f(s.last().pose());
        s.pushPose();
        p.translateAndRotate(s);
        var b = new Matrix4f(s.last().pose());
        parts.add(p);
        poses.add(p.storePose());
        parentWorld.add(a);
        ownWorld.add(b);
        var acc = (ModelPartAccessor) (Object) p;
        snap.add(new ModelSnapshot.Part(i, up, n, arr(b),
                p.skipDraw ? List.of() : acc.ragdollfx$cubes().stream().map(ModelCapture::cb).toList()));
        acc.ragdollfx$children().forEach((k, v) -> go(v, k, i, s));
        s.popPose();
    }

    private static ModelSnapshot.Cube cb(ModelPart.Cube c) {
        var fs = new ArrayList<ModelSnapshot.Face>(6);
        for (var q : ((CubeAccessor) (Object) c).ragdollfx$polygons()) {
            if (q == null) continue;
            float[][] p = new float[4][], t = new float[4][];
            for (int i = 0; i < 4; i++) {
                var v = q.vertices()[i];
                p[i] = new float[]{v.x(), v.y(), v.z()};
                t[i] = new float[]{v.u(), v.v()};
            }
            fs.add(new ModelSnapshot.Face(p, t, q.normal().x(), q.normal().y(), q.normal().z()));
        }
        return new ModelSnapshot.Cube(c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ, fs);
    }

    public int[][] hideLists() {
        int n = snap.size();
        boolean[] ck = new boolean[n], ov = new boolean[n];
        // overlays pull their whole subtree with them: the villager hat brim lives under "hat"
        for (var p : snap) ov[p.index()] = RagdollBuilder.isOverlay(p.name()) || p.parent() >= 0 && ov[p.parent()];
        for (var p : snap) ck[p.index()] = !p.cubes().isEmpty() && !ov[p.index()];
        int[][] o = new int[n][];
        for (int i = 0; i < n; i++) {
            int x = i;
            o[i] = snap.stream().filter(q -> q.index() != x && ck[q.index()] && under(q, x)).mapToInt(ModelSnapshot.Part::index).toArray();
        }
        return o;
    }

    private boolean under(ModelSnapshot.Part q, int a) {
        for (int k = q.parent(); k >= 0; k = snap.get(k).parent()) if (k == a) return true;
        return false;
    }

    static double[] arr(Matrix4f m) {
        float[] f = m.get(new float[16]);
        double[] d = new double[16];
        for (int i = 0; i < 16; i++) d[i] = f[i];
        return d;
    }
}
