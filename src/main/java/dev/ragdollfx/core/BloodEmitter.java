package dev.ragdollfx.core;

import dev.ragdollfx.core.physics.*;
import java.util.Random;

public final class BloodEmitter {
    public interface Sink { void spawn(double x, double y, double z, double vx, double vy, double vz, boolean big); }

    private int s;
    private final double[] a = new double[256];
    private final Random r;

    public BloodEmitter(long sd) { r = new Random(sd * 31 + 7); }

    public void tick(Ragdoll rd, Sink k) {
        int n = Math.min(rd.woundAnchors.size(), a.length);
        for (int i = 0; i < n; a[i++] += .05) {
            boolean f = i >= s;
            int c = f ? 10 + r.nextInt(8) : r.nextDouble() < Math.max(0, .9 - a[i] / 3) ? 1 : 0;
            double sp = f ? .125 : .02;
            for (var p : rd.woundAnchors.get(i))
                for (int j = 0; j < c; j++)
                    k.spawn(p.x, p.y, p.z, r.nextGaussian() * sp, (f ? .12 : 0) + r.nextDouble() * .05, r.nextGaussian() * sp, f && j % 3 == 0);
        }
        s = rd.woundAnchors.size();
    }
}
