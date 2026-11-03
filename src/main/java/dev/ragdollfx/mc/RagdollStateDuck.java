package dev.ragdollfx.mc;

public interface RagdollStateDuck {
    byte NONE = 0, CAPTURE = 1, HIDE = 2;
    int ragdollfx$entityId();
    byte ragdollfx$phase();
    void ragdollfx$set(int id, byte p);
}
