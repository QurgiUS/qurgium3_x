package dev.ragdollfx.core.fracture;

import java.util.*;

public final class FracturePlanner {
    public enum Kind { HEAD, LIMB, APPENDAGE, TORSO, OTHER }
    public enum Style { CLEAN_LIMBS, DECAPITATION, HALF_SHATTER, FULL_SHATTER, MESSY }
    public record Decision(Style style, Map<Integer, FracturePlan> plan) {}

    private static final String[][] W = {
        {"head", "skull", "jaw", "beak"},
        {"leg", "arm", "hand", "foot", "paw"},
        {"tail", "wing", "fin", "tentacle", "ear", "horn", "antler"}};

    public static Kind classify(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < W.length; i++) for (String w : W[i]) if (n.contains(w)) return Kind.values()[i];
        return n.equals("body") || n.equals("cube") || n.contains("torso") || n.contains("chest") ? Kind.TORSO : Kind.OTHER;
    }

    public static Decision plan(List<int[]> ch, boolean fr, Random r, double vi) {
        Map<Integer, FracturePlan> m = new HashMap<>();
        if (!fr) {
            ch.forEach(c -> m.put(c[0], FracturePlan.ATTACHED));
            return new Decision(null, m);
        }
        double x = r.nextDouble() / Math.max(.5, vi);
        Style st = x < .12 ? Style.FULL_SHATTER : x < .3 ? Style.HALF_SHATTER : x < .48 ? Style.DECAPITATION : x < .75 ? Style.CLEAN_LIMBS : Style.MESSY;
        for (int[] c : ch) {
            Kind k = Kind.values()[c[1]];
            double a = r.nextDouble(), b = r.nextDouble();
            m.put(c[0], switch (st) {
                case FULL_SHATTER -> a < .85 ? FracturePlan.SHATTERED : FracturePlan.DETACHED;
                case HALF_SHATTER -> k == Kind.TORSO || a < .35 ? FracturePlan.SHATTERED : b < .6 ? FracturePlan.DETACHED : FracturePlan.WEAK;
                case DECAPITATION -> k == Kind.HEAD || a < .2 ? FracturePlan.DETACHED : FracturePlan.WEAK;
                case CLEAN_LIMBS -> (k == Kind.LIMB || k == Kind.APPENDAGE) && a < .55 || k == Kind.HEAD && b < .3 ? FracturePlan.DETACHED : FracturePlan.WEAK;
                case MESSY -> a < .3 ? FracturePlan.DETACHED : a < .5 ? FracturePlan.SHATTERED : FracturePlan.WEAK;
            });
        }
        if (!ch.isEmpty() && !m.containsValue(FracturePlan.DETACHED) && !m.containsValue(FracturePlan.SHATTERED))
            m.put(ch.get(r.nextInt(ch.size()))[0], ch.size() < 2 ? FracturePlan.SHATTERED : FracturePlan.DETACHED);
        return new Decision(st, m);
    }
}
