package dev.ragdollfx.mc.render;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import org.joml.Matrix4f;

public final class RenderHook {
    public static void register() {
        // 26.3: no immediate buffers; add geometry to the frame's submit queue
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx ->
                RagdollRenderer.render(ctx.submitNodeCollector(), new Matrix4f(ctx.poseStack().last().pose())));
    }
}
