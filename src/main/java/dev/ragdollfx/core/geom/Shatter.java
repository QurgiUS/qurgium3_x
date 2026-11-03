package dev.ragdollfx.core.geom;

import java.util.*;

public final class Shatter {
    public record Cell(float[] min, float[] max, List<ModelSnapshot.Face> quads) {}

    public static List<Cell> split(ModelSnapshot.Cube c, Random r, int lim) {
        float[] lo = {c.minX(), c.minY(), c.minZ()}, hi = {c.maxX(), c.maxY(), c.maxZ()};
        float s = 2 + r.nextFloat() * 2.5f;
        int[] n = new int[3];
        for (int i = 0; i < 3; i++) n[i] = Math.max(1, Math.round((hi[i] - lo[i]) / s));
        while (n[0] * n[1] * n[2] > lim) {
            int k = n[0] >= n[1] && n[0] >= n[2] ? 0 : n[1] >= n[2] ? 1 : 2;
            if (n[k] == 1) break;
            n[k]--;
        }
        float[][] q = new float[3][];
        for (int i = 0; i < 3; i++) q[i] = cut(lo[i], hi[i], n[i], r);
        var out = new ArrayList<Cell>();
        for (int i = 0; i < n[0] * n[1] * n[2]; i++) {
            int a = i / (n[1] * n[2]), b = i / n[2] % n[1], d = i % n[2];
            float[] mn = {q[0][a], q[1][b], q[2][d]}, mx = {q[0][a + 1], q[1][b + 1], q[2][d + 1]};
            out.add(new Cell(mn, mx, faces(c, mn, mx)));
        }
        return out;
    }

    private static float[] cut(float a, float b, int n, Random r) {
        float[] v = new float[n + 1];
        float w = (b - a) / n;
        for (int i = 0; i <= n; i++) v[i] = a + w * i + (i > 0 && i < n ? (r.nextFloat() - .5f) * .6f * w : 0);
        return v;
    }

    private static List<ModelSnapshot.Face> faces(ModelSnapshot.Cube c, float[] mn, float[] mx) {
        var res = new ArrayList<ModelSnapshot.Face>(6);
        for (int f = 0; f < 6; f++) {
            int ax = f >> 1, sg = (f & 1) * 2 - 1;
            var src = c.faces().stream().filter(o -> (ax == 0 ? o.nx() : ax == 1 ? o.ny() : o.nz()) * sg > .5f).findFirst().orElse(null);
            if (src == null) continue;
            int u = (ax + 1) % 3, v = (ax + 2) % 3;
            float[][] p = new float[4][3], t = new float[4][];
            for (int k = 0; k < 4; k++) {
                p[k][ax] = sg > 0 ? mx[ax] : mn[ax];
                p[k][u] = k == 1 || k == 2 ? mx[u] : mn[u];
                p[k][v] = k > 1 ? mx[v] : mn[v];
            }
            if (sg < 0) { var x = p[1]; p[1] = p[3]; p[3] = x; }
            for (int k = 0; k < 4; k++) t[k] = uv(src, p[k]);
            res.add(new ModelSnapshot.Face(p, t, ax == 0 ? sg : 0, ax == 1 ? sg : 0, ax == 2 ? sg : 0));
        }
        return res;
    }

    static float[] uv(ModelSnapshot.Face f, float[] p) {
        float[] o = f.pos()[0], a = f.pos()[1], b = f.pos()[3], e = new float[3], g = new float[3], d = new float[3];
        for (int i = 0; i < 3; i++) { e[i] = a[i] - o[i]; g[i] = b[i] - o[i]; d[i] = p[i] - o[i]; }
        float l = dt(e, e), m = dt(g, g), s = l < 1e-6f ? 0 : cl(dt(d, e) / l), t = m < 1e-6f ? 0 : cl(dt(d, g) / m);
        float[][] w = f.uv();
        return new float[]{w[0][0] + (w[1][0] - w[0][0]) * s + (w[3][0] - w[0][0]) * t, w[0][1] + (w[1][1] - w[0][1]) * s + (w[3][1] - w[0][1]) * t};
    }

    private static float dt(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static float cl(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
