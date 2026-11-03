package dev.ragdollfx.mc.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.ragdollfx.mc.gui.RagdollConfigScreen;

public final class ModMenuIntegration implements ModMenuApi {
    public ConfigScreenFactory<?> getModConfigScreenFactory() { return RagdollConfigScreen::new; }
}
