package dev.m4sh3r.linear.monitor;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.config.LinearConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Measures how long ticks take and decides when the server is "stressed".
 *
 * <p>On Paper every tick's duration comes from {@link ServerTickEndEvent}. Folia ticks many
 * regions in parallel and has no single server tick, so there the monitor falls back to the
 * server's own averages when it offers them, and otherwise reports no data (adaptive mode
 * then simply stays off).
 */
public final class TickMonitor implements Listener {

    private static final int SAMPLES = 100; // 5 seconds

    private final Linear plugin;
    private final double[] durations = new double[SAMPLES];
    private int index;
    private int filled;
    private volatile long lastSample;

    private volatile double mspt = Double.NaN;
    private volatile double tps = Double.NaN;
    private volatile boolean stressed;
    private int above;
    private int below;

    public TickMonitor(Linear plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> update(), 20L, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        synchronized (durations) {
            durations[index] = event.getTickDuration();
            index = (index + 1) % SAMPLES;
            if (filled < SAMPLES) {
                filled++;
            }
        }
        lastSample = System.nanoTime();
    }

    private void update() {
        double avg = Double.NaN;
        if (System.nanoTime() - lastSample < 5_000_000_000L) {
            synchronized (durations) {
                double sum = 0;
                for (int i = 0; i < filled; i++) {
                    sum += durations[i];
                }
                avg = filled == 0 ? Double.NaN : sum / filled;
            }
        } else {
            try {
                avg = plugin.getServer().getAverageTickTime();
            } catch (Throwable ignored) {
                // Not available on this server type.
            }
        }
        mspt = avg;
        try {
            tps = plugin.getServer().getTPS()[0];
        } catch (Throwable ignored) {
            tps = Double.isNaN(avg) ? Double.NaN : Math.min(20.0, 1000.0 / Math.max(50.0, avg));
        }

        LinearConfig.Adaptive a = plugin.settings().adaptive;
        if (Double.isNaN(avg)) {
            above = below = 0;
            stressed = false;
            return;
        }
        if (avg > a.stressedMspt) {
            above++;
            below = 0;
        } else if (avg < a.recoveredMspt) {
            below++;
            above = 0;
        } else {
            above = below = 0;
        }
        if (!stressed && above >= 5) {
            stressed = true;
            plugin.getLogger().info(String.format("Server is overloaded (%.1f ms/tick): adaptive mode on.", avg));
        } else if (stressed && below >= 10) {
            stressed = false;
            plugin.getLogger().info(String.format("Server recovered (%.1f ms/tick): adaptive mode off.", avg));
        }
    }

    public double mspt() {
        return mspt;
    }

    public double tps() {
        return tps;
    }

    public boolean stressed() {
        return stressed;
    }
}
