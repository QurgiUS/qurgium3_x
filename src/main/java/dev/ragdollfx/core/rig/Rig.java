package dev.ragdollfx.core.rig;

import java.util.Locale;

/**
 * Explicit joint targets for entity models whose vanilla part hierarchy does not
 * match the skeleton the corpse should use.
 *
 * <p>Most models are fine with "joint to the model parent". These are the ones
 * that are not: a ravager's head hangs off its neck in the model but the neck is
 * part of the head in anatomy; a camel's neck is three stacked parts that fold
 * onto the body; a squid's eight tentacles are siblings of a body that sits in
 * the middle of the list; the wither's heads hang off a ribcage, not the model
 * root. Linking by model parent there produces a corpse that bends at the wrong
 * place, or parts that float free of the torso.
 *
 * <p>Table format: {@code {modelKey, child, parent}}. {@code modelKey} is the
 * model class simple name as it appears at runtime; {@code child} may end in
 * {@code *} to match a family of parts (the camel has neck1/neck2, the squid has
 * tentacle1..8). Anything not listed keeps the model hierarchy.
 *
 * <p>Core package: no Minecraft types, no imports beyond {@link Locale}.
 */
public final class Rig {

    private static final String[][] EDGES = {
        // --- humanoid-ish -------------------------------------------------
        {"VillagerModel", "arms", "body"},
        {"BabyVillagerModel", "right_hand", "body"},
        {"BabyVillagerModel", "middlearm_r1", "body"},
        {"BabyVillagerModel", "bb_main", "body"},
        {"WitchModel", "hat2", "head"},
        {"WitchModel", "hat3", "head"},
        {"WitchModel", "hat4", "head"},
        {"WitchModel", "arms", "body"},
        {"IllagerModel", "arms", "body"},
        {"IllagerModel", "left_shoulder", "body"},
        {"VexModel", "cloth", "body"},
        {"VexModel", "right_wing", "body"},
        {"VexModel", "left_wing", "body"},
        {"EndermanModel", "hat", "head"},
        {"ZombieVillagerModel", "nose", "head"},
        {"BabyZombieVillagerModel", "nose", "head"},
        {"ArmorStandModel", "right_body_stick", "body"},
        {"ArmorStandModel", "left_body_stick", "body"},
        {"ArmorStandModel", "shoulder_stick", "body"},
        {"WardenModel", "right_tendril", "head"},
        {"WardenModel", "left_tendril", "head"},
        {"WardenModel", "left_ribcage", "body"},
        {"WardenModel", "right_ribcage", "body"},

        // --- quadrupeds ---------------------------------------------------
        {"WolfModel", "upper_body", "body"},
        {"AdultWolfModel", "upper_body", "body"},
        {"BabyWolfModel", "upper_body", "body"},
        {"RavagerModel", "neck", "head"},
        {"RavagerModel", "mouth", "head"},
        {"RavagerModel", "right_horn", "head"},
        {"RavagerModel", "left_horn", "head"},
        {"RavagerModel", "head_child", "head"},
        {"HoglinModel", "mane", "body"},
        {"HoglinModel", "right_horn", "head"},
        {"HoglinModel", "left_horn", "head"},
        {"HoglinModel", "right_ear", "head"},
        {"HoglinModel", "left_ear", "head"},
        {"BabyHoglinModel", "right_ear", "head"},
        {"BabyHoglinModel", "left_ear", "head"},
        {"GoatModel", "nose", "head"},
        {"GoatModel", "left_ear", "head"},
        {"GoatModel", "right_ear", "head"},
        {"BabyGoatModel", "HeadMain", "head"},
        {"BabyGoatModel", "left_horn", "HeadMain"},
        {"BabyGoatModel", "right_horn", "HeadMain"},
        {"GoatModel", "left_horn", "head"},
        {"GoatModel", "right_horn", "head"},
        {"SheepModel", "head", "body"},
        {"BabySheepModel", "head", "body"},
        {"AdultRabbitModel", "right_ear", "head"},
        {"AdultRabbitModel", "left_ear", "head"},
        {"BabyRabbitModel", "right_ear", "head"},
        {"BabyRabbitModel", "left_ear", "head"},
        {"BabyRabbitModel", "body_r1", "body"},
        {"AdultFoxModel", "nose", "head"},
        {"AdultFoxModel", "right_ear", "head"},
        {"AdultFoxModel", "left_ear", "head"},
        {"BabyFoxModel", "right_ear", "head"},
        {"BabyFoxModel", "left_ear", "head"},
        {"ArmadilloModel", "right_ear*", "head_cube"},
        {"ArmadilloModel", "left_ear*", "head_cube"},
        {"BabyArmadilloModel", "right_ear", "head_cube"},
        {"BabyArmadilloModel", "left_ear", "head_cube"},
        {"ArmadilloModel", "tail", "body"},
        {"SnifferModel", "nose", "head"},
        {"SnifferModel", "lower_beak", "head"},
        {"SnifferModel", "right_ear", "head"},
        {"SnifferModel", "left_ear", "head"},

        // --- long-necked quadrupeds --------------------------------------
        {"CamelModel", "head", "neck2"},
        {"CamelModel", "right_ear", "neck2"},
        {"CamelModel", "left_ear", "neck2"},
        {"CamelModel", "neck1", "neck2"},
        {"CamelModel", "neck2", "body"},
        {"CamelModel", "hump", "body"},
        {"CamelModel", "tail", "body"},
        {"BabyCamelModel", "head", "neck2"},
        {"BabyCamelModel", "right_ear", "neck2"},
        {"BabyCamelModel", "left_ear", "neck2"},
        {"BabyCamelModel", "neck1", "neck2"},
        {"BabyCamelModel", "neck2", "body"},
        {"BabyCamelModel", "tail", "body"},
        {"LlamaModel", "left_ear", "neck"},
        {"LlamaModel", "right_ear", "neck"},
        {"BabyLlamaModel", "left_ear", "head"},
        {"BabyLlamaModel", "right_ear", "head"},
        {"HorseModel", "left_ear", "head_parts"},
        {"HorseModel", "right_ear", "head_parts"},
        {"HorseModel", "head", "head_parts"},
        {"HorseModel", "mane", "head_parts"},
        {"BabyDonkeyModel", "left_ear", "neck"},
        {"BabyDonkeyModel", "right_ear", "neck"},
        {"BabyDonkeyModel", "head", "neck"},

        // --- birds / fish / flying ---------------------------------------
        {"AdultChickenModel", "beak", "head"},
        {"AdultChickenModel", "red_thing", "head"},
        {"BabyChickenModel", "body", "body"},
        {"ParrotModel", "beak1", "head"},
        {"ParrotModel", "beak2", "head"},
        {"ParrotModel", "feather", "head"},
        {"ParrotModel", "head2", "head"},
        {"BatModel", "right_ear", "head"},
        {"BatModel", "left_ear", "head"},
        {"BatModel", "right_wing_tip", "right_wing"},
        {"BatModel", "left_wing_tip", "left_wing"},
        {"BeeModel", "front_legs", "body"},
        {"BeeModel", "middle_legs", "body"},
        {"BeeModel", "back_legs", "body"},
        {"BeeModel", "right_wing", "body"},
        {"BeeModel", "left_wing", "body"},
        {"BeeModel", "right_antenna", "body"},
        {"BeeModel", "left_antenna", "body"},
        {"BeeModel", "stinger", "body"},
        {"PhantomModel", "right_wing_tip", "right_wing_base"},
        {"PhantomModel", "left_wing_tip", "left_wing_base"},
        {"PhantomModel", "tail_tip", "tail_base"},
        {"CodModel", "nose", "head"},
        {"CodModel", "tail_fin", "body"},
        {"CodModel", "left_fin", "body"},
        {"CodModel", "right_fin", "body"},
        {"CodModel", "top_fin", "body"},
        {"SalmonModel", "top_front_fin", "body_front"},
        {"SalmonModel", "top_back_fin", "body_back"},
        {"SalmonModel", "back_fin", "body_back"},
        {"SalmonModel", "left_fin", "body_front"},
        {"SalmonModel", "right_fin", "body_front"},
        {"PufferfishSmallModel", "right_eye", "body"},
        {"PufferfishSmallModel", "left_eye", "body"},
        {"PufferfishSmallModel", "left_fin", "body"},
        {"PufferfishSmallModel", "right_fin", "body"},
        {"PufferfishSmallModel", "back_fin", "body"},
        {"DolphinModel", "nose", "head"},
        {"DolphinModel", "tail", "body"},
        {"DolphinModel", "tail_fin", "tail"},
        {"SquidModel", "tentacle*", "body"},
        {"GhastModel", "tentacle*", "body"},
        {"HappyGhastModel", "tentacle*", "body"},
        {"HappyGhastModel", "inner_body", "body"},
        {"AllayModel", "right_wing", "body"},
        {"AllayModel", "left_wing", "body"},
        {"AllayModel", "body2", "body"},
        {"TadpoleModel", "tail", "body"},

        // --- amphibians / reptiles ---------------------------------------
        {"FrogModel", "left_foot", "left_leg"},
        {"FrogModel", "right_foot", "right_leg"},
        {"FrogModel", "left_hand", "left_arm"},
        {"FrogModel", "right_hand", "right_arm"},
        {"FrogModel", "left_eye", "body"},
        {"FrogModel", "right_eye", "body"},
        {"FrogModel", "tongue", "body"},
        {"FrogModel", "head", "body"},
        {"FrogModel", "croaking_body", "body"},
        {"AdultAxolotlModel", "left_gills", "head"},
        {"AdultAxolotlModel", "right_gills", "head"},
        {"AdultAxolotlModel", "top_gills", "head"},
        {"BabyAxolotlModel", "left_gills", "head"},
        {"BabyAxolotlModel", "right_gills", "head"},
        {"BabyAxolotlModel", "top_gills", "head"},

        // --- arthropods / segmented --------------------------------------
        {"SpiderModel", "body1", "body0"},
        {"SpiderModel", "right_front_leg", "body0"},
        {"SpiderModel", "right_middle_front_leg", "body0"},
        {"SpiderModel", "right_middle_hind_leg", "body0"},
        {"SpiderModel", "right_hind_leg", "body0"},
        {"SpiderModel", "left_front_leg", "body0"},
        {"SpiderModel", "left_middle_front_leg", "body0"},
        {"SpiderModel", "left_middle_hind_leg", "body0"},
        {"SpiderModel", "left_hind_leg", "body0"},
        {"EndermiteModel", "segment0", "segment1"},
        {"EndermiteModel", "segment2", "segment1"},
        {"EndermiteModel", "segment3", "segment2"},
        {"SilverfishModel", "segment0", "segment1"},
        {"SilverfishModel", "segment1", "segment2"},
        {"SilverfishModel", "segment3", "segment2"},
        {"SilverfishModel", "layer0", "segment2"},
        {"SilverfishModel", "layer2", "segment1"},
        {"SilverfishModel", "layer1", "segment4"},

        // --- golems / bosses ---------------------------------------------
        {"IronGolemModel", "head", "lower_body"},
        {"IronGolemModel", "nose", "head"},
        {"IronGolemModel", "body", "lower_body"},
        {"SnowGolemModel", "pumpkin", "head"},
        {"SnowGolemModel", "upper_body", "lower_body"},
        {"GuardianModel", "tail*", "head"},
        {"GuardianModel", "spike*", "head"},
        {"GuardianModel", "eye", "head"},
        {"WitherBossModel", "tail", "ribcage"},
        {"WitherBossModel", "left_head", "ribcage"},
        {"WitherBossModel", "right_head", "ribcage"},
        {"WitherBossModel", "center_head", "ribcage"},
        {"WitherBossModel", "shoulders", "ribcage"},

        // --- ender dragon -------------------------------------------------
        {"EnderDragonModel", "neck1", "neck0"},
        {"EnderDragonModel", "neck2", "neck1"},
        {"EnderDragonModel", "neck3", "neck2"},
        {"EnderDragonModel", "neck4", "neck3"},
        {"EnderDragonModel", "head", "neck4"},
        {"EnderDragonModel", "jaw", "head"},
        {"EnderDragonModel", "neck0", "body"},
        {"EnderDragonModel", "tail1", "tail0"},
        {"EnderDragonModel", "tail2", "tail1"},
        {"EnderDragonModel", "tail3", "tail2"},
        {"EnderDragonModel", "tail4", "tail3"},
        {"EnderDragonModel", "tail5", "tail4"},
        {"EnderDragonModel", "tail6", "tail5"},
        {"EnderDragonModel", "tail7", "tail6"},
        {"EnderDragonModel", "tail8", "tail7"},
        {"EnderDragonModel", "tail9", "tail8"},
        {"EnderDragonModel", "tail10", "tail9"},
        {"EnderDragonModel", "tail11", "tail10"},
        {"EnderDragonModel", "tail0", "body"},
        {"EnderDragonModel", "right_wing_tip", "right_wing"},
        {"EnderDragonModel", "left_wing_tip", "left_wing"},
        {"EnderDragonModel", "right_front_leg_tip", "right_front_leg"},
        {"EnderDragonModel", "right_hind_leg_tip", "right_hind_leg"},
        {"EnderDragonModel", "left_front_leg_tip", "left_front_leg"},
        {"EnderDragonModel", "left_hind_leg_tip", "left_hind_leg"},
        {"EnderDragonModel", "right_front_foot", "right_front_leg_tip"},
        {"EnderDragonModel", "right_hind_foot", "right_hind_leg_tip"},
        {"EnderDragonModel", "left_front_foot", "left_front_leg_tip"},
        {"EnderDragonModel", "left_hind_foot", "left_hind_leg_tip"},

        // --- striders -----------------------------------------------------
        {"AdultStriderModel", "right_top_bristle", "body"},
        {"AdultStriderModel", "right_middle_bristle", "body"},
        {"AdultStriderModel", "right_bottom_bristle", "body"},
        {"AdultStriderModel", "left_top_bristle", "body"},
        {"AdultStriderModel", "left_middle_bristle", "body"},
        {"AdultStriderModel", "left_bottom_bristle", "body"},
    };

    private static final String[][] CAPS = {};

    private Rig() {}

    /**
     * Maximum number of simulated parts for a model, or {@code -1} when the
     * capture is already the right size. These models carry extra meshes that
     * the renderer draws but a corpse should not simulate.
     */
    public static int cap(String model) {
        if (model == null) return -1;
        String key = simple(model);
        for (String[] e : CAPS) if (simple(e[0]).equals(key)) return Integer.parseInt(e[1]);
        return -1;
    }

    /**
     * Joint target for {@code part} in {@code model}, or {@code null} when the
     * model hierarchy is already correct.
     */
    public static String parentOf(String model, String part) {
        if (model == null || part == null) return null;
        String key = simple(model);
        String name = part.toLowerCase(Locale.ROOT);
        for (String[] e : EDGES) {
            if (!simple(e[0]).equals(key)) continue;
            String child = e[1].toLowerCase(Locale.ROOT);
            boolean hit = child.endsWith("*")
                    ? name.startsWith(child.substring(0, child.length() - 1))
                    : name.equals(child);
            if (hit) return e[2];
        }
        return null;
    }

    /** True when the part takes its joint target from this table. */
    public static boolean overrides(String model, String part) {
        return parentOf(model, part) != null;
    }

    private static String simple(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }
}
