package dev.m4sh3r.linear.ai;

import org.bukkit.entity.Mob;

/** Decides, every few ticks, whether a mob should have its AI. Runs on the mob's own thread. */
public interface MobHandler {

    boolean handles(Mob mob);

    int interval();

    void evaluate(Mob mob);

    int wakeTicks();
}
