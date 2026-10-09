package com.example.weapons;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles "the sift": creating/loading the world and sending people there and back.
 * Settings live under "sift:" in config.yml.
 */
public class SiftManager implements Listener {

    private final WeaponPlugin plugin;
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, BukkitTask> returnTasks = new HashMap<>();

    public SiftManager(WeaponPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- The world ----------

    public String getWorldName() {
        return plugin.getConfig().getString("sift.world", "the_sift");
    }

    /**
     * Returns the sift world. When create is true it is created (or loaded from disk) if it isn't loaded yet.
     * Returns null if it doesn't exist and couldn't be created.
     */
    public World getWorld(boolean create) {
        String name = getWorldName();
        World world = Bukkit.getWorld(name);
        if (world != null || !create) return world;

        boolean folderExists = new File(Bukkit.getWorldContainer(), name).isDirectory();
        if (!folderExists && !plugin.getConfig().getBoolean("sift.create-if-missing", true)) return null;

        WorldCreator creator = new WorldCreator(name);
        try {
            creator.environment(World.Environment.valueOf(
                    plugin.getConfig().getString("sift.environment", "NORMAL").toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("sift.environment must be NORMAL, NETHER or THE_END - using NORMAL.");
        }
        try {
            creator.type(WorldType.valueOf(
                    plugin.getConfig().getString("sift.type", "NORMAL").toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("sift.type must be NORMAL, FLAT, LARGE_BIOMES or AMPLIFIED - using NORMAL.");
        }

        try {
            plugin.getLogger().info("Creating/loading world '" + name + "'...");
            return creator.createWorld();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't create the world '" + name + "'", e);
            return null;
        }
    }

    /** Where people arrive in the sift. */
    public Location getSpawn(World world) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("sift.spawn");
        if (s != null) {
            return new Location(world, s.getDouble("x", 0), s.getDouble("y", 100), s.getDouble("z", 0),
                    (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
        }
        Location loc = world.getSpawnLocation().add(0.5, 0, 0.5);
        if (world.getEnvironment() == World.Environment.NORMAL) {
            loc.setY(world.getHighestBlockYAt(loc) + 1.0);
        }
        return loc;
    }

    // ---------- Sending someone else (on-hit) ----------

    /** Sends the victim to the sift. Returns false if it couldn't be done (so no cooldown is spent). */
    public boolean send(Player attacker, LivingEntity victim) {
        World world = getWorld(true);
        WeaponManager messages = plugin.getWeaponManager();
        if (world == null) {
            messages.message(attacker, "sift-missing", "{world}", getWorldName());
            return false;
        }
        if (victim.getWorld().equals(world)) return false; // already there

        Location before = victim.getLocation().clone();
        effectsAt(before);
        if (!victim.teleport(getSpawn(world))) return false;
        effectsAt(victim.getLocation());

        returnLocations.put(victim.getUniqueId(), before);
        scheduleReturn(victim.getUniqueId());

        messages.message(attacker, "sift-sent-attacker", "{target}", victim.getName());
        if (victim instanceof Player p) {
            messages.message(p, "sift-sent-victim", "{player}", attacker.getName());
        }
        return true;
    }

    // ---------- Sending yourself (right-click) ----------

    /** Right-click: go to the sift, or (if you're already there) come back. */
    public boolean enter(Player player) {
        World world = getWorld(true);
        WeaponManager messages = plugin.getWeaponManager();
        if (world == null) {
            messages.message(player, "sift-missing", "{world}", getWorldName());
            return false;
        }

        if (player.getWorld().equals(world)) {
            if (!plugin.getConfig().getBoolean("sift.right-click-toggle", true)) return false;
            return returnHome(player.getUniqueId(), "sift-left");
        }

        Location before = player.getLocation().clone();
        effectsAt(before);
        if (!player.teleport(getSpawn(world))) return false;
        effectsAt(player.getLocation());

        returnLocations.put(player.getUniqueId(), before);
        messages.message(player, "sift-entered");
        return true;
    }

    // ---------- Coming back ----------

    private void scheduleReturn(UUID id) {
        double seconds = plugin.getConfig().getDouble("sift.return-after", 0);
        if (seconds <= 0) return;

        BukkitTask old = returnTasks.remove(id);
        if (old != null) old.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            returnTasks.remove(id);
            returnHome(id, "sift-returned");
        }, (long) (seconds * 20));
        returnTasks.put(id, task);
    }

    /** Sends an entity from the sift back to where it came from (or the main world's spawn). */
    private boolean returnHome(UUID id, String messageKey) {
        BukkitTask pending = returnTasks.remove(id);
        if (pending != null) pending.cancel();

        Entity e = Bukkit.getEntity(id);
        World sift = getWorld(false);
        if (!(e instanceof LivingEntity living) || sift == null || !living.getWorld().equals(sift)) return false;

        Location back = returnLocations.remove(id);
        if (back == null || back.getWorld() == null) {
            back = Bukkit.getWorlds().get(0).getSpawnLocation();
        }

        effectsAt(living.getLocation());
        if (!living.teleport(back)) return false;
        effectsAt(living.getLocation());

        if (living instanceof Player p) {
            plugin.getWeaponManager().message(p, messageKey);
        }
        return true;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        returnLocations.remove(id);
        BukkitTask t = returnTasks.remove(id);
        if (t != null) t.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (returnTasks.containsKey(id)) {
            returnHome(id, "sift-returned"); // don't leave them stranded when the timer would have brought them back
        }
    }

    // ---------- Visuals ----------

    /** Ominous teal/purple vortex used at both ends of every trip. */
    private void effectsAt(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        Location mid = loc.clone().add(0, 1, 0);
        Location ground = loc.clone().add(0, 0.2, 0);

        Fx.spawn(w, Particle.FLASH, mid, 1);
        Fx.burst(w, mid, 80, 0.5, Particle.SCULK_SOUL);
        Fx.burst(w, mid, 50, 0.4, Particle.REVERSE_PORTAL);
        Fx.burst(w, mid, 30, 0.25, Particle.SOUL_FIRE_FLAME);
        Fx.ringDust(w, ground, 1.6, Fx.n(40), Color.fromRGB(0, 170, 170), Color.fromRGB(25, 0, 45), 2.2f);
        Fx.shockwave(w, ground, 4, 8, Particle.SOUL_FIRE_FLAME);
        Fx.helix(w, loc, 1.0, 2.6, 16, Particle.SOUL_FIRE_FLAME, 3);

        w.playSound(mid, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.5f);
        w.playSound(mid, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.2f, 0.8f);
    }
}
