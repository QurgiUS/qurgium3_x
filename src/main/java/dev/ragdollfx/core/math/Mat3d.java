package dev.ragdollfx.core.math;

public final class Mat3d {
    public final double[] m = new double[9];

    public static Mat3d identity() {
        Mat3d r = new Mat3d();
        r.m[0] = r.m[4] = r.m[8] = 1;
        return r;
    }

    public static Mat3d fromAxes(Vec3d a, Vec3d b, Vec3d c) {
        Mat3d r = new Mat3d();
        Vec3d[] v = {a, b, c};
        for (int i = 0; i < 9; i++) r.m[i] = i % 3 == 0 ? v[i / 3].x : i % 3 == 1 ? v[i / 3].y : v[i / 3].z;
        return r;
    }

    /** Rotation matrix of a unit quaternion (w, x, y, z). */
    public static Mat3d fromQuat(double w, double x, double y, double z) {
        Mat3d r = new Mat3d();
        double xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        r.set(0, 0, 1 - 2 * (yy + zz)); r.set(0, 1, 2 * (xy - wz));     r.set(0, 2, 2 * (xz + wy));
        r.set(1, 0, 2 * (xy + wz));     r.set(1, 1, 1 - 2 * (xx + zz)); r.set(1, 2, 2 * (yz - wx));
        r.set(2, 0, 2 * (xz - wy));     r.set(2, 1, 2 * (yz + wx));     r.set(2, 2, 1 - 2 * (xx + yy));
        return r;
    }

    public Mat3d mulTransposed(Mat3d o) {
        Mat3d r = new Mat3d();
        for (int i = 0; i < 9; i++) {
            int c = i / 3, w = i % 3;
            r.m[i] = m[w] * o.m[c] + m[3 + w] * o.m[3 + c] + m[6 + w] * o.m[6 + c];
        }
        return r;
    }

    public Mat3d mul(Mat3d o) {
        Mat3d r = new Mat3d();
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 3; col++)
                r.set(row, col, get(row, 0) * o.get(0, col) + get(row, 1) * o.get(1, col) + get(row, 2) * o.get(2, col));
        return r;
    }

    public Vec3d transform(Vec3d v) {
        return new Vec3d(
            get(0, 0) * v.x + get(0, 1) * v.y + get(0, 2) * v.z,
            get(1, 0) * v.x + get(1, 1) * v.y + get(1, 2) * v.z,
            get(2, 0) * v.x + get(2, 1) * v.y + get(2, 2) * v.z);
    }

    public double get(int r, int c) { return m[c * 3 + r]; }

    public void set(int r, int c, double v) { m[c * 3 + r] = v; }
}