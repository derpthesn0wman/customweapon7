package com.example.weapons;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry of abilities that run when a weapon is fired ("on-shoot:" in config.yml).
 *
 * To add your own: ShootAbilities.register("NAME", (plugin, shooter, projectile, options) -> { ... });
 */
public final class ShootAbilities {

    private static final Map<String, ShootAbility> REGISTRY = new TreeMap<>();

    static {
        // ---- RIDE: you ride the arrow (no gravity) until you crouch off, which deletes the arrow ----
        register("RIDE", (plugin, shooter, projectile, options) -> {
            ConfigurationSection o = options != null ? options : new MemoryConfiguration();
            double speed = o.getDouble("speed", 2.5);
            double protection = o.getDouble("fall-protection", 8);
            WeaponManager manager = plugin.getWeaponManager();
            NamespacedKey rideKey = manager.getRideKey();

            // Mark it as a ride arrow (value = seconds of fall protection after hopping off)
            projectile.getPersistentDataContainer().set(rideKey, PersistentDataType.DOUBLE, protection);
            projectile.setGravity(false);
            if (projectile instanceof AbstractArrow arrow) {
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            }

            // If you're still riding a previous arrow, get off it first
            Entity old = shooter.getVehicle();
            if (old != null && manager.isRideArrow(old)) {
                shooter.leaveVehicle();
                old.remove();
            }

            // The arrow isn't in the world yet during the shoot event, so mount on the next tick
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!projectile.isValid() || !shooter.isOnline()) return;

                if (speed > 0) {
                    projectile.setVelocity(shooter.getEyeLocation().getDirection().normalize().multiply(speed));
                }
                projectile.addPassenger(shooter);

                // Mount burst
                World w = shooter.getWorld();
                Location at = shooter.getLocation();
                Fx.spawn(w, Particle.FLASH, at.clone().add(0, 1, 0), 1);
                Fx.shockwave(w, at.clone().add(0, 0.5, 0), 3.5, 6, Particle.CLOUD);
                Fx.burst(w, at.clone().add(0, 1, 0), 50, 0.4, Particle.END_ROD);

                // Trail behind the arrow for as long as it exists
                new org.bukkit.scheduler.BukkitRunnable() {
                    int t = 0;

                    @Override
                    public void run() {
                        if (!projectile.isValid() || t++ > 20 * 120) {
                            cancel();
                            return;
                        }
                        Location p = projectile.getLocation();
                        Fx.spawn(w, Particle.END_ROD, p, 2, 0.1, 0.1, 0.1, 0.01);
                        Fx.spawn(w, Particle.CLOUD, p, 2, 0.15, 0.15, 0.15, 0.01);
                        Vector vel = projectile.getVelocity();
                        if (t % 3 == 0 && vel.lengthSquared() > 0.01) {
                            Fx.ringFacing(w, p, vel, 0.9, Fx.n(14), Particle.ELECTRIC_SPARK);
                        }
                    }
                }.runTaskTimer(plugin, 0L, 1L);

                // Safety net in case the arrow vanishes mid-air (despawns, hits something that removes it)
                ActiveAbilities.NO_FALL_UNTIL.put(shooter.getUniqueId(), System.currentTimeMillis() + 60_000L);
                shooter.playSound(shooter.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
            });
        });
    }

    private ShootAbilities() {}

    /** Gets the player off a ride arrow and deletes the arrow. */
    public static void endRide(WeaponManager manager, Player player, Entity arrow) {
        Double protection = arrow.getPersistentDataContainer().get(manager.getRideKey(), PersistentDataType.DOUBLE);
        double seconds = protection != null ? protection : 8;

        player.leaveVehicle();
        arrow.remove();

        player.setFallDistance(0f);
        if (seconds > 0) {
            ActiveAbilities.NO_FALL_UNTIL.put(player.getUniqueId(), System.currentTimeMillis() + (long) (seconds * 1000));
        } else {
            ActiveAbilities.NO_FALL_UNTIL.remove(player.getUniqueId());
        }
    }

    public static void register(String name, ShootAbility ability) {
        REGISTRY.put(name.toUpperCase(Locale.ROOT), ability);
    }

    public static ShootAbility get(String name) {
        return name == null ? null : REGISTRY.get(name.toUpperCase(Locale.ROOT));
    }

    public static Set<String> names() {
        return REGISTRY.keySet();
    }
}
