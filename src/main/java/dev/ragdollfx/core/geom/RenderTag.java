package dev.ragdollfx.core.geom;

import java.util.List;

public sealed interface RenderTag {
    record WholePart(int partIndex) implements RenderTag {}
    record Shard(int partIndex, List<ModelSnapshot.Face> quads) implements RenderTag {}
}
