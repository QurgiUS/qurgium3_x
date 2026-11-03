package dev.ragdollfx.core.physics;

public final class Particle {
    public double x, y, z, px, py, pz, invMass = 1;
    public boolean grounded;

    public Particle(double a, double b, double c) { px = x = a; py = y = b; pz = z = c; }

    public void setVelocity(double a, double b, double c, double t) { px = x - a * t; py = y - b * t; pz = z - c * t; }

    public void addVelocity(double a, double b, double c, double t) { px -= a * t; py -= b * t; pz -= c * t; }
}
