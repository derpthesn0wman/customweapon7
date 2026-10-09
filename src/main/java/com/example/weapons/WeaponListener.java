package com.example.weapons;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WeaponListener implements Listener {

    private final WeaponPlugin plugin;
    private final WeaponManager manager;

    /** Tracks the last time each player fired, so multishot's extra arrows aren't blocked by the cooldown. */
    private final Map<UUID, Long> lastShot = new HashMap<>();

    public WeaponListener(WeaponPlugin plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** When a weapon is fired: check permission/cooldown and tag the projectile. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Weapon weapon = manager.getWeapon(event.getBow());
        if (weapon == null) return;

        if (!player.hasPermission("customweapons.use")) {
            manager.message(player, "no-permission");
            event.setCancelled(true);
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastShot.get(player.getUniqueId());
        boolean sameVolley = last != null && now - last < 150;

        if (weapon.usesMinecraftCooldown()) {
            // Vanilla item cooldown (grey overlay on the item). Note: it applies to ALL items of that material.
            Material type = event.getBow().getType();
            if (!sameVolley) {
                if (player.hasCooldown(type)) {
                    double secs = player.getCooldown(type) / 20.0;
                    manager.message(player, "cooldown", "{time}", String.format("%.1f", secs));
                    event.setCancelled(true);
                    return;
                }
                int ticks = (int) Math.round(weapon.getCooldown() * 20);
                if (ticks > 0) player.setCooldown(type, ticks);
                lastShot.put(player.getUniqueId(), now);
            }
        } else {
            double remaining = manager.getRemainingCooldown(player.getUniqueId(), weapon);
            if (remaining > 0 && !sameVolley) {
                manager.message(player, "cooldown", "{time}", String.format("%.1f", remaining));
                event.setCancelled(true);
                return;
            }

            if (!sameVolley) {
                manager.startCooldown(player.getUniqueId(), weapon);
                lastShot.put(player.getUniqueId(), now);
            }
        }

        Entity projectile = event.getProjectile();
        projectile.getPersistentDataContainer()
                .set(manager.getWeaponKey(), PersistentDataType.STRING, weapon.getId());

        if (weapon.getOnShoot() != null) {
            ShootAbility shootAbility = ShootAbilities.get(weapon.getOnShoot().getAbility());
            if (shootAbility != null) {
                shootAbility.onShoot(plugin, player, projectile, weapon.getOnShoot().getOptions());
            }
        }
    }

    /** When a tagged projectile hits a living entity: run the weapon's ability. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();

        // Ride arrows: fly through entities and stay put when they hit a block (removed only when the rider crouches)
        if (manager.isRideArrow(projectile)) {
            if (event.getHitEntity() != null) event.setCancelled(true);
            return;
        }

        Weapon weapon = getTaggedWeapon(projectile);
        if (weapon == null) return;

        if (!(projectile.getShooter() instanceof Player shooter)) return;

        if (event.getHitEntity() instanceof LivingEntity target && !target.equals(shooter)) {
            Ability ability = Abilities.get(weapon.getAbilityName());
            if (ability != null) {
                ability.apply(plugin, shooter, target, weapon.getOptions());
            }
        }

        // Clean up the arrow next tick (not now, so the damage event can still resolve)
        plugin.getServer().getScheduler().runTask(plugin, projectile::remove);
    }

    /** Weapons with "damage: false" don't hurt the target. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        Weapon weapon = getTaggedWeapon(projectile);
        if (weapon != null && !weapon.dealsDamage()) {
            event.setCancelled(true);
        }
    }

    private Weapon getTaggedWeapon(Projectile projectile) {
        String id = projectile.getPersistentDataContainer()
                .get(manager.getWeaponKey(), PersistentDataType.STRING);
        return manager.getWeapon(id);
    }
}
