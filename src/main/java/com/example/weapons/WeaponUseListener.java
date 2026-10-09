package com.example.weapons;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Handles right-click / sneak-right-click abilities on weapons (swords etc). */
public class WeaponUseListener implements Listener {

    private final WeaponPlugin plugin;
    private final WeaponManager manager;

    /** Per player + ability: how many charges have been used in the current cycle. */
    private final Map<UUID, Map<String, ChargeState>> chargeStates = new HashMap<>();

    private static class ChargeState {
        int used = 0;
        long firstUse = 0;
        long lastUse = 0;
        long cooldownUntil = 0;
    }

    public WeaponUseListener(WeaponPlugin plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action clickType = event.getAction();
        if (clickType != Action.RIGHT_CLICK_AIR && clickType != Action.RIGHT_CLICK_BLOCK) return;

        Weapon weapon = manager.getWeapon(event.getItem());
        if (weapon == null) return;

        Player player = event.getPlayer();

        Weapon.WeaponAction action = (player.isSneaking() && weapon.getSneakRightClick() != null)
                ? weapon.getSneakRightClick()
                : weapon.getRightClick();
        if (action == null && weapon.getHoldRightClick() == null) return;

        // Let normal block interactions (chests, doors, buttons...) work when not sneaking
        if (clickType == Action.RIGHT_CLICK_BLOCK && !player.isSneaking()
                && event.getClickedBlock() != null && event.getClickedBlock().getType().isInteractable()) {
            return;
        }

        if (!player.hasPermission("customweapons.use")) {
            manager.message(player, "no-permission");
            return;
        }

        // Abilities locked behind kills (required-kills:)
        if (action != null && action.getRequiredKills() > 0) {
            int kills = plugin.getUpgradeManager().getKills(event.getItem());
            if (kills < action.getRequiredKills()) {
                String raw = plugin.getConfig().getString("messages.upgrade-locked", "");
                if (!raw.isBlank()) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(
                            manager.format("upgrade-locked",
                                    "{required}", String.valueOf(action.getRequiredKills()),
                                    "{kills}", String.valueOf(kills))));
                }
                return;
            }
        }

        if (action == null) {
            // Hold-to-charge weapon (e.g. the spear): don't cancel the click, just start / refresh the charge
            plugin.getChargeManager().click(player, weapon, weapon.getHoldRightClick());
            return;
        }

        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        if (action.getCharges() > 1) {
            useCharged(player, weapon, action, event.getItem());
            return;
        }

        String key = weapon.getId() + ":" + action.getName();
        double remaining = manager.getRemainingCooldown(player.getUniqueId(), key);
        if (remaining > 0) {
            String raw = plugin.getConfig().getString("messages.cooldown", "");
            if (!raw.isBlank()) {
                // Action bar so holding right-click doesn't spam chat
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(
                        manager.format("cooldown", "{time}", String.format("%.0f", Math.ceil(remaining)))));
            }
            return;
        }

        ActiveAbility ability = ActiveAbilities.get(action.getAbility());
        if (ability == null) return;

        boolean used = ability.use(plugin, player, action.getOptions());
        if (used) {
            manager.startCooldown(player.getUniqueId(), key, action.getCooldown());

            // Minecraft's own item cooldown (grey overlay). Applies to every item of that type.
            if (weapon.usesMinecraftCooldown()) {
                int ticks = (int) Math.round(action.getCooldown() * 20);
                if (ticks > 0) player.setCooldown(event.getItem().getType(), ticks);
            }
        }
    }

    /** No fall damage for a few seconds after dashing. */
    @EventHandler
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;

        Long until = ActiveAbilities.NO_FALL_UNTIL.get(player.getUniqueId());
        if (until == null) return;

        if (System.currentTimeMillis() <= until) event.setCancelled(true);
        ActiveAbilities.NO_FALL_UNTIL.remove(player.getUniqueId());
    }

    /**
     * Abilities with "charges: N" can be used N times before the cooldown starts.
     * If you don't use all the charges within "charge-window" seconds of the first use, the cooldown starts anyway.
     * "charge-gap" is the minimum time between two uses.
     */
    private void useCharged(Player player, Weapon weapon, Weapon.WeaponAction action, ItemStack item) {
        ActiveAbility ability = ActiveAbilities.get(action.getAbility());
        if (ability == null) return;

        String key = weapon.getId() + ":" + action.getName();
        ChargeState st = chargeStates
                .computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(key, k -> new ChargeState());

        long now = System.currentTimeMillis();
        long windowMs = (long) (action.getChargeWindow() * 1000);
        long cooldownMs = (long) (action.getCooldown() * 1000);
        long gapMs = (long) (action.getChargeGap() * 1000);

        // Window ran out before all charges were used: the cooldown started when the window ended
        if (st.used > 0 && st.cooldownUntil == 0 && now > st.firstUse + windowMs) {
            st.cooldownUntil = st.firstUse + windowMs + cooldownMs;
        }

        if (st.cooldownUntil > 0) {
            if (now >= st.cooldownUntil) {
                st.used = 0;
                st.cooldownUntil = 0;
            } else {
                String raw = plugin.getConfig().getString("messages.cooldown", "");
                if (!raw.isBlank()) {
                    double remaining = (st.cooldownUntil - now) / 1000.0;
                    actionBar(player, manager.format("cooldown", "{time}", String.format("%.0f", Math.ceil(remaining))));
                }
                return;
            }
        }

        if (st.used > 0 && now - st.lastUse < gapMs) return; // too soon after the previous charge

        if (!ability.use(plugin, player, action.getOptions())) return;

        if (st.used == 0) st.firstUse = now;
        st.used++;
        st.lastUse = now;

        int left = action.getCharges() - st.used;
        if (left <= 0) {
            st.cooldownUntil = now + cooldownMs;
            if (weapon.usesMinecraftCooldown()) {
                int ticks = (int) Math.round(action.getCooldown() * 20);
                if (ticks > 0) player.setCooldown(item.getType(), ticks);
            }
            actionBar(player, "\u00a7cNo charges left");
        } else {
            actionBar(player, "\u00a7eCharges left: \u00a7f" + left + "\u00a77/\u00a7f" + action.getCharges());
        }
    }

    private void actionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }

    /** Crouching while riding a ride arrow gets you off and deletes the arrow. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        Entity vehicle = player.getVehicle();
        if (vehicle == null || !manager.isRideArrow(vehicle)) return;
        ShootAbilities.endRide(manager, player, vehicle);
    }

    /** Don't leave ride arrows hanging around when the rider logs out. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Entity vehicle = event.getPlayer().getVehicle();
        if (vehicle != null && manager.isRideArrow(vehicle)) {
            ShootAbilities.endRide(manager, event.getPlayer(), vehicle);
        }
    }

    /** "True damage" hits ignore armor, protection enchants, resistance and shield blocking. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrueDamage(EntityDamageByEntityEvent event) {
        if (!ActiveAbilities.TRUE_DAMAGE.contains(event.getEntity().getUniqueId())) return;

        EntityDamageEvent.DamageModifier[] reductions = {
                EntityDamageEvent.DamageModifier.ARMOR,
                EntityDamageEvent.DamageModifier.RESISTANCE,
                EntityDamageEvent.DamageModifier.MAGIC,
                EntityDamageEvent.DamageModifier.BLOCKING
        };
        for (EntityDamageEvent.DamageModifier mod : reductions) {
            try {
                if (event.isApplicable(mod)) event.setDamage(mod, 0.0);
            } catch (Throwable ignored) {
                // modifier API not available on this server version - damage stays normal
            }
        }
    }

    /** Weapons with "on-hit:" run an ability when you melee something with them (e.g. the Scrambler). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHitAbility(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        Weapon weapon = manager.getWeapon(hand);
        if (weapon == null || weapon.getOnHit() == null) return;

        Weapon.WeaponAction action = weapon.getOnHit();
        ConfigurationSection opts = action.getOptions();
        if (opts != null && opts.getBoolean("players-only", false) && !(target instanceof Player)) return;
        if (!player.hasPermission("customweapons.use")) return;

        String key = weapon.getId() + ":" + action.getName();
        double remaining = manager.getRemainingCooldown(player.getUniqueId(), key);
        if (remaining > 0) {
            String raw = plugin.getConfig().getString("messages.cooldown", "");
            if (!raw.isBlank()) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(
                        manager.format("cooldown", "{time}", String.format("%.0f", Math.ceil(remaining)))));
            }
            return;
        }

        Ability ability = Abilities.get(action.getAbility());
        if (ability == null) return;

        Abilities.FAILED = false;
        ability.apply(plugin, player, target, opts);
        if (Abilities.FAILED) {
            Abilities.FAILED = false; // couldn't be used, so don't spend the cooldown
            return;
        }
        manager.startCooldown(player.getUniqueId(), key, action.getCooldown());

        if (weapon.usesMinecraftCooldown()) {
            int ticks = (int) Math.round(action.getCooldown() * 20);
            if (ticks > 0) player.setCooldown(hand.getType(), ticks);
        }
    }
}
