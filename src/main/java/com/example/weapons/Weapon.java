package com.example.weapons;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One weapon definition loaded from config.yml.
 */
public class Weapon {

    private final String id;
    private final Material material;
    private final String name;
    private final List<String> lore;
    private final int customModelData;
    private final boolean glow;
    private final boolean unbreakable;
    private final ConfigurationSection enchantments;
    private final String abilityName;
    private final double cooldown;
    private final boolean minecraftCooldown;
    private final boolean damage;
    private final ConfigurationSection options;
    private final WeaponAction rightClick;
    private final WeaponAction sneakRightClick;
    private final WeaponAction onShoot;
    private final WeaponAction holdRightClick;
    private final boolean slowFalling;
    private final ConfigurationSection killsConfig;
    private final ConfigurationSection bonusHealth;
    private final ConfigurationSection shieldBypass;
    private final ConfigurationSection combo;
    private final WeaponAction onHit;
    private final ConfigurationSection chainConfig;
    private final double attackSpeedBonus;

    public Weapon(String id, ConfigurationSection s) {
        this.id = id;

        Material mat = Material.matchMaterial(s.getString("material", "CROSSBOW"));
        if (mat == null) mat = Material.matchMaterial(s.getString("fallback-material", ""));
        this.material = mat != null ? mat : Material.CROSSBOW;

        this.name = s.getString("name", id);
        this.lore = s.getStringList("lore");
        this.customModelData = s.getInt("custom-model-data", 0);
        this.glow = s.getBoolean("glow", false);
        this.unbreakable = s.getBoolean("unbreakable", false);
        this.enchantments = s.getConfigurationSection("enchantments");
        this.abilityName = s.getString("ability", "NONE").toUpperCase(Locale.ROOT);
        this.cooldown = s.getDouble("cooldown", 0);
        this.minecraftCooldown = s.getBoolean("minecraft-cooldown", false);
        this.damage = s.getBoolean("damage", true);
        this.options = s.getConfigurationSection("options");
        this.rightClick = loadAction("right-click", s);
        this.sneakRightClick = loadAction("sneak-right-click", s);
        this.onShoot = loadAction("on-shoot", s);
        this.holdRightClick = loadAction("hold-right-click", s);
        this.slowFalling = s.getBoolean("slow-falling", false);
        this.killsConfig = s.getConfigurationSection("kills");
        this.bonusHealth = s.getConfigurationSection("bonus-health");
        this.shieldBypass = s.getConfigurationSection("shield-bypass");
        this.combo = s.getConfigurationSection("combo");
        this.onHit = loadAction("on-hit", s);
        this.chainConfig = s.getConfigurationSection("chain");
        this.attackSpeedBonus = s.getDouble("attack-speed-bonus", 0);
    }

    public ItemStack createItem(NamespacedKey weaponKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(color(name));

        List<String> coloredLore = new ArrayList<>();
        for (String line : lore) coloredLore.add(color(line));
        meta.setLore(coloredLore);

        if (customModelData > 0) meta.setCustomModelData(customModelData);
        meta.setUnbreakable(unbreakable);
        if (unbreakable) meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);

        if (enchantments != null) {
            for (String key : enchantments.getKeys(false)) {
                Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
                if (ench != null) meta.addEnchant(ench, enchantments.getInt(key), true);
            }
        }

        if (glow && (enchantments == null || enchantments.getKeys(false).isEmpty())) {
            // Glint without a visible enchant
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String getId() { return id; }
    public String getDisplayName() { return color(name); }
    public String getAbilityName() { return abilityName; }
    public double getCooldown() { return cooldown; }
    public boolean usesMinecraftCooldown() { return minecraftCooldown; }
    public Material getMaterial() { return material; }
    public boolean dealsDamage() { return damage; }
    public ConfigurationSection getOptions() { return options; }
    public WeaponAction getRightClick() { return rightClick; }
    public WeaponAction getSneakRightClick() { return sneakRightClick; }
    public WeaponAction getOnShoot() { return onShoot; }
    public WeaponAction getHoldRightClick() { return holdRightClick; }
    public boolean hasSlowFalling() { return slowFalling; }
    public ConfigurationSection getKillsConfig() { return killsConfig; }
    public ConfigurationSection getBonusHealth() { return bonusHealth; }
    public ConfigurationSection getShieldBypass() { return shieldBypass; }
    public ConfigurationSection getCombo() { return combo; }
    public WeaponAction getOnHit() { return onHit; }
    public ConfigurationSection getChainConfig() { return chainConfig; }
    public double getAttackSpeedBonus() { return attackSpeedBonus; }

    /** The configured lore, colour-coded. Returns a fresh list each time. */
    public List<String> getLoreLines() {
        List<String> out = new ArrayList<>();
        for (String line : lore) out.add(color(line));
        return out;
    }

    public List<WeaponAction> getActions() {
        List<WeaponAction> list = new ArrayList<>();
        if (rightClick != null) list.add(rightClick);
        if (sneakRightClick != null) list.add(sneakRightClick);
        if (holdRightClick != null) list.add(holdRightClick);
        return list;
    }

    /** Short text for /weapons list. */
    public String describe() {
        if (!abilityName.equals("NONE")) return abilityName;
        List<String> parts = new ArrayList<>();
        for (WeaponAction a : getActions()) parts.add(a.getAbility());
        if (onShoot != null) parts.add(onShoot.getAbility());
        if (killsConfig != null) parts.add("UPGRADES");
        if (combo != null) parts.add("COMBO");
        if (onHit != null) parts.add(onHit.getAbility());
        if (chainConfig != null) parts.add("CHAIN");
        return parts.isEmpty() ? "NONE" : String.join("+", parts);
    }

    private static WeaponAction loadAction(String key, ConfigurationSection parent) {
        ConfigurationSection s = parent.getConfigurationSection(key);
        return s == null ? null : new WeaponAction(key, s);
    }

    /** A click-triggered ability (right-click or sneak + right-click) with its own cooldown and options. */
    public static class WeaponAction {
        private final String name;
        private final String ability;
        private final double cooldown;
        private final double chargeTime;
        private final int stages;
        private final int requiredKills;
        private final int charges;
        private final double chargeWindow;
        private final double chargeGap;
        private final ConfigurationSection options;

        WeaponAction(String name, ConfigurationSection s) {
            this.name = name;
            this.ability = s.getString("ability", "").toUpperCase(Locale.ROOT);
            this.cooldown = s.getDouble("cooldown", 0);
            this.chargeTime = s.getDouble("charge-time", 5);
            this.stages = s.getInt("stages", 5);
            this.requiredKills = s.getInt("required-kills", 0);
            this.charges = Math.max(1, s.getInt("charges", 1));
            this.chargeWindow = s.getDouble("charge-window", 5);
            this.chargeGap = s.getDouble("charge-gap", 0.4);
            this.options = s.getConfigurationSection("options");
        }

        public String getName() { return name; }
        public String getAbility() { return ability; }
        public double getChargeTime() { return chargeTime; }
        public int getStages() { return stages; }
        public int getRequiredKills() { return requiredKills; }
        public int getCharges() { return charges; }
        public double getChargeWindow() { return chargeWindow; }
        public double getChargeGap() { return chargeGap; }
        public double getCooldown() { return cooldown; }
        public ConfigurationSection getOptions() { return options; }
    }
}
