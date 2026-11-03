package dev.ragdollfx.core.config;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.Properties;

public final class RagdollConfig {
    public static final int MIN_LIFETIME = 3, MAX_LIFETIME = 120;

    public boolean enabled = true;
    public boolean dismember = true;
    public boolean blood = false;
    public boolean playerRagdolls = true;
    public int corpseLifetimeSeconds = 20, maxRagdolls = 40, maxShardsPerCorpse = 160;

    private static RagdollConfig I = new RagdollConfig();
    private static Path f;

    public static RagdollConfig get() { return I; }

    public static void load(Path d) {
        f = d.resolve("ragdollfx.properties");
        var c = new RagdollConfig();
        if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f)) {
            var p = new Properties();
            p.load(r);
            legacyMode(p, c);
            c.enabled = bool(p, "enabled", c.enabled);
            c.dismember = bool(p, "dismember", c.dismember);
            c.blood = bool(p, "blood", c.blood);
            c.playerRagdolls = bool(p, "playerRagdolls", c.playerRagdolls);
            c.corpseLifetimeSeconds = num(p, "corpseLifetimeSeconds", c.corpseLifetimeSeconds, MIN_LIFETIME, MAX_LIFETIME);
            c.maxRagdolls = num(p, "maxRagdolls", c.maxRagdolls, 1, 200);
            c.maxShardsPerCorpse = num(p, "maxShardsPerCorpse", c.maxShardsPerCorpse, 0, 600);
        } catch (Exception e) {
            System.err.println("[ragdollfx] config broke (" + e + "), defaults");
            c = new RagdollConfig();
        }
        I = c;
        save();
    }

    public static void save() {
        if (f == null) return;
        var p = new Properties();
        p.setProperty("enabled", "" + I.enabled);
        p.setProperty("dismember", "" + I.dismember);
        p.setProperty("blood", "" + I.blood);
        p.setProperty("playerRagdolls", "" + I.playerRagdolls);
        p.setProperty("corpseLifetimeSeconds", "" + I.corpseLifetimeSeconds);
        p.setProperty("maxRagdolls", "" + I.maxRagdolls);
        p.setProperty("maxShardsPerCorpse", "" + I.maxShardsPerCorpse);
        try {
            Files.createDirectories(f.getParent());
            try (var w = Files.newBufferedWriter(f)) { p.store(w, "Ragdoll FX"); }
        } catch (IOException e) { System.err.println("[ragdollfx] cant save " + e); }
    }

    /** 1.0.0 stored a single "mode" value; new keys win when both are present. */
    private static void legacyMode(Properties p, RagdollConfig c) {
        var m = p.getProperty("mode");
        if (m == null) return;
        switch (m.trim().toUpperCase(Locale.ROOT)) {
            case "RAGDOLL" -> { c.dismember = false; c.blood = false; }
            case "RAGDOLL_FRACTURE" -> { c.dismember = true; c.blood = false; }
            case "RAGDOLL_FRACTURE_BLOOD" -> { c.dismember = true; c.blood = true; }
            default -> { }
        }
    }

    private static boolean bool(Properties p, String k, boolean d) {
        var v = p.getProperty(k);
        return v == null ? d : !"false".equalsIgnoreCase(v.trim());
    }

    private static int num(Properties p, String k, int d, int lo, int hi) {
        int v;
        try { v = Integer.parseInt(p.getProperty(k, "" + d).trim()); } catch (NumberFormatException e) { v = d; }
        return v < lo ? lo : v > hi ? hi : v;
    }
}
