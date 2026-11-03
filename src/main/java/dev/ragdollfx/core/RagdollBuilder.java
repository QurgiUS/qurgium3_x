package dev.ragdollfx.core;

import dev.ragdollfx.core.fracture.*;
import dev.ragdollfx.core.geom.*;
import dev.ragdollfx.core.math.Vec3d;
import dev.ragdollfx.core.rig.Rig;
import dev.ragdollfx.core.physics.*;
import java.util.*;

public final class RagdollBuilder {
    private static final double PX = 1 / 16.0, MH = .03;
    private static final String[] OV = {"hat", "jacket", "sleeve", "pants", "robe", "mole", "overlay"};
    /** Parts drawn rigidly with a body instead of being simulated (decorations, fins, chests...). */
    private static final String[] WELD = {
        "arms", "nose", "mole", "snout", "beak", "tendril", "horn", "left_horn", "right_horn",
        "crystal", "claw", "spike", "tooth", "teeth",
        "mane", "left_ear", "right_ear", "ear", "ears",
        "upper_body",
        "tail_tip", "wing_tip", "right_wing_tip", "left_wing_tip",
        "fin", "left_fin", "right_fin", "top_fin", "tail_fin", "back_fin",
        "chest", "left_chest", "right_chest",
        "cube", "shell",
        "saddle", "bridle", "reins", "harness", "armor", "_wire", "bag"
    };

    public static boolean isWelded(String n) {
        var s = n.toLowerCase(Locale.ROOT);
        for (var w : WELD) if (s.equals(w) || s.endsWith(w) || s.contains(w)) return true;
        return false;
    }

    private static boolean hasCubedChild(int idx, List<ModelSnapshot.Part> ps, List<ModelSnapshot.Part> ch) {
        for (var q : ch) for (int i = q.parent(); i >= 0; i = ps.get(i).parent()) if (i == idx) return true;
        return false;
    }

    public record Input(ModelSnapshot model, String modelKey, boolean fracture, boolean blood, long seed, double vx, double vy, double vz, double violence, int shardBudget) {}

    public static boolean isOverlay(String n) {
        var s = n.toLowerCase(Locale.ROOT);
        for (var p : OV) if (s.startsWith(p) || s.endsWith(p)) return true;
        return false;
    }

    public static Ragdoll build(Input in) {
        var r = new Random(in.seed);
        var rd = new Ragdoll();
        var ps = in.model.parts;
        var base = ps.stream().filter(p -> !p.cubes().isEmpty()).toList();
        int cap = Rig.cap(in.modelKey);
        final var ch = (cap > 0 && base.size() > cap) ? base.subList(0, cap) : base;
        if (ch.isEmpty()) return rd;

        var real = ch.stream().filter(p -> !isOverlay(p.name())).toList();
        final var pool = real.isEmpty() ? ch : real;
        var t = pool.stream().filter(p -> FracturePlanner.classify(p.name()) == FracturePlanner.Kind.TORSO).findFirst()
                .orElseGet(() -> Collections.max(pool, Comparator.comparingDouble(RagdollBuilder::vol)));

        var byIdx = new HashMap<Integer, ModelSnapshot.Part>();
        for (var p : ch) byIdx.put(p.index(), p);
        int bodyIdx = -1;
        for (var p : ch) if (bodyIdx < 0 && "body".equals(p.name())) bodyIdx = p.index();
        var skip = new HashSet<Integer>();
        var hostOf = new HashMap<Integer, Integer>();

        // layers with the same part names (wool, armor, saddle, slime shell, golem cracks, spider eyes):
        // drawn with the main copy; the torso always wins as the main copy
        var firstByName = new HashMap<String, Integer>();
        firstByName.put(t.name().toLowerCase(Locale.ROOT), t.index());
        for (var p : ch) {
            Integer first = firstByName.putIfAbsent(p.name().toLowerCase(Locale.ROOT), p.index());
            if (first != null && first != p.index()) { skip.add(p.index()); hostOf.put(p.index(), first); }
        }

        double total = ch.stream().filter(p -> !skip.contains(p.index())).mapToDouble(RagdollBuilder::vol).sum();
        for (var p : ch) {
            if (p == t || skip.contains(p.index())) continue;
            boolean overlay = isOverlay(p.name()), named = isWelded(p.name());
            boolean weld = overlay || named || vol(p) < total * .05 && !hasCubedChild(p.index(), ps, ch);
            if (!weld) continue;
            int h = -1;
            if (!overlay) {
                String target = jointTarget(in.modelKey, p.name(), ch);
                if (target != null) {
                    Integer ti = indexOf(ch, target);
                    if (ti != null && ti != p.index()) h = ti;
                }
            }
            if (h < 0) for (int i = p.parent(); i >= 0; i = ps.get(i).parent()) if (byIdx.containsKey(i)) { h = i; break; }
            if (h < 0 && "arms".equals(p.name())) h = bodyIdx;
            if (h < 0 && (overlay || named)) h = t.index();
            if (h >= 0 && h != p.index()) { skip.add(p.index()); hostOf.put(p.index(), h); }
        }
        // a weld whose host is itself welded must ride on the final real body, otherwise it is dropped
        for (var key : new ArrayList<>(hostOf.keySet())) {
            int h = hostOf.get(key), guard = 0;
            while (hostOf.containsKey(h) && guard++ < 32) h = hostOf.get(h);
            if (hostOf.containsKey(h) || h == key) { hostOf.remove(key); skip.remove(key); }
            else hostOf.put(key, h);
        }

        boolean fr = in.fracture;
        var ks = new ArrayList<int[]>();
        for (var p : ch) if (p != t || fr) ks.add(new int[]{p.index(), FracturePlanner.classify(p.name()).ordinal()});
        var pl = FracturePlanner.plan(ks, fr, r, in.violence).plan();
        for (int h : hostOf.values()) if (pl.get(h) == FracturePlan.SHATTERED) pl.put(h, FracturePlan.DETACHED);

        if (fr) {
            boolean splits = false;
            for (var p : ch) {
                if (p == t || skip.contains(p.index())) continue;
                var f = pl.get(p.index());
                if (f == FracturePlan.DETACHED || f == FracturePlan.SHATTERED) {
                    splits = true;
                    break;
                }
            }
            if (!splits) {
                ModelSnapshot.Part pick = null;
                for (var p : ch) {
                    if (p == t || skip.contains(p.index())) continue;
                    if (pick == null || vol(p) > vol(pick)) pick = p;
                }
                if (pick != null) pl.put(pick.index(), FracturePlan.DETACHED);
            }
        }

        Map<Integer, RigidBox> bo = new HashMap<>();
        Set<RigidBox> fl = new HashSet<>();
        int sb = in.shardBudget, g = 0;

        for (var p : ch) {
            if (skip.contains(p.index())) continue;
            if (pl.get(p.index()) == FracturePlan.SHATTERED && sb > 4) {
                for (var c : p.cubes())
                    for (var cl : Shatter.split(c, r, Math.max(1, sb / p.cubes().size()))) {
                        rd.bodies.add(new RigidBox(box(p.world(), cl.min(), cl.max()), new RenderTag.Shard(p.index(), cl.quads()), -1, rd.constraints));
                        sb--;
                    }
                rd.woundAnchors.add(new Particle[]{rd.bodies.get(rd.bodies.size() - 1).c[0]});
                continue;
            }
            float[][] bb = bounds(p);
            var b = new RigidBox(box(p.world(), bb[0], bb[1]), new RenderTag.WholePart(p.index()), g++, rd.constraints);
            rd.bodies.add(b);
            bo.put(p.index(), b);
        }

        // welded decorations collide with the world through probes on their host
        for (var e : hostOf.entrySet()) {
            var hb = bo.get(e.getValue());
            var wp = byIdx.get(e.getKey());
            if (hb == null || wp == null) continue;
            float[][] bb = bounds(wp);
            for (var v : box(wp.world(), bb[0], bb[1])) hb.attach(v);
        }

        var paired = new HashSet<Long>();
        for (var p : ch) {
            RigidBox k = bo.get(p.index()), q = null;
            if (k == null || p == t) continue;
            String target = jointTarget(in.modelKey, p.name(), ch);
            if (target != null) {
                Integer idx = indexOf(ch, target);
                if (idx != null) {
                    var cand = bo.get(resolve(idx, hostOf));
                    if (cand != k) q = cand;
                }
            }
            for (int i = p.parent(); i >= 0 && q == null; i = ps.get(i).parent()) {
                var cand = bo.get(resolve(i, hostOf));
                if (cand != k) q = cand;
            }
            if (q == null) q = bo.get(t.index());
            if (q == null || q == k) continue;
            int ia = rd.bodies.indexOf(q), ib = rd.bodies.indexOf(k);
            if (!paired.add((long) Math.min(ia, ib) << 32 | Math.max(ia, ib))) continue;
            double[] v = ModelSnapshot.transform(p.world(), 0, 0, 0);
            Vec3d pivot = k.clampToBody(new Vec3d(v[0], v[1], v[2]), .02);
            var f = pl.getOrDefault(p.index(), FracturePlan.ATTACHED);
            var j = rd.addJoint(q, k, pivot, f == FracturePlan.WEAK ? .25 + r.nextDouble() * .5 : 0, jointLimit(p.name()));
            if (f == FracturePlan.DETACHED) {
                j.broken = true;
                j.onBreak.run();
                fl.add(k);
            }
        }

        for (var e : hostOf.entrySet()) {
            var hb = bo.get(e.getValue());
            if (hb == null) continue;
            int hi = rd.bodies.indexOf(hb);
            if (hi >= 0) rd.welds.add(new Ragdoll.Weld(e.getKey(), hi));
        }

        // one coherent launch for the whole corpse: it tips over in the hit direction instead of
        // every bone getting its own random spin (which tore joints apart on the first frame)
        var tb = bo.get(t.index());
        Vec3d tc = (tb != null ? tb : rd.bodies.get(0)).restCenter;
        double kk = .4 + r.nextDouble() * .6, an = r.nextDouble() * Math.PI * 2;
        double hl = Math.hypot(in.vx, in.vz);
        double dx = hl > 1e-3 ? in.vx / hl : Math.cos(an), dz = hl > 1e-3 ? in.vz / hl : Math.sin(an);
        double spin = 2.2 + r.nextDouble() * 1.3;
        double wx = dz * spin, wy = r.nextGaussian() * .35, wz = -dx * spin;
        double lvx = in.vx * .35 + dx * kk * .3, lvy = in.vy * .25 + .08 + r.nextDouble() * .1, lvz = in.vz * .35 + dz * kk * .3;

        for (var b : rd.bodies) {
            double ex = 0, ey = 0, ez = 0, sx = 0, sy = 0, sz = 0;
            boolean sh = b.renderTag instanceof RenderTag.Shard;
            if (fr && (sh || fl.contains(b))) {
                var o = Vec3d.sub(b.restCenter, tc);
                if (o.length() < 1e-3) o.set(r.nextGaussian(), .3, r.nextGaussian());
                o.normalize();
                double s = (sh ? 1.5 : .8) + r.nextDouble() * 1.2 * in.violence;
                ex = o.x * s;
                ey = Math.abs(o.y) * s * .3 + .5 + r.nextDouble() * .8;
                ez = o.z * s;
                double sp = sh ? 4 : 2.5;
                sx = r.nextGaussian() * sp;
                sy = r.nextGaussian() * sp;
                sz = r.nextGaussian() * sp;
            }
            for (var p : b.particles()) {
                double rx = p.x - tc.x, ry = p.y - tc.y, rz = p.z - tc.z;
                double ox = p.x - b.restCenter.x, oy = p.y - b.restCenter.y, oz = p.z - b.restCenter.z;
                p.setVelocity(
                        lvx + ex + (wy * rz - wz * ry) + (sy * oz - sz * oy),
                        lvy + ey + (wz * rx - wx * rz) + (sz * ox - sx * oz),
                        lvz + ez + (wx * ry - wy * rx) + (sx * oy - sy * ox),
                        Ragdoll.STEP);
            }
        }

        if (in.blood && rd.woundAnchors.isEmpty()) {
            var trunk = bo.get(t.index());
            rd.woundAnchors.add(new Particle[]{(trunk != null ? trunk : rd.bodies.get(0)).c[0]});
            for (var p : ch) if ("head".equals(p.name())) {
                var hb = bo.get(p.index());
                if (hb != null) rd.woundAnchors.add(new Particle[]{hb.c[0]});
                break;
            }
        }
        return rd;
    }

    private static double jointLimit(String name) {
        return Math.toRadians(switch (FracturePlanner.classify(name)) {
            case HEAD -> 55;
            case LIMB -> 100;
            case APPENDAGE -> 70;
            case TORSO -> 35;
            default -> 60;
        });
    }

    private static final java.util.regex.Pattern CHAIN = java.util.regex.Pattern.compile("^(tail|neck|segment|tail_segment)(\\d+)$");

    /** tail2 -> tail1, neck3 -> neck2, ... when the model has the previous link. */
    private static String chainParent(String name, List<ModelSnapshot.Part> ch) {
        var m = CHAIN.matcher(name.toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        int n = Integer.parseInt(m.group(2));
        if (n <= 0) return null;
        String prev = m.group(1) + (n - 1);
        if (indexOf(ch, prev) != null) return prev;
        if (n == 2 && indexOf(ch, m.group(1)) != null) return m.group(1);
        return null;
    }

    private static String jointTarget(String modelKey, String name, List<ModelSnapshot.Part> ch) {
        String t = Rig.parentOf(modelKey, name);
        return t != null ? t : chainParent(name, ch);
    }

    private static int resolve(int idx, Map<Integer, Integer> hostOf) {
        Integer h = hostOf.get(idx);
        return h != null ? h : idx;
    }

    private static float[][] bounds(ModelSnapshot.Part p) {
        float[] mn = {1e9f, 1e9f, 1e9f}, mx = {-1e9f, -1e9f, -1e9f};
        for (var c : p.cubes()) {
            float[] a = {c.minX(), c.minY(), c.minZ()}, b = {c.maxX(), c.maxY(), c.maxZ()};
            for (int i = 0; i < 3; i++) {
                mn[i] = Math.min(mn[i], a[i]);
                mx[i] = Math.max(mx[i], b[i]);
            }
        }
        return new float[][]{mn, mx};
    }

    private static Vec3d[] box(double[] m, float[] mn, float[] mx) {
        double[][] e = new double[2][3];
        for (int i = 0; i < 3; i++) {
            double c = (mn[i] + mx[i]) * .5 * PX, h = Math.max(MH, (mx[i] - mn[i]) * .5 * PX);
            e[0][i] = c - h;
            e[1][i] = c + h;
        }
        var o = new Vec3d[8];
        for (int i = 0; i < 8; i++) {
            double[] w = ModelSnapshot.transform(m, e[i & 1][0], e[i >> 1 & 1][1], e[i >> 2 & 1][2]);
            o[i] = new Vec3d(w[0], w[1], w[2]);
        }
        return o;
    }

    private static double vol(ModelSnapshot.Part p) {
        return p.cubes().stream().mapToDouble(c -> (c.maxX() - c.minX()) * (c.maxY() - c.minY()) * (c.maxZ() - c.minZ())).sum();
    }

    private static Integer indexOf(List<ModelSnapshot.Part> parts, String name) {
        for (var p : parts) {
            if (p.cubes().isEmpty()) continue;
            if (p.name().equalsIgnoreCase(name)) return p.index();
        }
        return null;
    }

    public static double[] delta(RigidBox b, double a) {
        var R = b.deltaRotation(a);
        Vec3d c = b.center(true, a), o = b.restCenter;
        double[] g = new double[16], cc = {c.x, c.y, c.z};
        g[15] = 1;
        for (int i = 0; i < 9; i++) g[i / 3 * 4 + i % 3] = R.get(i % 3, i / 3);
        for (int i = 0; i < 3; i++) g[12 + i] = cc[i] - (R.get(i, 0) * o.x + R.get(i, 1) * o.y + R.get(i, 2) * o.z);
        return g;
    }
}
