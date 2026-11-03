package dev.ragdollfx.mc;

import dev.ragdollfx.core.BloodEmitter;
import dev.ragdollfx.core.geom.ModelSnapshot;
import dev.ragdollfx.core.physics.Ragdoll;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;

public final class Corpse {
    public final int entityId;
    public final double ox, oy, oz;
    public final Ragdoll ragdoll;
    public final BloodEmitter blood;
    public final List<ModelPart> parts;
    public final PartPose[] poses;
    public final Matrix4f[] parentWorld, ownWorld;
    public final int[][] hideWhenDrawing;
    public final RenderType renderType;
    public final ModelSnapshot snapshot;
    public final float height;
    public final Object levelRef;
    public double ageSeconds;

    public Corpse(int id, double x, double y, double z, Ragdoll r, BloodEmitter b, List<ModelPart> ps, PartPose[] pp,
                  Matrix4f[] pw, Matrix4f[] ow, int[][] h, RenderType t, ModelSnapshot s, float hh, Object l) {
        entityId = id; ox = x; oy = y; oz = z; ragdoll = r; blood = b; parts = ps; poses = pp;
        parentWorld = pw; ownWorld = ow; hideWhenDrawing = h; renderType = t; snapshot = s; height = hh; levelRef = l;
    }
}
