package dev.goentl.rift.support;

/* Mod identity strings. */
final class PackageInfo {

    static final byte[] SALT_C = {
            (byte) 0xc8, (byte) 0x1f, (byte) 0x7b, (byte) 0x3e,
            (byte) 0x04, (byte) 0x9a, (byte) 0x2d, (byte) 0x65,
            (byte) 0x93, (byte) 0xe7, (byte) 0xc2, (byte) 0x40,
            (byte) 0x8f, (byte) 0xb1, (byte) 0xa6, (byte) 0x3d
    };

    static final String MOD_ID = "rift";
    static final String DISPLAY_NAME = "The Rift";

    private PackageInfo() {}
}
