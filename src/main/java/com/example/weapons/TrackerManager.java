package com.example.weapons;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the tracker compass: creating it, remembering who it tracks, and keeping it pointed at them.
 *
 * Uses the "lodestone compass" trick (a lodestone position that isn't backed by a real block),
 * so the compass works in every dimension instead of spinning in the Nether/End.
 * If the target is in a different dimension, it points at their last known position in the holder's dimension.
 */
public class TrackerManager {

    private final WeaponPlugin plugin;
    private final NamespacedKey trackerKey;
    private final NamespacedKey targetKey;

    /** player -> (world uid -> last known location in that world) */
    private final Map<UUID, Map<UUID, Location>> lastKnown = new HashMap<>();
    private BukkitTask task;

    public TrackerManager(WeaponPlugin plugin) {
        this.plugin = plugin;
        this.trackerKey = new NamespacedKey(plugin, "tracker_compass");
        this.targetKey = new NamespacedKey(plugin, "tracker_target");
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        if (!isEnabled()) return;
        long interval = Math.max(1L, plugin.getConfig().getLong("tracker.update-interval", 20L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("tracker.enabled", true);
    }

    // ---------- Item ----------

    public ItemStack createCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) item.getItemMeta();
        meta.setDisplayName(color(plugin.getConfig().getString("tracker.name", "&6&lPlayer Tracker")));
        meta.setLore(baseLore());
        meta.getPersistentDataContainer().set(trackerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isTracker(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(trackerKey, PersistentDataType.BYTE);
    }

    public UUID getTarget(ItemStack item) {
        if (!isTracker(item)) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Sets (or clears, when target is null) the player a compass tracks. */
    public void setTarget(ItemStack item, UUID target) {
        CompassMeta meta = (CompassMeta) item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        List<String> lore = baseLore();

        if (target == null) {
            pdc.remove(targetKey);
            meta.setLodestone(null);
        } else {
            pdc.set(targetKey, PersistentDataType.STRING, target.toString());
            lore.add("");
            lore.add(color("&7Tracking: &f" + nameOf(target)));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    /**
     * Applies a target to the tracker compass the player is holding (main hand, then off hand, then anywhere in inventory).
     * @return true if a compass was found and updated
     */
    public boolean applyToPlayersTracker(Player player, UUID target) {
        PlayerInventory inv = player.getInventory();

        ItemStack main = inv.getItemInMainHand();
        if (isTracker(main)) {
            setTarget(main, target);
            inv.setItemInMainHand(main);
            updateHolder(player);
            return true;
        }

        ItemStack off = inv.getItemInOffHand();
        if (isTracker(off)) {
            setTarget(off, target);
            inv.setItemInOffHand(off);
            updateHolder(player);
            return true;
        }

        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isTracker(contents[i])) {
                setTarget(contents[i], target);
                inv.setItem(i, contents[i]);
                updateHolder(player);
                return true;
            }
        }
        return false;
    }

    // ---------- Updating ----------

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastKnown.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                    .put(p.getWorld().getUID(), p.getLocation().clone());
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateHolder(p);
        }
    }

    /** Points every tracker compass in the player's inventory at its target. */
    public void updateHolder(Player holder) {
        PlayerInventory inv = holder.getInventory();
        ItemStack[] contents = inv.getContents();

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            UUID targetId = getTarget(item);
            if (targetId == null) continue;

            Location dest = resolve(holder, targetId);
            CompassMeta meta = (CompassMeta) item.getItemMeta();
            Location current = meta.getLodestone();

            boolean changed = false;
            if (dest == null) {
                if (current != null) {
                    meta.setLodestone(null);
                    changed = true;
                }
            } else {
                boolean sameSpot = current != null
                        && current.getWorld() != null
                        && current.getWorld().equals(dest.getWorld())
                        && current.distanceSquared(dest) < 1.0;
                if (!sameSpot || meta.isLodestoneTracked()) {
                    meta.setLodestoneTracked(false);
                    meta.setLodestone(dest);
                    changed = true;
                }
            }

            if (changed) {
                item.setItemMeta(meta);
                inv.setItem(i, item);
            }
        }

        if (plugin.getConfig().getBoolean("tracker.show-actionbar", true)) {
            ItemStack main = inv.getItemInMainHand();
            ItemStack off = inv.getItemInOffHand();
            if (isTracker(main)) sendActionBar(holder, main);
            else if (isTracker(off)) sendActionBar(holder, off);
        }
    }

    /** Where should a compass held in the holder's current world point for this target? */
    private Location resolve(Player holder, UUID targetId) {
        World world = holder.getWorld();
        Player target = Bukkit.getPlayer(targetId);
        if (target != null && target.getWorld().equals(world)) {
            return target.getLocation();
        }
        Map<UUID, Location> known = lastKnown.get(targetId);
        return known == null ? null : known.get(world.getUID());
    }

    private void sendActionBar(Player holder, ItemStack item) {
        UUID targetId = getTarget(item);
        String text;

        if (targetId == null) {
            text = "&7Right-click to choose a player to track";
        } else {
            boolean showDistance = plugin.getConfig().getBoolean("tracker.show-distance", true);
            Player target = Bukkit.getPlayer(targetId);
            Location dest = resolve(holder, targetId);
            String name = nameOf(targetId);
            String info;

            if (target != null && target.getWorld().equals(holder.getWorld())) {
                info = showDistance ? (int) target.getLocation().distance(holder.getLocation()) + " blocks" : "tracking";
            } else if (target == null) {
                info = dest != null ? "offline, last seen " + distanceText(holder, dest, showDistance) : "offline";
            } else {
                info = "in the " + dimensionName(target.getWorld())
                        + (dest != null ? " - last known spot here" : " - no known spot here");
            }
            text = "&6Tracking &f" + name + " &7- " + info;
        }

        holder.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(color(text)));
    }

    private String distanceText(Player holder, Location dest, boolean showDistance) {
        if (!showDistance) return "nearby";
        return (int) dest.distance(holder.getLocation()) + " blocks away";
    }

    // ---------- Helpers ----------

    public String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : "Unknown";
    }

    public static String dimensionName(World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> "Overworld";
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> world.getName();
        };
    }

    private List<String> baseLore() {
        List<String> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("tracker.lore")) lore.add(color(line));
        return lore;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
