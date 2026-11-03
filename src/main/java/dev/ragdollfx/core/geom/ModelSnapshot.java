package dev.ragdollfx.core.geom;

import java.util.List;

public final class ModelSnapshot {
    public record Part(int index, int parent, String name, double[] world, List<Cube> cubes) {}
    public record Cube(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, List<Face> faces) {}
    public record Face(float[][] pos, float[][] uv, float nx, float ny, float nz) {}

    public final List<Part> parts;

    public ModelSnapshot(List<Part> p) { parts = p; }

    public static double[] transform(double[] m, double x, double y, double z) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) r[i] = m[i] * x + m[4 + i] * y + m[8 + i] * z + m[12 + i];
        return r;
    }
}
