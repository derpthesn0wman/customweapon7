package com.example.weapons;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public class WeaponCommand implements CommandExecutor, TabCompleter {

    private final WeaponPlugin plugin;
    private final WeaponManager manager;
    private final TrackerManager tracker;

    public WeaponCommand(WeaponPlugin plugin, WeaponManager manager, TrackerManager tracker) {
        this.plugin = plugin;
        this.manager = manager;
        this.tracker = tracker;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7Usage: /" + label + " <give|tracker|list|reload> [weapon] [player]");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                sender.sendMessage("§dAvailable weapons:");
                for (Weapon w : manager.getWeapons()) {
                    sender.sendMessage("§7 - §f" + w.getId() + " §7(" + w.describe() + ") " + w.getDisplayName());
                }
                sender.sendMessage("§dOther items: §ftracker §7(player tracking compass)");
            }

            case "reload" -> {
                if (!sender.hasPermission("customweapons.admin")) {
                    manager.message(sender, "no-permission");
                    return true;
                }
                plugin.reloadConfig();
                Fx.init(plugin);
                manager.load();
                tracker.start();
                manager.message(sender, "reloaded", "{count}", String.valueOf(manager.getWeapons().size()));
            }

            case "give" -> {
                if (!sender.hasPermission("customweapons.admin")) {
                    manager.message(sender, "no-permission");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§7Usage: /" + label + " give <weapon> [player]");
                    return true;
                }

                Weapon weapon = manager.getWeapon(args[1]);
                if (weapon == null) {
                    manager.message(sender, "unknown-weapon", "{weapon}", args[1]);
                    return true;
                }

                Player target = resolveTarget(sender, args, 2);
                if (target == null) {
                    manager.message(sender, "unknown-player");
                    return true;
                }

                ItemStack created = weapon.createItem(manager.getWeaponKey());
                plugin.getUpgradeManager().initialize(created, weapon);
                giveItem(target, created);

                manager.message(sender, "given", "{weapon}", weapon.getDisplayName(), "{player}", target.getName());
                if (!target.equals(sender)) {
                    manager.message(target, "received", "{weapon}", weapon.getDisplayName());
                }
            }

            case "tracker" -> {
                if (!sender.hasPermission("customweapons.admin")) {
                    manager.message(sender, "no-permission");
                    return true;
                }
                Player target = resolveTarget(sender, args, 1);
                if (target == null) {
                    manager.message(sender, "unknown-player");
                    return true;
                }

                giveItem(target, tracker.createCompass());

                manager.message(sender, "given", "{weapon}", "Player Tracker", "{player}", target.getName());
                if (!target.equals(sender)) {
                    manager.message(target, "received", "{weapon}", "Player Tracker");
                }
            }

            case "menu" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can open the menu.");
                    return true;
                }
                if (!player.hasPermission("customweapons.tracker")) {
                    manager.message(player, "no-permission");
                    return true;
                }
                try {
                    new TrackerGui(plugin, player, 0).open(player);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to open the tracker menu", e);
                    player.sendMessage("§cCouldn't open the tracker menu - check the server console.");
                }
            }

            default -> sender.sendMessage("§7Usage: /" + label + " <give|tracker|list|reload> [weapon] [player]");
        }
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int index) {
        if (args.length > index) return Bukkit.getPlayerExact(args[index]);
        return sender instanceof Player p ? p : null;
    }

    private void giveItem(Player target, ItemStack item) {
        target.getInventory().addItem(item).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("give", "tracker", "menu", "list", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Weapon w : manager.getWeapons()) {
                if (w.getId().toLowerCase().startsWith(args[1].toLowerCase())) out.add(w.getId());
            }
        } else if ((args.length == 3 && args[0].equalsIgnoreCase("give"))
                || (args.length == 2 && args[0].equalsIgnoreCase("tracker"))) {
            String partial = args[args.length - 1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) out.add(p.getName());
            }
        }
        return out;
    }
}
