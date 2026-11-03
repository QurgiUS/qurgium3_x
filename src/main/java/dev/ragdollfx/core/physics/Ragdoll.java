package dev.ragdollfx.core.physics;

import dev.ragdollfx.core.math.Mat3d;
import dev.ragdollfx.core.math.Vec3d;
import java.util.*;

public final class Ragdoll {
    public static final double STEP = 1 / 60.0, GRAVITY = 24, AIR_DAMPING = .995, FRICTION = .95;
    public static final int ITERATIONS = 10;
    /** Max travel of one particle per step; a fast fall must not tunnel through a floor. */
    public static final double MAX_STEP_MOVE = .45;
    public static final double DEFAULT_JOINT_LIMIT = Math.toRadians(80);

    private static final double GROUND_DAMPING = .94, REST_DAMPING = .8, REST_AIR_DAMPING = .96, REST_SPEED = .012;
    /** Allowed resting penetration; pushing out further makes bodies hop on the ground every step. */
    private static final double SLOP = .002;
    private static final double MU_STATIC = .9, MU_KINETIC = .6;
    private static final double LIMIT_STIFFNESS = .5;
    private static final double ENERGY_SLACK = .003;
    private static final double CALM_SPEED = .004, MIN_AWAKE_TIME = .6;
    private static final int CALM_STEPS = 20, SUPPORT_CHECK_STEPS = 15, FINAL_CONTACT_PASSES = 4;

    /** A model part drawn rigidly with a body instead of being simulated on its own. */
    public record Weld(int part, int body) {}

    private record Joint(Constraint c, RigidBox parent, RigidBox child, double maxAngle) {
        RigidBox other(RigidBox b) { return b == parent ? child : parent; }
    }

    public final List<RigidBox> bodies = new ArrayList<>();
    public final List<Constraint> constraints = new ArrayList<>(), joints = new ArrayList<>();
    public final List<Particle[]> woundAnchors = new ArrayList<>();
    public final List<Weld> welds = new ArrayList<>();
    public double age;

    private double acc;
    private final List<Joint> links = new ArrayList<>();
    private final Map<RigidBox, List<Joint>> linksOf = new IdentityHashMap<>();
    private final List<Aabb> scratch = new ArrayList<>();
    private final HashSet<Long> cells = new HashSet<>();
    private final HashSet<Aabb> seen = new HashSet<>();
    private final double[] pt = new double[6];
    private final double[] terms = new double[6];
    private boolean asleep;
    private int calm, sleepTick;
    private double lastMaxSpeed = 1;

    public boolean sleeping() { return asleep; }

    public void wake() {
        asleep = false;
        calm = 0;
        lastMaxSpeed = 1;
    }

    public Constraint addJoint(RigidBox p, RigidBox ch, Vec3d at, double brk) {
        return addJoint(p, ch, at, brk, DEFAULT_JOINT_LIMIT);
    }

    public Constraint addJoint(RigidBox p, RigidBox ch, Vec3d at, double brk, double maxAngle) {
        Particle a = p.attach(at), b = ch.attach(at);
        var j = new Constraint(a, b, 0, 1, false);
        j.breakDistance = brk;
        j.onBreak = () -> woundAnchors.add(new Particle[]{a, b});
        joints.add(j);
        constraints.add(j);
        var link = new Joint(j, p, ch, maxAngle);
        links.add(link);
        linksOf.computeIfAbsent(p, k -> new ArrayList<>()).add(link);
        linksOf.computeIfAbsent(ch, k -> new ArrayList<>()).add(link);
        return j;
    }

    public double advance(double s, WorldCollider w) {
        acc += Math.min(s, .1);
        for (int n = 0; acc >= STEP && n < 6; n++, acc -= STEP) step(w);
        return acc / STEP;
    }

    public void step(WorldCollider w) {
        age += STEP;
        if (asleep) {
            if (++sleepTick % SUPPORT_CHECK_STEPS != 0 || supported(w)) return;
            wake();
        }
        double g = GRAVITY * STEP * STEP;
        boolean resting = lastMaxSpeed < REST_SPEED;
        for (var b : bodies) {
            b.beginStep();
            double k = b.grounded ? (resting ? REST_DAMPING : GROUND_DAMPING) : (resting ? REST_AIR_DAMPING : AIR_DAMPING);
            for (var p : b.c) move(p, k, g);
            for (var p : b.attached) move(p, k, g);
            b.shapeMatch();
            gather(b, w);
            b.resetContacts();
            b.grounded = false;
        }
        for (int i = 0; i < ITERATIONS; i++) {
            solveJoints(i == 0);
            solveLimits();
            if (i == 0 || i == ITERATIONS / 2) contacts();
            if ((i & 1) == 1) for (var b : bodies) collide(b);
        }
        for (var b : bodies) {
            b.shapeMatch();
            for (int s = 0; s < FINAL_CONTACT_PASSES && collide(b) > 0; s++) {}
            friction(b);
            removeBounce(b);
            limitEnergy(b, g);
            settle(b);
        }
        updateSleep();
    }

    private static void move(Particle p, double k, double g) {
        double vx = (p.x - p.px) * k, vy = (p.y - p.py) * k, vz = (p.z - p.pz) * k;
        double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (d > MAX_STEP_MOVE) {
            double s = MAX_STEP_MOVE / d;
            vx *= s;
            vy *= s;
            vz *= s;
        }
        p.px = p.x;
        p.py = p.y;
        p.pz = p.z;
        p.x += vx;
        p.y += vy - g;
        p.z += vz;
    }

    private void solveJoints(boolean first) {
        for (var l : links) {
            var c = l.c();
            if (c.broken) continue;
            Particle a = c.a, b = c.b;
            double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (first && c.breakDistance > 0 && d - c.rest > c.breakDistance) {
                c.broken = true;
                if (c.onBreak != null) c.onBreak.run();
                continue;
            }
            if (d < 1e-7) continue;
            double nx = dx / d, ny = dy / d, nz = dz / d;
            double wa = l.parent().genInvMass(a.x, a.y, a.z, nx, ny, nz), wb = l.child().genInvMass(b.x, b.y, b.z, nx, ny, nz);
            double lam = d / (wa + wb);
            l.parent().applyImpulse(a.x, a.y, a.z, nx * lam, ny * lam, nz * lam);
            l.child().applyImpulse(b.x, b.y, b.z, -nx * lam, -ny * lam, -nz * lam);
        }
    }

    /** Cone+twist limit: relative rotation of child to parent may not exceed maxAngle from the death pose. */
    private void solveLimits() {
        for (var l : links) {
            if (l.c().broken || l.maxAngle() >= Math.PI) continue;
            double[] rel = RigidBox.relative(l.parent(), l.child());
            double w = Math.min(1, rel[0]), ang = 2 * Math.acos(w);
            if (ang <= l.maxAngle()) continue;
            double s = Math.sqrt(Math.max(1e-12, 1 - w * w));
            Mat3d rp = l.parent().rotation();
            Vec3d ax = rp.transform(new Vec3d(rel[1] / s, rel[2] / s, rel[3] / s)).normalize();
            double wp = l.parent().angularInvMass(ax.x, ax.y, ax.z), wc = l.child().angularInvMass(ax.x, ax.y, ax.z), sum = wp + wc;
            if (sum < 1e-12) continue;
            double ex = (ang - l.maxAngle()) * LIMIT_STIFFNESS, fc = ex * wc / sum, fp = ex * wp / sum;
            Particle pa = l.c().a, pb = l.c().b;
            l.child().rotate(-ax.x * fc, -ax.y * fc, -ax.z * fc, pb.x, pb.y, pb.z);
            l.parent().rotate(ax.x * fp, ax.y * fp, ax.z * fp, pa.x, pa.y, pa.z);
        }
    }

    private void gather(RigidBox b, WorldCollider w) {
        b.nearby.clear();
        scratch.clear();
        cells.clear();
        double x0 = 1e18, y0 = 1e18, z0 = 1e18, x1 = -1e18, y1 = -1e18, z1 = -1e18;
        int n = b.probeCount();
        for (int k = 0; k < n; k++) {
            b.probe(k, pt);
            x0 = Math.min(x0, pt[0]);
            x1 = Math.max(x1, pt[0]);
            y0 = Math.min(y0, pt[1]);
            y1 = Math.max(y1, pt[1]);
            z0 = Math.min(z0, pt[2]);
            z1 = Math.max(z1, pt[2]);
            if (cells.add(cell(pt[0], pt[1], pt[2]))) w.boxesNear(pt[0], pt[1], pt[2], scratch);
        }
        double m = .3;
        seen.clear();
        for (var box : scratch) {
            if (box.maxX() < x0 - m || box.minX() > x1 + m || box.maxY() < y0 - m || box.minY() > y1 + m
                    || box.maxZ() < z0 - m || box.minZ() > z1 + m) continue;
            if (seen.add(box)) b.nearby.add(box);
        }
    }

    private static long cell(double x, double y, double z) {
        long a = (long) Math.floor(x) & 0x1FFFFF, b = (long) Math.floor(y) & 0x1FFFFF, c = (long) Math.floor(z) & 0x1FFFFF;
        return a << 42 | b << 21 | c;
    }

    /**
     * One simultaneous (Jacobi) contact pass: every penetrating probe computes its own correction
     * and the body moves by their average. Solving probes one by one rocked resting bodies
     * corner-to-corner every frame, which was the ground jitter.
     */
    private int collide(RigidBox b) {
        if (b.nearby.isEmpty()) return 0;
        int n = b.probeCount(), hits = 0;
        Vec3d cc = b.center(false, 1);
        Arrays.fill(terms, 0);
        for (int k = 0; k < n; k++) {
            b.probe(k, pt);
            int bestF = -1;
            double bestD = 0;
            for (var box : b.nearby) {
                if (!inside(box, pt[0], pt[1], pt[2])) continue;
                int f = exitFace(box, pt, b.nearby);
                double d = depth(box, f, pt[0], pt[1], pt[2]);
                if (d > bestD) {
                    bestD = d;
                    bestF = f;
                }
            }
            if (bestF < 0 || bestD <= SLOP) continue;
            double corr = bestD - SLOP * .5;
            double nx = bestF == 0 ? -1 : bestF == 1 ? 1 : 0, ny = bestF == 2 ? -1 : bestF == 3 ? 1 : 0, nz = bestF == 4 ? -1 : bestF == 5 ? 1 : 0;
            double lam = corr / b.genInvMass(pt[0], pt[1], pt[2], nx, ny, nz);
            b.impulseTerms(terms, cc, pt[0], pt[1], pt[2], nx * lam, ny * lam, nz * lam);
            b.contactDepth[k] += corr;
            b.contactFace[k] = bestF;
            if (bestF == 3) b.grounded = true;
            hits++;
        }
        if (hits > 0) {
            double s = 1.0 / hits;
            b.applyRigid(terms[0] * s, terms[1] * s, terms[2] * s, terms[3] * s, terms[4] * s, terms[5] * s, cc);
        }
        return hits;
    }

    /** Coulomb friction from the normal correction accumulated this step, averaged like contacts. */
    private void friction(RigidBox b) {
        int n = b.probeCount(), hits = 0;
        Vec3d cc = b.center(false, 1);
        Arrays.fill(terms, 0);
        for (int k = 0; k < n; k++) {
            double d = b.contactDepth[k];
            if (d <= 0) continue;
            b.probe(k, pt);
            int ax = b.contactFace[k] >> 1;
            double dx = ax == 0 ? 0 : pt[0] - pt[3], dy = ax == 1 ? 0 : pt[1] - pt[4], dz = ax == 2 ? 0 : pt[2] - pt[5];
            double tl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (tl < 1e-9) continue;
            double corr = tl <= MU_STATIC * d ? tl : MU_KINETIC * d;
            double ux = -dx / tl, uy = -dy / tl, uz = -dz / tl;
            double lam = corr / b.genInvMass(pt[0], pt[1], pt[2], ux, uy, uz);
            b.impulseTerms(terms, cc, pt[0], pt[1], pt[2], ux * lam, uy * lam, uz * lam);
            hits++;
        }
        if (hits > 0) {
            double s = 1.0 / hits;
            b.applyRigid(terms[0] * s, terms[1] * s, terms[2] * s, terms[3] * s, terms[4] * s, terms[5] * s, cc);
        }
    }

    /** A contact push-out must not turn into upward velocity (that is what launched corpses). */
    private static void removeBounce(RigidBox b) {
        if (!b.grounded) return;
        double vy = b.meanVy(), cap = Math.max(0, b.vyBefore) + .0015;
        if (vy > cap) b.shiftVelocity(0, -(vy - cap), 0);
    }

    /** Position corrections must never make a body faster than gravity alone could. */
    private static void limitEnergy(RigidBox b, double g) {
        double now = b.maxSpeed(), allowed = b.speedBefore + g * 1.5 + ENERGY_SLACK;
        if (now > allowed && now > 1e-9) b.scaleVelocity(allowed / now);
    }

    private static void settle(RigidBox b) {
        for (var p : b.c) p.grounded = b.grounded;
        if (b.grounded && b.maxSpeed() < 8e-4) b.scaleVelocity(0);
    }

    private void updateSleep() {
        double m = 0;
        for (var b : bodies) m = Math.max(m, b.maxSpeed());
        lastMaxSpeed = m;
        if (age < MIN_AWAKE_TIME || m >= CALM_SPEED) {
            calm = 0;
            return;
        }
        if (++calm < CALM_STEPS) return;
        for (var b : bodies) b.freeze();
        asleep = true;
        sleepTick = 0;
    }

    /** While asleep: every body that rested on something must still have a block right under it. */
    private boolean supported(WorldCollider w) {
        for (var b : bodies) {
            if (!b.grounded) continue;
            gather(b, w);
            boolean ok = false;
            int n = b.probeCount();
            for (int k = 0; k < n && !ok; k++) {
                b.probe(k, pt);
                for (var box : b.nearby)
                    if (inside(box, pt[0], pt[1] - .03, pt[2])) {
                        ok = true;
                        break;
                    }
            }
            if (!ok) return false;
        }
        return true;
    }

    private static boolean inside(Aabb b, double x, double y, double z) {
        return x > b.minX() && x < b.maxX() && y > b.minY() && y < b.maxY() && z > b.minZ() && z < b.maxZ();
    }

    private static double depth(Aabb b, int f, double x, double y, double z) {
        return switch (f) {
            case 0 -> x - b.minX();
            case 1 -> b.maxX() - x;
            case 2 -> y - b.minY();
            case 3 -> b.maxY() - y;
            case 4 -> z - b.minZ();
            default -> b.maxZ() - z;
        };
    }

    /**
     * Exit through the face the point came through during this step; faces covered by a neighbour
     * block are never valid exits (that pushed corpses sideways into the floor).
     */
    private static int exitFace(Aabb b, double[] p, List<Aabb> all) {
        double x = p[0], y = p[1], z = p[2], ox = p[3], oy = p[4], oz = p[5], tol = SLOP * 2;
        double[] d = {x - b.minX(), b.maxX() - x, y - b.minY(), b.maxY() - y, z - b.minZ(), b.maxZ() - z};
        boolean[] came = {ox <= b.minX() + tol, ox >= b.maxX() - tol, oy <= b.minY() + tol, oy >= b.maxY() - tol, oz <= b.minZ() + tol, oz >= b.maxZ() - tol};
        int best = -1;
        for (int f = 0; f < 6; f++) if (came[f] && (best < 0 || d[f] < d[best]) && !covered(b, f, x, y, z, all)) best = f;
        if (best >= 0) return best;
        for (int f = 0; f < 6; f++) if (f != 2 && (best < 0 || d[f] < d[best]) && !covered(b, f, x, y, z, all)) best = f;
        return best >= 0 ? best : 3;
    }

    private static boolean covered(Aabb b, int f, double x, double y, double z, List<Aabb> all) {
        double e = 2e-3;
        switch (f) {
            case 0 -> x = b.minX() - e;
            case 1 -> x = b.maxX() + e;
            case 2 -> y = b.minY() - e;
            case 3 -> y = b.maxY() + e;
            case 4 -> z = b.minZ() - e;
            default -> z = b.maxZ() + e;
        }
        for (var o : all) if (o != b && inside(o, x, y, z)) return true;
        return false;
    }

    private boolean collidable(RigidBox a, RigidBox b) {
        var la = linksOf.get(a);
        var lb = linksOf.get(b);
        if (la == null || lb == null) return true;
        for (var j : la) {
            if (j.c().broken) continue;
            var o = j.other(a);
            if (o == b) return false;
            for (var k : lb) if (!k.c().broken && k.other(b) == o) return false;
        }
        return true;
    }

    /** Capsule-vs-capsule separation between bodies that are not directly jointed or siblings. */
    private void contacts() {
        int n = bodies.size();
        if (n < 2) return;
        double[] ca = new double[7], cb = new double[7];
        for (int i = 0; i < n; i++) {
            RigidBox a = bodies.get(i);
            capsule(a, ca);
            for (int j = i + 1; j < n; j++) {
                RigidBox b = bodies.get(j);
                if (a.group < 0 && b.group < 0) continue;
                capsule(b, cb);
                double dx = cb[0] - ca[0], dy = cb[1] - ca[1], dz = cb[2] - ca[2], lim = a.boundRadius + b.boundRadius;
                if (dx * dx + dy * dy + dz * dz > lim * lim) continue;
                if (!collidable(a, b)) continue;
                if (separate(a, ca, b, cb)) capsule(a, ca);
            }
        }
    }

    private static void capsule(RigidBox b, double[] o) {
        Vec3d c = b.center(false, 1), ax = b.axis[b.longAxis];
        o[0] = c.x;
        o[1] = c.y;
        o[2] = c.z;
        o[3] = ax.x * b.capHalf;
        o[4] = ax.y * b.capHalf;
        o[5] = ax.z * b.capHalf;
        o[6] = b.capRadius * .85;
    }

    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static boolean separate(RigidBox a, double[] ca, RigidBox b, double[] cb) {
        double p1x = ca[0] - ca[3], p1y = ca[1] - ca[4], p1z = ca[2] - ca[5], d1x = 2 * ca[3], d1y = 2 * ca[4], d1z = 2 * ca[5];
        double p2x = cb[0] - cb[3], p2y = cb[1] - cb[4], p2z = cb[2] - cb[5], d2x = 2 * cb[3], d2y = 2 * cb[4], d2z = 2 * cb[5];
        double rx = p1x - p2x, ry = p1y - p2y, rz = p1z - p2z;
        double aa = d1x * d1x + d1y * d1y + d1z * d1z, e = d2x * d2x + d2y * d2y + d2z * d2z, f = d2x * rx + d2y * ry + d2z * rz;
        double s, t, eps = 1e-9;
        if (aa <= eps && e <= eps) {
            s = 0;
            t = 0;
        } else if (aa <= eps) {
            s = 0;
            t = clamp01(f / e);
        } else {
            double cc = d1x * rx + d1y * ry + d1z * rz;
            if (e <= eps) {
                t = 0;
                s = clamp01(-cc / aa);
            } else {
                double bb = d1x * d2x + d1y * d2y + d1z * d2z, den = aa * e - bb * bb;
                s = den > eps ? clamp01((bb * f - cc * e) / den) : 0;
                t = (bb * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp01(-cc / aa);
                } else if (t > 1) {
                    t = 1;
                    s = clamp01((bb - cc) / aa);
                }
            }
        }
        double c1x = p1x + d1x * s, c1y = p1y + d1y * s, c1z = p1z + d1z * s;
        double c2x = p2x + d2x * t, c2y = p2y + d2y * t, c2z = p2z + d2z * t;
        double nx = c2x - c1x, ny = c2y - c1y, nz = c2z - c1z, l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double pen = ca[6] + cb[6] - l;
        if (pen <= SLOP) return false;
        if (l < 1e-6) {
            nx = 0;
            ny = 1;
            nz = 0;
        } else {
            nx /= l;
            ny /= l;
            nz /= l;
        }
        double pax = c1x + nx * ca[6], pay = c1y + ny * ca[6], paz = c1z + nz * ca[6];
        double pbx = c2x - nx * cb[6], pby = c2y - ny * cb[6], pbz = c2z - nz * cb[6];
        double wa = a.genInvMass(pax, pay, paz, nx, ny, nz), wb = b.genInvMass(pbx, pby, pbz, nx, ny, nz);
        double lam = (pen - SLOP) * .5 / (wa + wb);
        a.applyImpulse(pax, pay, paz, -nx * lam, -ny * lam, -nz * lam);
        b.applyImpulse(pbx, pby, pbz, nx * lam, ny * lam, nz * lam);
        return true;
    }

    public boolean push(Aabb e, double vx, double vy, double vz, double cx, double cz) {
        boolean h = false;
        for (var b : bodies) {
            double x0 = 1e9, y0 = 1e9, z0 = 1e9, x1 = -1e9, y1 = -1e9, z1 = -1e9;
            for (var p : b.c) {
                x0 = Math.min(x0, p.x);
                x1 = Math.max(x1, p.x);
                y0 = Math.min(y0, p.y);
                y1 = Math.max(y1, p.y);
                z0 = Math.min(z0, p.z);
                z1 = Math.max(z1, p.z);
            }
            if (x1 < e.minX() || x0 > e.maxX() || y1 < e.minY() || y0 > e.maxY() || z1 < e.minZ() || z0 > e.maxZ()) continue;
            double bx = (x0 + x1) * .5 - cx, bz = (z0 + z1) * .5 - cz, l = Math.max(.2, Math.hypot(bx, bz));
            b.addVelocity(vx * 1.7 + bx / l * 1.5, Math.max(0, vy) * .5 + .45, vz * 1.7 + bz / l * 1.5, STEP);
            b.grounded = false;
            h = true;
        }
        if (h) wake();
        return h;
    }
}
