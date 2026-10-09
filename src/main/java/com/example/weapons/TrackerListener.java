package com.example.weapons;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;
import java.util.logging.Level;

public class TrackerListener implements Listener {

    private final WeaponPlugin plugin;
    private final WeaponManager messages;
    private final TrackerManager tracker;

    public TrackerListener(WeaponPlugin plugin, WeaponManager messages, TrackerManager tracker) {
        this.plugin = plugin;
        this.messages = messages;
        this.tracker = tracker;
    }

    /** Right-click the compass: open the GUI. Sneak + right-click: stop tracking. */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.getConfig().getBoolean("debug", false)
                && event.getItem() != null && event.getItem().getType() == Material.COMPASS) {
            plugin.getLogger().info("[debug] compass interact: hand=" + event.getHand()
                    + " action=" + event.getAction()
                    + " isTracker=" + tracker.isTracker(event.getItem())
                    + " alreadyCancelled=" + event.isCancelled());
        }

        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!tracker.isEnabled() || !tracker.isTracker(event.getItem())) return;

        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Player player = event.getPlayer();
        if (!player.hasPermission("customweapons.tracker")) {
            messages.message(player, "no-permission");
            return;
        }

        if (player.isSneaking()) {
            tracker.applyToPlayersTracker(player, null);
            messages.message(player, "tracker-cleared");
            return;
        }

        try {
            new TrackerGui(plugin, player, 0).open(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to open the tracker menu", e);
            player.sendMessage("\u00a7cCouldn't open the tracker menu - check the server console.");
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TrackerGui gui)) return;
        event.setCancelled(true);

        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();

        if (slot == TrackerGui.SLOT_PREV && gui.getPage() > 0) {
            new TrackerGui(plugin, player, gui.getPage() - 1).open(player);
            return;
        }
        if (slot == TrackerGui.SLOT_NEXT) {
            new TrackerGui(plugin, player, gui.getPage() + 1).open(player);
            return;
        }
        if (slot == TrackerGui.SLOT_CLEAR) {
            player.closeInventory();
            if (tracker.applyToPlayersTracker(player, null)) {
                messages.message(player, "tracker-cleared");
            }
            return;
        }

        UUID targetId = gui.getPlayerAt(slot);
        if (targetId == null) return;

        player.closeInventory();

        if (!tracker.applyToPlayersTracker(player, targetId)) {
            messages.message(player, "tracker-no-compass");
            return;
        }

        String targetName = tracker.nameOf(targetId);
        messages.message(player, "tracker-set", "{target}", targetName);

        if (plugin.getConfig().getBoolean("tracker.notify-target", false)) {
            Player target = plugin.getServer().getPlayer(targetId);
            if (target != null) {
                messages.message(target, "tracker-notify", "{player}", player.getName());
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TrackerGui) {
            event.setCancelled(true);
        }
    }
}
