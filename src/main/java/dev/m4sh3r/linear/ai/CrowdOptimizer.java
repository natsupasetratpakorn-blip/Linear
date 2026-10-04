package dev.m4sh3r.linear.ai;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.config.LinearConfig;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Tameable;

/**
 * Switches off the AI of farm animals packed into pens. A cow in a crowd of fifty cannot
 * wander, but it still runs pathfinding, wander, look-around and tempt goals every tick.
 * Physics, aging, egg laying and drops keep working; feeding an animal wakes it up so
 * players can still breed it.
 */
public final class CrowdOptimizer implements MobHandler {

    private final Linear plugin;
    private final AiController ai;

    public CrowdOptimizer(Linear plugin, AiController ai) {
        this.plugin = plugin;
        this.ai = ai;
    }

    @Override
    public boolean handles(Mob mob) {
        return plugin.settings().crowd.types.contains(mob.getType());
    }

    @Override
    public int interval() {
        return plugin.settings().crowd.interval;
    }

    @Override
    public int wakeTicks() {
        return plugin.settings().crowd.wakeTicks;
    }

    @Override
    public void evaluate(Mob mob) {
        boolean ours = ai.isOurs(mob);
        if (!ours && !mob.isAware()) {
            return;
        }
        LinearConfig.Crowd c = plugin.settings().crowd;
        if (plugin.active(Linear.CROWD) && c.types.contains(mob.getType()) && shouldSleep(mob, c, ours)) {
            if (!ours) {
                ai.throttle(mob, AiController.Reason.CROWD);
            }
        } else if (ours) {
            ai.release(mob);
        }
    }

    private boolean shouldSleep(Mob mob, LinearConfig.Crowd c, boolean asleep) {
        if (ai.awake(mob) || mob.isLeashed()) {
            return false;
        }
        if (c.keepNamed && mob.customName() != null) {
            return false;
        }
        if (mob instanceof Tameable tameable && tameable.isTamed()) {
            return false;
        }
        int threshold = c.threshold;
        if (plugin.stressed()) {
            threshold = Math.min(threshold, plugin.settings().adaptive.crowdThreshold);
        }
        // Hysteresis: once asleep, only wake when the crowd has clearly thinned out.
        int needed = asleep ? Math.max(2, threshold - Math.max(1, threshold / 4)) : threshold;
        return countSameType(mob, c.radius, needed) >= needed;
    }

    /** Counts mobs of the same type in range, the mob itself included, stopping at {@code stopAt}. */
    private static int countSameType(Mob mob, double radius, int stopAt) {
        EntityType type = mob.getType();
        int count = 1;
        for (Entity other : mob.getNearbyEntities(radius, radius, radius)) {
            if (other.getType() == type && ++count >= stopAt) {
                break;
            }
        }
        return count;
    }
}
