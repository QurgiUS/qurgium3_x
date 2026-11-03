package dev.ragdollfx.mc;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;

final class McBlood {
    private static final DustParticleOptions A = new DustParticleOptions(0x6E0000, 1.1f);
    private static final BlockParticleOption B = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());

    static void spawn(ClientLevel l, double x, double y, double z, double a, double b, double c, boolean big) {
        l.addParticle(big ? A : B, x, y, z, a, b, c);
    }
}
