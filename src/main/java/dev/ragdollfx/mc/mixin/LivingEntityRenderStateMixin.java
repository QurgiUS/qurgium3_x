package dev.ragdollfx.mc.mixin;

import dev.ragdollfx.mc.RagdollStateDuck;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public class LivingEntityRenderStateMixin implements RagdollStateDuck {
    @Unique private int rf$i = -1;
    @Unique private byte rf$p;
    public int ragdollfx$entityId() { return rf$i; }
    public byte ragdollfx$phase() { return rf$p; }
    public void ragdollfx$set(int i, byte p) { rf$i = i; rf$p = p; }
}
