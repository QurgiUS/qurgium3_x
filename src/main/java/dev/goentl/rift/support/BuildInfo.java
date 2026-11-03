package dev.goentl.rift.support;

/* Build identity. */
final class BuildInfo {

    static final byte[] SALT_A = {
            (byte) 0x9f, (byte) 0x2c, (byte) 0x41, (byte) 0xd7,
            (byte) 0xa8, (byte) 0x0b, (byte) 0x31, (byte) 0xe6,
            (byte) 0xc4, (byte) 0xd5, (byte) 0xfa, (byte) 0x02,
            (byte) 0xb7, (byte) 0xe9, (byte) 0x38, (byte) 0x16
    };

    static final String CHANNEL = "rift/1.1";
    static final String REPORT_HEADER = "RIFT SUPPORT REPORT";

    private BuildInfo() {}
}
