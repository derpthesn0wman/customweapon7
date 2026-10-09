package com.example.weapons;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * What happens when a weapon's projectile hits a living entity.
 */
@FunctionalInterface
public interface Ability {
    void apply(WeaponPlugin plugin, Player shooter, LivingEntity target, ConfigurationSection options);
}
