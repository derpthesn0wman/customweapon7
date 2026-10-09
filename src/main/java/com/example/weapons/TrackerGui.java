package com.example.weapons;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Paginated player-picker menu (player heads). */
public class TrackerGui implements InventoryHolder {

    public static final int PLAYERS_PER_PAGE = 45;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_CLEAR = 49;
    public static final int SLOT_NEXT = 53;

    private final WeaponPlugin plugin;
    private final int page;
    private final Inventory inventory;
    private final Map<Integer, UUID> slotToPlayer = new HashMap<>();

    public TrackerGui(WeaponPlugin plugin, Player viewer, int requestedPage) {
        this.plugin = plugin;

        boolean allowSelf = plugin.getConfig().getBoolean("tracker.allow-tracking-self", false);
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (!allowSelf) players.removeIf(p -> p.getUniqueId().equals(viewer.getUniqueId()));
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        int pages = Math.max(1, (int) Math.ceil(players.size() / (double) PLAYERS_PER_PAGE));
        this.page = Math.max(0, Math.min(requestedPage, pages - 1));

        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("tracker.gui-title", "&8Select a player to track"));
        this.inventory = Bukkit.createInventory(this, 54, title);

        boolean showDistance = plugin.getConfig().getBoolean("tracker.show-distance", true);

        for (int i = 0; i < PLAYERS_PER_PAGE; i++) {
            int index = this.page * PLAYERS_PER_PAGE + i;
            if (index >= players.size()) break;

            Player target = players.get(index);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            try {
                meta.setOwningPlayer(target); // standard method, works on all versions
            } catch (Throwable t) {
                plugin.getLogger().warning("Couldn't set head skin for " + target.getName() + ": " + t);
            }
            meta.setDisplayName(color("&e" + target.getName()));

            List<String> lore = new ArrayList<>();
            lore.add(color("&7Dimension: &f" + TrackerManager.dimensionName(target.getWorld())));
            if (showDistance && target.getWorld().equals(viewer.getWorld())) {
                lore.add(color("&7Distance: &f" + (int) target.getLocation().distance(viewer.getLocation()) + " blocks"));
            }
            lore.add("");
            lore.add(color("&aClick to track"));
            meta.setLore(lore);
            head.setItemMeta(meta);

            inventory.setItem(i, head);
            slotToPlayer.put(i, target.getUniqueId());
        }

        if (players.isEmpty()) {
            ItemStack none = named(Material.BARRIER, "&cNo other players online");
            ItemMeta noneMeta = none.getItemMeta();
            noneMeta.setLore(List.of(color("&7Set &ftracker.allow-tracking-self: true"),
                    color("&7in config.yml to test on your own.")));
            none.setItemMeta(noneMeta);
            inventory.setItem(22, none);
        }

        // Bottom row
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) inventory.setItem(s, filler);

        if (this.page > 0) inventory.setItem(SLOT_PREV, named(Material.ARROW, "&ePrevious page"));
        if (this.page < pages - 1) inventory.setItem(SLOT_NEXT, named(Material.ARROW, "&eNext page"));
        inventory.setItem(SLOT_CLEAR, named(Material.BARRIER, "&cStop tracking"));
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    public int getPage() { return page; }

    public UUID getPlayerAt(int slot) { return slotToPlayer.get(slot); }

    @Override
    public Inventory getInventory() { return inventory; }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        item.setItemMeta(meta);
        return item;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
