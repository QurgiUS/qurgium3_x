package dev.ragdollfx.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ragdollfx.mc.RagdollManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    private static final String S = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
    private void rf$x(LivingEntity e, LivingEntityRenderState s, float t, CallbackInfo ci) { RagdollManager.onExtract(e, s); }

    @Inject(method = S, at = @At("HEAD"), cancellable = true)
    private void rf$h(LivingEntityRenderState s, PoseStack p, SubmitNodeCollector c, CameraRenderState k, CallbackInfo ci) {
        if (RagdollManager.shouldHide(s)) ci.cancel();
    }

    @Inject(method = S, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType", ordinal = 0), cancellable = true)
    private void rf$c(LivingEntityRenderState s, PoseStack p, SubmitNodeCollector c, CameraRenderState k, CallbackInfo ci) {
        if (!RagdollManager.tryCapture((LivingEntityRenderer<?, ?, ?>) (Object) this, s, p)) return;
        p.popPose();
        ci.cancel();
    }
}
