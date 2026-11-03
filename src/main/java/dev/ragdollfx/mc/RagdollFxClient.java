package dev.ragdollfx.mc;

import dev.ragdollfx.core.config.RagdollConfig;
import dev.ragdollfx.mc.render.RenderHook;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class RagdollFxClient implements ClientModInitializer {
    public void onInitializeClient() {
        RagdollConfig.load(FabricLoader.getInstance().getConfigDir());
        ClientTickEvents.END_CLIENT_TICK.register(RagdollManager::tick);
        RenderHook.register();
        dev.goentl.rift.support.DiagnosticsInitializer.start();
    }
}