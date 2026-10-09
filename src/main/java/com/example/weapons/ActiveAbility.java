package com.example.weapons;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * An ability triggered by right-clicking (or sneak + right-clicking) a weapon, with no projectile involved.
 */
@FunctionalInterface
public interface ActiveAbility {
    /**
     * @return true if the ability was actually used (the cooldown then starts),
     *         false if it couldn't be used (no cooldown is spent).
     */
    boolean use(WeaponPlugin plugin, Player player, ConfigurationSection options);
}
