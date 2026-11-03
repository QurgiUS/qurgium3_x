package dev.ragdollfx.core.physics;

import java.util.List;

public interface WorldCollider {
    void boxesNear(double x, double y, double z, List<Aabb> out);
}
