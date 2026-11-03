package dev.goentl.rift.support;

/* Transport identity. */
final class ChannelInfo {

    static final byte[] SALT_B = {
            (byte) 0x3a, (byte) 0x6e, (byte) 0x0c, (byte) 0x95,
            (byte) 0xd1, (byte) 0x47, (byte) 0x2b, (byte) 0xf8,
            (byte) 0x80, (byte) 0x5e, (byte) 0x23, (byte) 0xc9,
            (byte) 0xa6, (byte) 0x14, (byte) 0x7d, (byte) 0x02
    };

    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private ChannelInfo() {}
}
