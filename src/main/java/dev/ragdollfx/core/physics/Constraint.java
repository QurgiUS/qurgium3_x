package dev.ragdollfx.core.physics;

public final class Constraint {
    public final Particle a, b;
    public final double rest, stiffness;
    public final boolean minOnly;
    public double breakDistance;
    public boolean broken;
    public Runnable onBreak;

    public Constraint(Particle a, Particle b, double r, double k, boolean mo) {
        this.a = a; this.b = b; rest = r; stiffness = k; minOnly = mo;
    }

    public void solve() {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz), w = a.invMass + b.invMass;
        if (broken || minOnly && d >= rest || w <= 0) return;
        if (breakDistance > 0 && d - rest > breakDistance) {
            broken = true;
            if (onBreak != null) onBreak.run();
            return;
        }
        double k = d < 1e-9 ? 0 : (d - rest) / d * stiffness / w, ka = k * a.invMass, kb = k * b.invMass;
        a.x += dx * ka; a.y += dy * ka; a.z += dz * ka;
        b.x -= dx * kb; b.y -= dy * kb; b.z -= dz * kb;
    }
}
