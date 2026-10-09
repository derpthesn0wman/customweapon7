package com.example.weapons;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

public class WeaponManager {

    private final WeaponPlugin plugin;
    private final NamespacedKey weaponKey;
    private final NamespacedKey rideKey;
    private final Map<String, Weapon> weapons = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public WeaponManager(WeaponPlugin plugin) {
        this.plugin = plugin;
        this.weaponKey = new NamespacedKey(plugin, "weapon_id");
        this.rideKey = new NamespacedKey(plugin, "ride_arrow");
    }

    public void load() {
        weapons.clear();
        cooldowns.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("weapons");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            ConfigurationSection ws = section.getConfigurationSection(id);
            if (ws == null) continue;

            Weapon weapon = new Weapon(id, ws);
            String configuredMaterial = ws.getString("material", "CROSSBOW");
            if (Material.matchMaterial(configuredMaterial) == null) {
                plugin.getLogger().warning("Weapon '" + id + "': material '" + configuredMaterial
                        + "' doesn't exist on this server version. Using " + weapon.getMaterial() + " instead.");
            }
            if (!weapon.getAbilityName().equals("NONE") && Abilities.get(weapon.getAbilityName()) == null) {
                plugin.getLogger().warning("Weapon '" + id + "' uses unknown ability '"
                        + weapon.getAbilityName() + "'. Available: " + Abilities.names());
                continue;
            }
            boolean badAction = false;
            for (Weapon.WeaponAction action : weapon.getActions()) {
                if (ActiveAbilities.get(action.getAbility()) == null) {
                    plugin.getLogger().warning("Weapon '" + id + "' uses unknown " + action.getName()
                            + " ability '" + action.getAbility() + "'. Available: " + ActiveAbilities.names());
                    badAction = true;
                }
            }
            if (weapon.getOnShoot() != null && ShootAbilities.get(weapon.getOnShoot().getAbility()) == null) {
                plugin.getLogger().warning("Weapon '" + id + "' uses unknown on-shoot ability '"
                        + weapon.getOnShoot().getAbility() + "'. Available: " + ShootAbilities.names());
                badAction = true;
            }
            if (weapon.getOnHit() != null && Abilities.get(weapon.getOnHit().getAbility()) == null) {
                plugin.getLogger().warning("Weapon '" + id + "' uses unknown on-hit ability '"
                        + weapon.getOnHit().getAbility() + "'. Available: " + Abilities.names());
                badAction = true;
            }
            if (badAction) continue;
            weapons.put(id.toLowerCase(), weapon);
        }
    }

    public NamespacedKey getWeaponKey() { return weaponKey; }

    public NamespacedKey getRideKey() { return rideKey; }

    /** Is this entity an arrow someone can ride (fired by a weapon with the RIDE ability)? */
    public boolean isRideArrow(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(rideKey, PersistentDataType.DOUBLE);
    }

    public Collection<Weapon> getWeapons() { return weapons.values(); }

    public Weapon getWeapon(String id) {
        return id == null ? null : weapons.get(id.toLowerCase());
    }

    /** Returns the weapon definition for an item, or null if it isn't a custom weapon. */
    public Weapon getWeapon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id = pdc.get(weaponKey, PersistentDataType.STRING);
        return getWeapon(id);
    }

    // ---------- Cooldowns ----------

    /** Seconds remaining, or 0 if ready. */
    public double getRemainingCooldown(UUID player, Weapon weapon) {
        Map<String, Long> map = cooldowns.get(player);
        if (map == null) return 0;
        Long expires = map.get(weapon.getId());
        if (expires == null) return 0;
        return Math.max(0, (expires - System.currentTimeMillis()) / 1000.0);
    }

    public void startCooldown(UUID player, Weapon weapon) {
        if (weapon.getCooldown() <= 0) return;
        cooldowns.computeIfAbsent(player, k -> new HashMap<>())
                .put(weapon.getId(), System.currentTimeMillis() + (long) (weapon.getCooldown() * 1000));
    }

    /** Cooldown by custom key (used for click abilities, e.g. "dash_sword:sneak-right-click"). */
    public double getRemainingCooldown(UUID player, String key) {
        Map<String, Long> map = cooldowns.get(player);
        if (map == null) return 0;
        Long expires = map.get(key);
        if (expires == null) return 0;
        return Math.max(0, (expires - System.currentTimeMillis()) / 1000.0);
    }

    public void startCooldown(UUID player, String key, double seconds) {
        if (seconds <= 0) return;
        cooldowns.computeIfAbsent(player, k -> new HashMap<>())
                .put(key, System.currentTimeMillis() + (long) (seconds * 1000));
    }

    // ---------- Messages ----------

    public String format(String key, String... replacements) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String msg = plugin.getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }

    public void message(CommandSender to, String key, String... replacements) {
        String raw = plugin.getConfig().getString("messages." + key, key);
        if (raw == null || raw.isBlank()) return; // blank message in config = disabled
        to.sendMessage(format(key, replacements));
    }
}
