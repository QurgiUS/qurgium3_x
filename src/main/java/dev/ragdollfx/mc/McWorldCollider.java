package dev.ragdollfx.mc;

import dev.ragdollfx.core.physics.Aabb;
import dev.ragdollfx.core.physics.WorldCollider;
import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

public final class McWorldCollider implements WorldCollider {
    private static final Map<Long, List<AABB>> C = new HashMap<>();
    private final ClientLevel l;
    private final double x, y, z;
    private final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

    public McWorldCollider(ClientLevel l, double x, double y, double z) { this.l = l; this.x = x; this.y = y; this.z = z; }

    static void clearCache() { C.clear(); }

    public void boxesNear(double a, double b, double c, List<Aabb> o) {
        int i = (int) Math.floor(a + x), j = (int) Math.floor(b + y), k = (int) Math.floor(c + z);
        // Scan 3 blocks vertically: the block the particle is in, one below, and one above.
        // Previously only 2 were checked (y and y-1), so fast-falling large bodies could
        // clip through the floor when a corner entered a third block in one step.
        for (int d = -1; d <= 1; d++) put(i, j + d, k, o);
    }

    private void put(int i, int j, int k, List<Aabb> o) {
        for (var b : C.computeIfAbsent(BlockPos.asLong(i, j, k), q -> {
            p.set(i, j, k);
            if (!l.hasChunkAt(p)) return List.of();
            var s = l.getBlockState(p).getCollisionShape(l, p);
            return s.isEmpty() ? List.of() : s.move(i, j, k).toAabbs();
        })) o.add(new Aabb(b.minX - x, b.minY - y, b.minZ - z, b.maxX - x, b.maxY - y, b.maxZ - z));
    }
}
