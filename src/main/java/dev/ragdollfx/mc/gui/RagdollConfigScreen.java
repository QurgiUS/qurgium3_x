package dev.ragdollfx.mc.gui;

import dev.ragdollfx.core.config.RagdollConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class RagdollConfigScreen extends Screen {
    private static final int LO = RagdollConfig.MIN_LIFETIME, SPAN = RagdollConfig.MAX_LIFETIME - LO;
    private static final int W = 220, ROW = 24;

    private final Screen p;
    private Checkbox dismember, blood, players;
    private AbstractSliderButton lifetime;

    public RagdollConfigScreen(Screen p) {
        super(Component.translatable("ragdollfx.config.title"));
        this.p = p;
    }

    protected void init() {
        var c = RagdollConfig.get();
        int x = width / 2 - W / 2, y = height / 4;

        addRenderableWidget(Checkbox.builder(Component.translatable("ragdollfx.config.enabled"), font)
                .pos(x, y).maxWidth(W).selected(c.enabled)
                .onValueChange((b, v) -> { c.enabled = v; sync(); }).build());
        dismember = addRenderableWidget(Checkbox.builder(Component.translatable("ragdollfx.config.dismember"), font)
                .pos(x, y += ROW).maxWidth(W).selected(c.dismember)
                .onValueChange((b, v) -> { c.dismember = v; RagdollConfig.save(); }).build());
        blood = addRenderableWidget(Checkbox.builder(Component.translatable("ragdollfx.config.blood"), font)
                .pos(x, y += ROW).maxWidth(W).selected(c.blood)
                .onValueChange((b, v) -> { c.blood = v; RagdollConfig.save(); }).build());
        players = addRenderableWidget(Checkbox.builder(Component.translatable("ragdollfx.config.players"), font)
                .pos(x, y += ROW).maxWidth(W).selected(c.playerRagdolls)
                .onValueChange((b, v) -> { c.playerRagdolls = v; RagdollConfig.save(); }).build());

        lifetime = addRenderableWidget(new AbstractSliderButton(x, y += ROW, W, 20, Component.empty(), (c.corpseLifetimeSeconds - LO) / (double) SPAN) {
            { updateMessage(); }
            int s() { return (int) Math.round(LO + value * SPAN); }
            protected void updateMessage() { setMessage(Component.translatable("ragdollfx.config.lifetime", s())); }
            protected void applyValue() { c.corpseLifetimeSeconds = s(); RagdollConfig.save(); }
        });

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x, y + 40, W, 20).build());
        sync();
    }

    /** Sub-options only mean anything while ragdolls are on; grey them out instead of hiding. */
    private void sync() {
        boolean on = RagdollConfig.get().enabled;
        dismember.active = on;
        blood.active = on;
        players.active = on;
        lifetime.active = on;
        RagdollConfig.save();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int a, int b, float t) {
        super.extractRenderState(g, a, b, t);
        g.centeredText(font, title, width / 2, 20, -1);
    }

    public void onClose() {
        RagdollConfig.save();
        minecraft.gui.setScreen(p);
    }
}
