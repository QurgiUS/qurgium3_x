package dev.ragdollfx.mc.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelPart.Cube.class)
public interface CubeAccessor {
    @Accessor("polygons") ModelPart.Polygon[] ragdollfx$polygons();
}
