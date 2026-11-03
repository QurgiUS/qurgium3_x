package dev.ragdollfx.core.physics;

import dev.ragdollfx.core.math.Mat3d;
import dev.ragdollfx.core.math.Vec3d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A rigid box made of 8 corner particles plus any number of attached points (joint pins,
 * collision probes of welded decorations). Orientation is extracted from the corners by
 * shape matching, so the box can never mirror itself inside out; every correction
 * (joints, limits, contacts, friction) moves the whole box as one rigid body.
 */
public final class RigidBox {
    private static final double[][] PROBES = buildProbes();
    private static final double MAX_ROT_PER_CORRECTION = .2;

    public final Particle[] c = new Particle[8];
    public final Vec3d restCenter = new Vec3d();
    public final Mat3d restRot;
    public final double radius;
    public final Object renderTag;
    public final int group;
    public final double mass, invMass;

    final double[] half = new double[3];
    final double boundRadius, capRadius, capHalf;
    final int longAxis;
    final Vec3d[] axis = new Vec3d[3];
    final List<Particle> attached = new ArrayList<>();
    final List<Aabb> nearby = new ArrayList<>();
    double[] contactDepth = new double[0];
    int[] contactFace = new int[0];
    boolean grounded;
    double speedBefore, vyBefore;

    private final Vec3d[] restAxis = new Vec3d[3];
    private final double[] invInertia = new double[3];
    private final Vec3d[] cornerRest = new Vec3d[8];
    private final List<Vec3d> attachedRest = new ArrayList<>();
    private final int latticeProbes;
    private final Vec3d prevCenter = new Vec3d();
    private double qw = 1, qx, qy, qz, pqw = 1, pqx, pqy, pqz;
    private Mat3d rot = Mat3d.identity();

    /** {@code cs} is kept for API compatibility; rigidity no longer needs distance constraints. */
    public RigidBox(Vec3d[] v, Object tag, int g, List<Constraint> cs) {
        renderTag = tag;
        group = g;
        for (int i = 0; i < 8; i++) {
            c[i] = new Particle(v[i].x, v[i].y, v[i].z);
            restCenter.add(v[i]);
        }
        restCenter.scale(.125);
        for (int i = 0; i < 8; i++) cornerRest[i] = Vec3d.sub(v[i], restCenter);
        Vec3d[] e = {Vec3d.sub(v[1], v[0]), Vec3d.sub(v[2], v[0]), Vec3d.sub(v[4], v[0])};
        for (int k = 0; k < 3; k++) half[k] = Math.max(.01, e[k].length() * .5);
        orthonormal(e, restAxis);
        for (int k = 0; k < 3; k++) axis[k] = restAxis[k].copy();
        double vol = 8 * half[0] * half[1] * half[2];
        mass = Math.pow(Math.max(vol, 2e-3), .45);
        invMass = 1 / mass;
        for (int k = 0; k < 3; k++) {
            double a = half[(k + 1) % 3], b = half[(k + 2) % 3];
            invInertia[k] = 1 / Math.max(mass / 3 * (a * a + b * b), 1e-6);
        }
        for (var p : c) p.invMass = invMass;
        int la = 0;
        for (int k = 1; k < 3; k++) if (half[k] > half[la]) la = k;
        longAxis = la;
        capRadius = Math.sqrt(half[(la + 1) % 3] * half[(la + 2) % 3]);
        capHalf = Math.max(0, half[la] - capRadius);
        boundRadius = Math.sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2]);
        latticeProbes = half[la] * 2 > .55 ? PROBES.length : 8;
        restRot = Mat3d.fromAxes(restAxis[0], restAxis[1], restAxis[2]);
        radius = Vec3d.sub(v[7], v[0]).length() * .42;
        prevCenter.set(restCenter);
    }

    public List<Particle> particles() {
        var l = new ArrayList<Particle>(8 + attached.size());
        for (var p : c) l.add(p);
        l.addAll(attached);
        return l;
    }

    /** Adds a point that moves rigidly with this box. Call while the box is still in its rest pose. */
    public Particle attach(Vec3d at) {
        Vec3d cc = center(false, 1);
        double dx = at.x - cc.x, dy = at.y - cc.y, dz = at.z - cc.z;
        Vec3d off = new Vec3d(
                rot.get(0, 0) * dx + rot.get(1, 0) * dy + rot.get(2, 0) * dz,
                rot.get(0, 1) * dx + rot.get(1, 1) * dy + rot.get(2, 1) * dz,
                rot.get(0, 2) * dx + rot.get(1, 2) * dy + rot.get(2, 2) * dz);
        var p = new Particle(at.x, at.y, at.z);
        p.invMass = invMass;
        attached.add(p);
        attachedRest.add(off);
        return p;
    }

    /** Closest point of the rest-pose box (grown by {@code margin}) to {@code p}. */
    public Vec3d clampToBody(Vec3d p, double margin) {
        double dx = p.x - restCenter.x, dy = p.y - restCenter.y, dz = p.z - restCenter.z;
        Vec3d out = restCenter.copy();
        for (int k = 0; k < 3; k++) {
            Vec3d a = restAxis[k];
            double lim = half[k] + margin, t = Math.max(-lim, Math.min(lim, dx * a.x + dy * a.y + dz * a.z));
            out.x += a.x * t;
            out.y += a.y * t;
            out.z += a.z * t;
        }
        return out;
    }

    public Vec3d center(boolean in, double a) {
        double x = 0, y = 0, z = 0;
        for (var p : c) {
            x += p.x;
            y += p.y;
            z += p.z;
        }
        x *= .125;
        y *= .125;
        z *= .125;
        if (!in) return new Vec3d(x, y, z);
        return new Vec3d(prevCenter.x + (x - prevCenter.x) * a,
                prevCenter.y + (y - prevCenter.y) * a,
                prevCenter.z + (z - prevCenter.z) * a);
    }

    public Mat3d frame(boolean in, double a) {
        return deltaRotation(in ? a : 1).mul(restRot);
    }

    /** Rotation from the rest pose to the pose interpolated between the last two steps. */
    public Mat3d deltaRotation(double a) {
        double s = pqw * qw + pqx * qx + pqy * qy + pqz * qz < 0 ? -1 : 1;
        double w = pqw + (qw * s - pqw) * a, x = pqx + (qx * s - pqx) * a,
                y = pqy + (qy * s - pqy) * a, z = pqz + (qz * s - pqz) * a;
        double l = Math.sqrt(w * w + x * x + y * y + z * z);
        if (l < 1e-12) return rot;
        return Mat3d.fromQuat(w / l, x / l, y / l, z / l);
    }

    public void addVelocity(double a, double b, double d, double t) {
        for (var p : c) p.addVelocity(a, b, d, t);
        for (var p : attached) p.addVelocity(a, b, d, t);
    }

    Mat3d rotation() { return rot; }

    void beginStep() {
        prevCenter.set(center(false, 1));
        pqw = qw;
        pqx = qx;
        pqy = qy;
        pqz = qz;
        speedBefore = maxSpeed();
        vyBefore = meanVy();
    }

    /** Stops all motion and makes the interpolated render pose equal to the current pose. */
    void freeze() {
        for (var p : c) { p.px = p.x; p.py = p.y; p.pz = p.z; }
        for (var p : attached) { p.px = p.x; p.py = p.y; p.pz = p.z; }
        beginStep();
    }

    double maxSpeed() {
        double m = 0;
        for (var p : c) {
            double dx = p.x - p.px, dy = p.y - p.py, dz = p.z - p.pz;
            m = Math.max(m, dx * dx + dy * dy + dz * dz);
        }
        return Math.sqrt(m);
    }

    double meanVy() {
        double s = 0;
        for (var p : c) s += p.y - p.py;
        return s * .125;
    }

    /** Scales the velocity of every point by f (keeps current positions). */
    void scaleVelocity(double f) {
        for (var p : c) { p.px = p.x - (p.x - p.px) * f; p.py = p.y - (p.y - p.py) * f; p.pz = p.z - (p.z - p.pz) * f; }
        for (var p : attached) { p.px = p.x - (p.x - p.px) * f; p.py = p.y - (p.y - p.py) * f; p.pz = p.z - (p.z - p.pz) * f; }
    }

    /** Adds a uniform per-step velocity change to every point (keeps current positions). */
    void shiftVelocity(double dx, double dy, double dz) {
        for (var p : c) { p.px -= dx; p.py -= dy; p.pz -= dz; }
        for (var p : attached) { p.px -= dx; p.py -= dy; p.pz -= dz; }
    }

    /** Extracts the best-fit rotation from the corners and snaps every point back onto the rigid shape. */
    void shapeMatch() {
        double cx = 0, cy = 0, cz = 0;
        for (var p : c) {
            cx += p.x;
            cy += p.y;
            cz += p.z;
        }
        cx *= .125;
        cy *= .125;
        cz *= .125;
        if (!Double.isFinite(cx + cy + cz)) {
            recover();
            return;
        }
        double[] a = new double[9];
        for (int i = 0; i < 8; i++) {
            double dx = c[i].x - cx, dy = c[i].y - cy, dz = c[i].z - cz;
            Vec3d r = cornerRest[i];
            a[0] += dx * r.x;
            a[1] += dy * r.x;
            a[2] += dz * r.x;
            a[3] += dx * r.y;
            a[4] += dy * r.y;
            a[5] += dz * r.y;
            a[6] += dx * r.z;
            a[7] += dy * r.z;
            a[8] += dz * r.z;
        }
        // Mueller et al. 2016, "A Robust Method to Extract the Rotational Part of Deformations"
        for (int it = 0; it < 10; it++) {
            double ox = 0, oy = 0, oz = 0, den = 0;
            for (int k = 0; k < 3; k++) {
                double rx = rot.get(0, k), ry = rot.get(1, k), rz = rot.get(2, k);
                double ax = a[k * 3], ay = a[k * 3 + 1], az = a[k * 3 + 2];
                ox += ry * az - rz * ay;
                oy += rz * ax - rx * az;
                oz += rx * ay - ry * ax;
                den += rx * ax + ry * ay + rz * az;
            }
            double s = 1 / (Math.abs(den) + 1e-9);
            ox *= s;
            oy *= s;
            oz *= s;
            if (ox * ox + oy * oy + oz * oz < 1e-20) break;
            premultiplyAxisAngle(ox, oy, oz);
        }
        snap(cx, cy, cz);
    }

    void translate(double dx, double dy, double dz) {
        for (var p : c) {
            p.x += dx;
            p.y += dy;
            p.z += dz;
        }
        for (var p : attached) {
            p.x += dx;
            p.y += dy;
            p.z += dz;
        }
    }

    /** Rotates the whole body by the axis-angle vector {@code w} around pivot {@code o}. */
    void rotate(double wx, double wy, double wz, double ox, double oy, double oz) {
        double ang = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (ang < 1e-12) return;
        double s = Math.sin(ang * .5) / ang, dw = Math.cos(ang * .5), dx = wx * s, dy = wy * s, dz = wz * s;
        Mat3d m = Mat3d.fromQuat(dw, dx, dy, dz);
        for (var p : c) turn(p, m, ox, oy, oz);
        for (var p : attached) turn(p, m, ox, oy, oz);
        premultiply(dw, dx, dy, dz);
    }

    /**
     * Adds the translation (acc[0..2]) and rotation (acc[3..5]) that a positional impulse
     * {@code j} at point {@code p} produces on this body around centre {@code cc}.
     */
    void impulseTerms(double[] acc, Vec3d cc, double px, double py, double pz, double jx, double jy, double jz) {
        double rx = px - cc.x, ry = py - cc.y, rz = pz - cc.z;
        acc[0] += jx * invMass;
        acc[1] += jy * invMass;
        acc[2] += jz * invMass;
        double[] w = invI(ry * jz - rz * jy, rz * jx - rx * jz, rx * jy - ry * jx);
        acc[3] += w[0];
        acc[4] += w[1];
        acc[5] += w[2];
    }

    /** Applies a rigid translation t and rotation w (about the translated centre cc + t). */
    void applyRigid(double tx, double ty, double tz, double wx, double wy, double wz, Vec3d cc) {
        double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl > MAX_ROT_PER_CORRECTION) {
            double s = MAX_ROT_PER_CORRECTION / wl;
            wx *= s;
            wy *= s;
            wz *= s;
        }
        translate(tx, ty, tz);
        rotate(wx, wy, wz, cc.x + tx, cc.y + ty, cc.z + tz);
    }

    /** Positional rigid-body correction: moves point {@code p} along {@code j} (j = lambda * n). */
    void applyImpulse(double px, double py, double pz, double jx, double jy, double jz) {
        Vec3d cc = center(false, 1);
        double[] acc = new double[6];
        impulseTerms(acc, cc, px, py, pz, jx, jy, jz);
        applyRigid(acc[0], acc[1], acc[2], acc[3], acc[4], acc[5], cc);
    }

    double genInvMass(double px, double py, double pz, double nx, double ny, double nz) {
        Vec3d cc = center(false, 1);
        double rx = px - cc.x, ry = py - cc.y, rz = pz - cc.z;
        double ax = ry * nz - rz * ny, ay = rz * nx - rx * nz, az = rx * ny - ry * nx;
        double[] w = invI(ax, ay, az);
        return invMass + ax * w[0] + ay * w[1] + az * w[2];
    }

    double angularInvMass(double nx, double ny, double nz) {
        double[] w = invI(nx, ny, nz);
        return nx * w[0] + ny * w[1] + nz * w[2];
    }

    int probeCount() { return latticeProbes + attached.size(); }

    /** Writes current xyz and start-of-step xyz of collision probe {@code k} into {@code o}. */
    void probe(int k, double[] o) {
        if (k < latticeProbes) {
            double[] w = PROBES[k];
            double x = 0, y = 0, z = 0, px = 0, py = 0, pz = 0;
            for (int i = 0; i < 8; i++) {
                double wi = w[i];
                if (wi == 0) continue;
                var p = c[i];
                x += wi * p.x;
                y += wi * p.y;
                z += wi * p.z;
                px += wi * p.px;
                py += wi * p.py;
                pz += wi * p.pz;
            }
            o[0] = x;
            o[1] = y;
            o[2] = z;
            o[3] = px;
            o[4] = py;
            o[5] = pz;
        } else {
            var p = attached.get(k - latticeProbes);
            o[0] = p.x;
            o[1] = p.y;
            o[2] = p.z;
            o[3] = p.px;
            o[4] = p.py;
            o[5] = p.pz;
        }
    }

    void resetContacts() {
        int n = probeCount();
        if (contactDepth.length != n) {
            contactDepth = new double[n];
            contactFace = new int[n];
        } else Arrays.fill(contactDepth, 0);
    }

    /** Rotation of {@code ch} relative to {@code p} (conj(qp) * qc), with w >= 0. */
    static double[] relative(RigidBox p, RigidBox ch) {
        double aw = p.qw, ax = -p.qx, ay = -p.qy, az = -p.qz, bw = ch.qw, bx = ch.qx, by = ch.qy, bz = ch.qz;
        double w = aw * bw - ax * bx - ay * by - az * bz;
        double x = aw * bx + ax * bw + ay * bz - az * by;
        double y = aw * by - ax * bz + ay * bw + az * bx;
        double z = aw * bz + ax * by - ay * bx + az * bw;
        return w < 0 ? new double[]{-w, -x, -y, -z} : new double[]{w, x, y, z};
    }

    private double[] invI(double vx, double vy, double vz) {
        double rx = 0, ry = 0, rz = 0;
        for (int k = 0; k < 3; k++) {
            Vec3d a = axis[k];
            double s = invInertia[k] * (a.x * vx + a.y * vy + a.z * vz);
            rx += a.x * s;
            ry += a.y * s;
            rz += a.z * s;
        }
        return new double[]{rx, ry, rz};
    }

    private void premultiplyAxisAngle(double wx, double wy, double wz) {
        double ang = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (ang < 1e-12) return;
        double s = Math.sin(ang * .5) / ang;
        premultiply(Math.cos(ang * .5), wx * s, wy * s, wz * s);
    }

    private void premultiply(double dw, double dx, double dy, double dz) {
        double w = dw * qw - dx * qx - dy * qy - dz * qz;
        double x = dw * qx + dx * qw + dy * qz - dz * qy;
        double y = dw * qy - dx * qz + dy * qw + dz * qx;
        double z = dw * qz + dx * qy - dy * qx + dz * qw;
        double l = Math.sqrt(w * w + x * x + y * y + z * z);
        if (l < 1e-12 || !Double.isFinite(l)) return;
        qw = w / l;
        qx = x / l;
        qy = y / l;
        qz = z / l;
        rot = Mat3d.fromQuat(qw, qx, qy, qz);
        for (int k = 0; k < 3; k++) axis[k] = rot.transform(restAxis[k]);
    }

    private void snap(double cx, double cy, double cz) {
        for (int i = 0; i < 8; i++) place(c[i], cornerRest[i], cx, cy, cz);
        for (int i = 0; i < attached.size(); i++) place(attached.get(i), attachedRest.get(i), cx, cy, cz);
    }

    private void place(Particle p, Vec3d o, double cx, double cy, double cz) {
        p.x = cx + rot.get(0, 0) * o.x + rot.get(0, 1) * o.y + rot.get(0, 2) * o.z;
        p.y = cy + rot.get(1, 0) * o.x + rot.get(1, 1) * o.y + rot.get(1, 2) * o.z;
        p.z = cz + rot.get(2, 0) * o.x + rot.get(2, 1) * o.y + rot.get(2, 2) * o.z;
    }

    private void recover() {
        qw = pqw;
        qx = pqx;
        qy = pqy;
        qz = pqz;
        rot = Mat3d.fromQuat(qw, qx, qy, qz);
        for (int k = 0; k < 3; k++) axis[k] = rot.transform(restAxis[k]);
        snap(prevCenter.x, prevCenter.y, prevCenter.z);
        for (var p : c) { p.px = p.x; p.py = p.y; p.pz = p.z; }
        for (var p : attached) { p.px = p.x; p.py = p.y; p.pz = p.z; }
    }

    private static void turn(Particle p, Mat3d m, double ox, double oy, double oz) {
        double x = p.x - ox, y = p.y - oy, z = p.z - oz;
        p.x = ox + m.get(0, 0) * x + m.get(0, 1) * y + m.get(0, 2) * z;
        p.y = oy + m.get(1, 0) * x + m.get(1, 1) * y + m.get(1, 2) * z;
        p.z = oz + m.get(2, 0) * x + m.get(2, 1) * y + m.get(2, 2) * z;
    }

    private static void orthonormal(Vec3d[] e, Vec3d[] out) {
        Vec3d x = e[0].copy();
        if (x.length() < 1e-9) x.set(1, 0, 0);
        x.normalize();
        Vec3d y = e[1].copy();
        y.sub(x.copy().scale(x.dot(y)));
        if (y.length() < 1e-9) {
            Vec3d f = Math.abs(x.x) < .9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            y = f.sub(x.copy().scale(x.dot(f)));
        }
        y.normalize();
        out[0] = x;
        out[1] = y;
        out[2] = x.copy().cross(y);
    }

    /** Corners first (one-hot), then edge midpoints and face centres as trilinear corner weights. */
    private static double[][] buildProbes() {
        var out = new ArrayList<double[]>();
        for (int i = 0; i < 8; i++) {
            var w = new double[8];
            w[i] = 1;
            out.add(w);
        }
        double[] t = {0, .5, 1};
        for (int a = 0; a < 3; a++)
            for (int b = 0; b < 3; b++)
                for (int d = 0; d < 3; d++) {
                    if (a != 1 && b != 1 && d != 1) continue;
                    if (a == 1 && b == 1 && d == 1) continue;
                    var w = new double[8];
                    for (int i = 0; i < 8; i++)
                        w[i] = ((i & 1) != 0 ? t[a] : 1 - t[a])
                                * ((i >> 1 & 1) != 0 ? t[b] : 1 - t[b])
                                * ((i >> 2 & 1) != 0 ? t[d] : 1 - t[d]);
                    out.add(w);
                }
        return out.toArray(new double[0][]);
    }
}
