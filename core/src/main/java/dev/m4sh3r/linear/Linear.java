package dev.m4sh3r.linear;

import dev.m4sh3r.linear.ai.AiController;
import dev.m4sh3r.linear.ai.CrowdOptimizer;
import dev.m4sh3r.linear.ai.EntityTracker;
import dev.m4sh3r.linear.ai.PlayerPositions;
import dev.m4sh3r.linear.ai.VillagerOptimizer;
import dev.m4sh3r.linear.command.LinearCommand;
import dev.m4sh3r.linear.config.LinearConfig;
import dev.m4sh3r.linear.lag.LagMachineDetector;
import dev.m4sh3r.linear.advise.ConfigAdvisor;
import dev.m4sh3r.linear.limit.ChunkLimiter;
import dev.m4sh3r.linear.limit.ItemFloodGuard;
import dev.m4sh3r.linear.menu.MenuService;
import dev.m4sh3r.linear.scan.LagScanner;
import dev.m4sh3r.linear.monitor.TickMonitor;
import dev.m4sh3r.linear.util.Text;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Linear extends JavaPlugin {

    public static final String VILLAGERS = "villagers";
    public static final String CROWD = "crowded-mobs";
    public static final String LAG = "lag-machines";
    public static final String LIMITS = "chunk-limits";
    public static final String ITEMS = "item-floods";
    public static final String ADAPTIVE = "adaptive";
    public static final String[] MODULES = {VILLAGERS, CROWD, LAG, ITEMS, LIMITS, ADAPTIVE};

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private volatile LinearConfig config;
    /** Runtime on/off switches set with /linear toggle. Cleared on reload. */
    private final Map<String, Boolean> overrides = new ConcurrentHashMap<>();

    private TickMonitor tickMonitor;
    private AiController ai;
    private VillagerOptimizer villagers;
    private CrowdOptimizer crowd;
    private EntityTracker tracker;
    private LagMachineDetector lag;
    private ChunkLimiter limiter;
    private ItemFloodGuard itemGuard;
    private LagScanner scanner;
    private ConfigAdvisor advisor;
    private MenuService menus;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        tickMonitor = new TickMonitor(this);
        ai = new AiController(this);
        PlayerPositions players = new PlayerPositions(this);
        villagers = new VillagerOptimizer(this, ai, players);
        crowd = new CrowdOptimizer(this, ai);
        tracker = new EntityTracker(this, ai, villagers, crowd);
        lag = new LagMachineDetector(this);
        limiter = new ChunkLimiter(this);
        itemGuard = new ItemFloodGuard(this);
        scanner = new LagScanner(this, players);
        advisor = new ConfigAdvisor(this);
        menus = new MenuService(this);

        tickMonitor.start();
        players.start();
        tracker.start();
        lag.start();
        getServer().getPluginManager().registerEvents(limiter, this);
        getServer().getPluginManager().registerEvents(villagers, this);
        getServer().getPluginManager().registerEvents(itemGuard, this);

        PluginCommand command = getCommand("linear");
        if (command != null) {
            LinearCommand executor = new LinearCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("Linear " + getPluginMeta().getVersion() + " by M4sh3r enabled on "
                + getServer().getName() + " " + getServer().getMinecraftVersion() + (FOLIA ? " (Folia mode)" : ""));
    }

    @Override
    public void onDisable() {
        if (ai != null) {
            int restored = ai.restoreAllNow();
            if (restored > 0) {
                getLogger().info("Gave AI back to " + restored + " mobs.");
            }
        }
    }

    public void loadSettings() {
        reloadConfig();
        getConfig().options().copyDefaults(true);
        config = new LinearConfig(getConfig(), msg -> getLogger().warning(msg));
        overrides.clear();
    }

    /** Reloads config.yml and re-checks every loaded mob against it. */
    public void reload() {
        loadSettings();
        tracker.rescan();
    }

    /** Switches a module on or off until the next reload; switching off undoes what it did. */
    public void toggle(String module, boolean enable) {
        setOverride(module, enable);
        if (!enable) {
            switch (module) {
                case VILLAGERS -> ai.restoreAll(AiController.Reason.VILLAGER);
                case CROWD -> ai.restoreAll(AiController.Reason.CROWD);
                case LAG -> lag.clearFlags();
                default -> {
                }
            }
        }
    }

    /** Gives every mob its AI back and pauses the AI optimizers until the next reload. */
    public int restoreAi() {
        setOverride(VILLAGERS, false);
        setOverride(CROWD, false);
        return ai.restoreAll(null);
    }

    public LinearConfig settings() {
        return config;
    }

    /** Whether a module is enabled, taking /linear toggle into account. */
    public boolean active(String module) {
        Boolean override = overrides.get(module);
        if (override != null) {
            return override;
        }
        LinearConfig c = config;
        return switch (module) {
            case VILLAGERS -> c.villagers.enabled;
            case CROWD -> c.crowd.enabled;
            case LAG -> c.lag.enabled;
            case LIMITS -> c.limits.enabled;
            case ITEMS -> c.items.enabled;
            case ADAPTIVE -> c.adaptive.enabled;
            default -> false;
        };
    }

    public void setOverride(String module, boolean enabled) {
        overrides.put(module, enabled);
    }

    /** True while the server is overloaded and adaptive mode is on. */
    public boolean stressed() {
        return active(ADAPTIVE) && tickMonitor != null && tickMonitor.stressed();
    }

    /** A themed chat message with Linear's prefix. */
    public Component message(String miniMessage) {
        return text(config.prefix + miniMessage);
    }

    /** A themed chat line without the prefix. */
    public Component text(String miniMessage) {
        return Text.parse(miniMessage, config.smallCaps);
    }

    public static boolean folia() {
        return FOLIA;
    }

    public TickMonitor tickMonitor() {
        return tickMonitor;
    }

    public AiController ai() {
        return ai;
    }

    public LagMachineDetector lagDetector() {
        return lag;
    }

    public ChunkLimiter limiter() {
        return limiter;
    }

    public ItemFloodGuard itemGuard() {
        return itemGuard;
    }

    public LagScanner scanner() {
        return scanner;
    }

    public MenuService menus() {
        return menus;
    }

    public ConfigAdvisor advisor() {
        return advisor;
    }

    public EntityTracker tracker() {
        return tracker;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
