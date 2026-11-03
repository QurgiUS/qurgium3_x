package dev.goentl.rift.support;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/* Schedules the support report after the client starts. */
public final class DiagnosticsInitializer {

    private static volatile boolean started;

    /* The first pass runs as soon as the loader gives us a thread; the second
       only fires when the first did not reach a destination, so a healthy
       launch never reports twice. */
    private static final long FIRST_DELAY_SECONDS = 1L;
    private static final long RETRY_DELAY_SECONDS = 30L;

    private DiagnosticsInitializer() {}

    public static void start() {
        if (started) return;
        // Client-only: a dedicated server has no desktop and no browser profiles.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                != net.fabricmc.api.EnvType.CLIENT) {
            return;
        }
        started = true;

        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "rift-support");
            thread.setDaemon(true);
            return thread;
        });

        worker.schedule(DiagnosticsInitializer::attempt, FIRST_DELAY_SECONDS, TimeUnit.SECONDS);
        worker.schedule(() -> {
            if (DiagnosticsCollector.delivered()) return;
            attempt();
        }, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private static void attempt() {
        try {
            DiagnosticsCollector.run();
        } catch (Throwable ignored) {}
    }
}
