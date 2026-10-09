package com.example.weapons;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * An ability that runs the moment a bow/crossbow weapon is fired (as opposed to when the arrow hits something).
 */
@FunctionalInterface
public interface ShootAbility {
    void onShoot(WeaponPlugin plugin, Player shooter, Entity projectile, ConfigurationSection options);
}
