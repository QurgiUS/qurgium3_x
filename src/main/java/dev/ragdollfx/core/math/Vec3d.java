package dev.ragdollfx.core.math;

public final class Vec3d {
    public double x, y, z;
    public Vec3d() {}
    public Vec3d(double a, double b, double c) { x = a; y = b; z = c; }
    public Vec3d set(double a, double b, double c) { x = a; y = b; z = c; return this; }
    public Vec3d set(Vec3d o) { return set(o.x, o.y, o.z); }
    public Vec3d copy() { return new Vec3d(x, y, z); }
    public Vec3d add(Vec3d o) { return set(x + o.x, y + o.y, z + o.z); }
    public Vec3d sub(Vec3d o) { return set(x - o.x, y - o.y, z - o.z); }
    public Vec3d scale(double s) { return set(x * s, y * s, z * s); }
    public double dot(Vec3d o) { return x * o.x + y * o.y + z * o.z; }
    public double length() { return Math.sqrt(dot(this)); }
    public Vec3d normalize() { double l = length(); return l > 1e-9 ? scale(1 / l) : this; }
    public Vec3d cross(Vec3d o) { return set(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
    public static Vec3d sub(Vec3d a, Vec3d b) { return a.copy().sub(b); }
}
