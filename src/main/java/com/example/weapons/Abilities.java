package com.example.weapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry of all abilities a weapon can use.
 *
 * To add a brand new ability in code, call Abilities.register("NAME", (plugin, shooter, target, options) -> { ... });
 * (the static block below is a good place), then use "ability: NAME" in config.yml.
 */
public final class Abilities {

    private static final Map<String, Ability> REGISTRY = new TreeMap<>();

    /** An on-hit ability sets this to true when it couldn't be used, so the weapon's cooldown isn't spent. */
    public static boolean FAILED = false;

    static {
        // ---- SWAP: shooter and target trade places ----
        register("SWAP", (plugin, shooter, target, options) -> {
            boolean keepRotation = options == null || options.getBoolean("keep-rotation", true);

            Location shooterLoc = shooter.getLocation();
            Location targetLoc = target.getLocation();

            Location shooterDest = targetLoc.clone();
            Location targetDest = shooterLoc.clone();

            if (keepRotation) {
                shooterDest.setYaw(shooterLoc.getYaw());
                shooterDest.setPitch(shooterLoc.getPitch());
                targetDest.setYaw(targetLoc.getYaw());
                targetDest.setPitch(targetLoc.getPitch());
            }

            // Effects at both old positions, plus a beam connecting the two players
            swapEffect(shooterLoc);
            swapEffect(targetLoc);
            if (shooterLoc.getWorld().equals(targetLoc.getWorld())) {
                Fx.line(shooterLoc.getWorld(), shooterLoc.clone().add(0, 1, 0), targetLoc.clone().add(0, 1, 0), 0.4, Particle.END_ROD);
                Fx.line(shooterLoc.getWorld(), shooterLoc.clone().add(0, 1, 0), targetLoc.clone().add(0, 1, 0), 0.6, Particle.PORTAL);
            }

            shooter.teleport(shooterDest);
            target.teleport(targetDest);

            shooterDest.getWorld().playSound(shooterDest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            targetDest.getWorld().playSound(targetDest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            swapEffect(shooterDest);
            swapEffect(targetDest);

            plugin.getWeaponManager().message(shooter, "swapped", "{target}", target.getName());
            if (target instanceof Player targetPlayer) {
                plugin.getWeaponManager().message(targetPlayer, "was-swapped", "{shooter}", shooter.getName());
            }
        });

        // ---- SCRAMBLE: shuffle the target player's inventory ----
        register("SCRAMBLE", (plugin, shooter, target, options) -> {
            if (!(target instanceof Player victim)) return;
            boolean includeArmor = options != null && options.getBoolean("include-armor", false);
            boolean includeOffhand = options == null || options.getBoolean("include-offhand", true);

            PlayerInventory inv = victim.getInventory();

            // Slots: 0-8 hotbar, 9-35 main inventory, 36-39 armor, 40 off-hand
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i <= 35; i++) slots.add(i);
            if (includeArmor) {
                for (int i = 36; i <= 39; i++) slots.add(i);
            }
            if (includeOffhand) slots.add(40);

            List<ItemStack> items = new ArrayList<>();
            for (int slot : slots) {
                ItemStack item = inv.getItem(slot);
                items.add(item == null ? null : item.clone());
            }
            Collections.shuffle(items); // empty slots are shuffled too, so items end up in random places

            for (int i = 0; i < slots.size(); i++) {
                inv.setItem(slots.get(i), items.get(i));
            }
            victim.updateInventory();

            scrambleEffect(victim);

            plugin.getWeaponManager().message(shooter, "scrambled-attacker", "{target}", victim.getName());
            plugin.getWeaponManager().message(victim, "scrambled-victim", "{player}", shooter.getName());
        });

        // ---- SIFT_SEND: send whoever you hit to the sift world ----
        register("SIFT_SEND", (plugin, shooter, target, options) -> {
            if (!plugin.getSiftManager().send(shooter, target)) FAILED = true;
        });

        // ---- LAUNCH: throw the target into the air ----
        register("LAUNCH", (plugin, shooter, target, options) -> {
            double power = options == null ? 1.2 : options.getDouble("power", 1.2);
            target.setVelocity(target.getVelocity().setY(power));

            World lw = target.getWorld();
            Location feet = target.getLocation();
            Fx.spawn(lw, Particle.FLASH, feet.clone().add(0, 0.5, 0), 1);
            Fx.shockwave(lw, feet.clone().add(0, 0.15, 0), 3.5, 6, Particle.CLOUD);
            Fx.burst(lw, feet.clone().add(0, 0.5, 0), 40, 0.35, Particle.CLOUD);
            Fx.burst(lw, feet.clone().add(0, 0.5, 0), 25, 0.3, Particle.END_ROD);
            Fx.helix(lw, feet, 0.7, 3.0, 12, Particle.END_ROD, 2);
            lw.playSound(feet, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
        });

        // ---- LIGHTNING: strike the target ----
        register("LIGHTNING", (plugin, shooter, target, options) -> {
            boolean harmless = options != null && options.getBoolean("harmless", false);
            if (harmless) {
                target.getWorld().strikeLightningEffect(target.getLocation());
            } else {
                target.getWorld().strikeLightning(target.getLocation());
            }
            Location lm = target.getLocation().add(0, 1, 0);
            Fx.spawn(target.getWorld(), Particle.FLASH, lm, 2, 0.3, 0.3, 0.3, 0);
            Fx.burst(target.getWorld(), lm, 80, 0.5, Particle.ELECTRIC_SPARK);
            Fx.shockwave(target.getWorld(), target.getLocation().add(0, 0.2, 0), 4, 7, Particle.ELECTRIC_SPARK);
            Fx.pillar(target.getWorld(), target.getLocation(), 6, 0.6, Particle.END_ROD);
        });

        // ---- EFFECT: apply a potion effect to target or shooter ----
        register("EFFECT", (plugin, shooter, target, options) -> {
            if (options == null) return;
            String name = options.getString("effect", "slowness").toLowerCase(Locale.ROOT);
            PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(name));
            if (type == null) {
                plugin.getLogger().warning("Unknown potion effect in config: " + name);
                return;
            }
            int ticks = (int) (options.getDouble("duration", 5) * 20);
            int amplifier = options.getInt("amplifier", 0);
            boolean onShooter = "SHOOTER".equalsIgnoreCase(options.getString("apply-to", "TARGET"));

            LivingEntity recipient = onShooter ? shooter : target;
            recipient.addPotionEffect(new PotionEffect(type, ticks, amplifier));

            World ew = recipient.getWorld();
            Location em = recipient.getLocation().add(0, 1, 0);
            boolean icy = name.equals("slowness");
            Fx.spawn(ew, Particle.FLASH, em, 1);
            Fx.burst(ew, em, 50, 0.3, icy ? Particle.SNOWFLAKE : Particle.END_ROD);
            Fx.ringDust(ew, recipient.getLocation().add(0, 0.2, 0), 1.2, Fx.n(30),
                    icy ? Color.fromRGB(120, 200, 255) : Color.fromRGB(255, 120, 255), Color.WHITE, 1.8f);
            Fx.shockwave(ew, recipient.getLocation().add(0, 0.2, 0), 3, 6, icy ? Particle.SNOWFLAKE : Particle.END_ROD);
            Fx.helix(ew, recipient.getLocation(), 0.8, 2.2, 14, icy ? Particle.SNOWFLAKE : Particle.END_ROD, 2);
        });
    }

    private Abilities() {}

    /** Teleport effect: flash, portal burst, ground shockwave and a rising spiral. */
    private static void swapEffect(Location loc) {
        World w = loc.getWorld();
        Location mid = loc.clone().add(0, 1, 0);
        Fx.spawn(w, Particle.FLASH, mid, 1);
        Fx.burst(w, mid, 60, 0.4, Particle.PORTAL);
        Fx.burst(w, mid, 30, 0.3, Particle.END_ROD);
        Fx.shockwave(w, loc.clone().add(0, 0.15, 0), 3, 6, Particle.REVERSE_PORTAL);
        Fx.helix(w, loc, 0.8, 2.2, 10, Particle.REVERSE_PORTAL, 3);
    }

    /** Scramble effect: a swirling tornado, colour-shifting ring, shockwave and flash around the victim. */
    private static void scrambleEffect(Player victim) {
        World w = victim.getWorld();
        Location loc = victim.getLocation();
        Location mid = loc.clone().add(0, 1, 0);
        Fx.spawn(w, Particle.FLASH, mid, 1);
        Fx.burst(w, mid, 70, 0.45, Particle.PORTAL);
        Fx.burst(w, mid, 40, 0.3, Particle.END_ROD);
        Fx.ringDust(w, loc.clone().add(0, 0.2, 0), 1.4, Fx.n(36),
                Color.fromRGB(255, 60, 220), Color.fromRGB(80, 220, 255), 2f);
        Fx.shockwave(w, loc.clone().add(0, 0.2, 0), 4, 7, Particle.DRAGON_BREATH);
        Fx.helix(w, loc, 1.0, 2.4, 20, Particle.END_ROD, 3);
        Fx.helix(w, loc, 0.7, 2.4, 20, Particle.PORTAL, 2);
        w.playSound(mid, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 1f);
        w.playSound(mid, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.6f);
    }

    public static void register(String name, Ability ability) {
        REGISTRY.put(name.toUpperCase(Locale.ROOT), ability);
    }

    public static Ability get(String name) {
        return name == null ? null : REGISTRY.get(name.toUpperCase(Locale.ROOT));
    }

    public static Set<String> names() {
        return REGISTRY.keySet();
    }
}
