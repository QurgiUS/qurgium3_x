package dev.ragdollfx.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ragdollfx.core.BloodEmitter;
import dev.ragdollfx.core.RagdollBuilder;
import dev.ragdollfx.core.config.RagdollConfig;
import dev.ragdollfx.core.geom.ModelSnapshot;
import dev.ragdollfx.core.physics.Aabb;
import dev.ragdollfx.mc.mixin.LivingEntityRendererInvoker;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class RagdollManager {
    private static final Map<Integer, Corpse> M = new HashMap<>();
    private static final ArrayDeque<Corpse> Q = new ArrayDeque<>();
    private static final Map<Integer, Entity> P = new HashMap<>();
    private static final Set<Integer> X = new HashSet<>();
    private static Object lv;
    private static boolean w;

    public static Collection<Corpse> active() { return Q; }

    public static void onExtract(LivingEntity e, LivingEntityRenderState s) {
        var d = (RagdollStateDuck) s;
        var cf = RagdollConfig.get();
        int i = e.getId();
        byte ph;
        if (!e.isDeadOrDying()) {
            M.remove(i);
            X.remove(i);
            ph = RagdollStateDuck.NONE;
        } else if (!cf.enabled) ph = RagdollStateDuck.NONE;
        else if (M.containsKey(i)) ph = RagdollStateDuck.HIDE;
        else if (X.contains(i) || s.isInvisible || e instanceof Player && !cf.playerRagdolls) ph = RagdollStateDuck.NONE;
        else {
            P.put(i, e);
            s.deathTime = 0;
            ph = RagdollStateDuck.CAPTURE;
        }
        d.ragdollfx$set(i, ph);
    }

    public static boolean shouldHide(LivingEntityRenderState s) { return ((RagdollStateDuck) s).ragdollfx$phase() == RagdollStateDuck.HIDE; }

    public static boolean tryCapture(LivingEntityRenderer<?, ?, ?> r, LivingEntityRenderState s, PoseStack ps) {
        var d = (RagdollStateDuck) s;
        Entity e = d.ragdollfx$phase() == RagdollStateDuck.CAPTURE ? P.remove(d.ragdollfx$entityId()) : null;
        if (e == null) return false;
        try {
            Vec3 cm = Minecraft.getInstance().gameRenderer.mainCamera().position();
            double x = e.getX(), y = e.getY(), z = e.getZ();
            var m = new Matrix4f().translation((float) (cm.x - x), (float) (cm.y - y), (float) (cm.z - z)).mul(ps.last().pose());
            var c = ModelCapture.capture(r.getModel().root(), m);
            var sn = new ModelSnapshot(c.snap);
            if (sn.parts.stream().allMatch(p -> p.cubes().isEmpty())) throw new IllegalStateException("no cubes");
            var rt = ((LivingEntityRendererInvoker) r).ragdollfx$getRenderType(s, true, false, s.appearsGlowing());
            if (rt == null) throw new IllegalStateException("no rt");

            var cf = RagdollConfig.get();
            Vec3 v = e.getDeltaMovement(), mv = e.position().subtract(e.xo, e.yo, e.zo);
            v = mv.lengthSqr() > v.lengthSqr() ? mv : v;
            long sd = e.getUUID().getMostSignificantBits() ^ System.nanoTime();
            var rd = RagdollBuilder.build(new RagdollBuilder.Input(sn, r.getModel().getClass().getSimpleName(), cf.dismember, cf.blood, sd, v.x * 20, Math.max(0, v.y) * 20, v.z * 20,
                    .8 + Math.min(1.5, v.length() * 2), cf.maxShardsPerCorpse));
            if (rd.bodies.isEmpty()) throw new IllegalStateException("empty");

            var k = new Corpse(e.getId(), x, y, z, rd, cf.blood ? new BloodEmitter(sd) : null, c.parts,
                    c.poses.toArray(PartPose[]::new), c.parentWorld.toArray(Matrix4f[]::new), c.ownWorld.toArray(Matrix4f[]::new),
                    c.hideLists(), rt, sn, e.getBbHeight(), e.level());
            M.put(k.entityId, k);
            Q.addLast(k);
            while (Q.size() > cf.maxRagdolls) drop(Q.peekFirst());
            return true;
        } catch (Throwable t) {
            X.add(e.getId());
            if (!w) System.err.println("[ragdollfx] vanilla death for " + e.getType() + ": " + t);
            w = true;
            return false;
        }
    }

    public static void tick(Minecraft mc) {
        if (mc.level != lv) { clear(); lv = mc.level; }
        if (mc.level == null || mc.isPaused()) return;
        if (!RagdollConfig.get().enabled) {
            if (!Q.isEmpty()) clear();
            return;
        }
        int life = RagdollConfig.get().corpseLifetimeSeconds;
        double md = mc.options.getEffectiveRenderDistance() * 16.0 + 32;
        Vec3 cm = mc.gameRenderer.mainCamera().position();
        Q.removeIf(c -> {
            boolean gone = c.ageSeconds > life || cm.distanceToSqr(c.ox, c.oy, c.oz) > md * md;
            if (gone) M.remove(c.entityId);
            return gone;
        });
        for (var c : Q) {
            var bb = new AABB(c.ox - 3, c.oy - 2, c.oz - 3, c.ox + 3, c.oy + 3, c.oz + 3);
            for (var o : mc.level.getEntities((Entity) null, bb, q -> q.getId() != c.entityId && (q instanceof LivingEntity l && l.isAlive() || q instanceof Projectile))) {
                var b = o.getBoundingBox();
                var v = o.position().subtract(o.xo, o.yo, o.zo).scale(20);
                c.ragdoll.push(new Aabb(b.minX - c.ox, b.minY - c.oy, b.minZ - c.oz, b.maxX - c.ox, b.maxY - c.oy, b.maxZ - c.oz), v.x, v.y, v.z, o.getX() - c.ox, o.getZ() - c.oz);
            }
            if (c.blood != null) c.blood.tick(c.ragdoll, (a, b, d, vx, vy, vz, big) -> McBlood.spawn(mc.level, c.ox + a, c.oy + b, c.oz + d, vx, vy, vz, big));
        }
        McWorldCollider.clearCache();
    }

    private static void drop(Corpse c) { Q.remove(c); M.remove(c.entityId); }

    public static void clear() { Q.clear(); M.clear(); P.clear(); X.clear(); }
}
