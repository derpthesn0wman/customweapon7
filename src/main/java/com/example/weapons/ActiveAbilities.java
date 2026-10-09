package com.example.weapons;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Registry of abilities that are triggered by clicking with a weapon.
 *
 * To add your own: ActiveAbilities.register("NAME", (plugin, player, options) -> { ...; return true; });
 * then use "ability: NAME" under right-click: or sneak-right-click: in config.yml.
 */
public final class ActiveAbilities {

    private static final Map<String, ActiveAbility> REGISTRY = new TreeMap<>();

    /** Players who are immune to fall damage until the given time (ms) after dashing. */
    public static final Map<UUID, Long> NO_FALL_UNTIL = new HashMap<>();

    /** Entities currently being hit by a "true damage" attack (armor etc. is ignored for these hits). */
    public static final Set<UUID> TRUE_DAMAGE = new HashSet<>();

    static {
        // ---- DASH: fling yourself in the direction you're looking (any direction, incl. up/down) ----
        register("DASH", (plugin, player, options) -> {
            ConfigurationSection o = options != null ? options : new MemoryConfiguration();
            double power = o.getDouble("power", 1.8);
            double minLift = o.getDouble("min-lift", 0.35);
            double fallProtection = o.getDouble("fall-protection", 6);

            Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(power);
            // Leave the ground a little, otherwise ground friction kills a flat dash instantly
            if (player.isOnGround() && velocity.getY() < minLift) {
                velocity.setY(minLift);
            }

            player.setVelocity(velocity);
            player.setFallDistance(0f);
            if (fallProtection > 0) {
                NO_FALL_UNTIL.put(player.getUniqueId(), System.currentTimeMillis() + (long) (fallProtection * 1000));
            }

            World world = player.getWorld();
            Location start = player.getLocation();
            Location chest = start.clone().add(0, 1, 0);
            Vector flight = velocity.clone().normalize();

            // Launch burst: ground shockwave, rings you fly through, a blast of air behind you
            Fx.spawn(world, Particle.FLASH, chest, 1);
            Fx.shockwave(world, start.clone().add(0, 0.15, 0), 4.5, 6, Particle.CLOUD);
            Fx.ringFacing(world, chest.clone().add(flight.clone().multiply(0.8)), flight, 1.3, Fx.n(32), Particle.END_ROD);
            Fx.ringFacing(world, chest.clone().add(flight.clone().multiply(0.2)), flight, 0.8, Fx.n(22), Particle.ELECTRIC_SPARK);
            Fx.cone(world, chest, flight.clone().multiply(-1), 0.5, 60, 0.5, Particle.CLOUD);
            Fx.cone(world, chest, flight.clone().multiply(-1), 0.3, 30, 0.7, Particle.END_ROD);
            world.playSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
            world.playSound(start, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1.1f);

            // Flight trail: sparks and clouds, with a ring every few ticks
            new org.bukkit.scheduler.BukkitRunnable() {
                int ticks = 0;

                @Override
                public void run() {
                    if (!player.isOnline() || ticks++ > 24) {
                        cancel();
                        return;
                    }
                    Location at = player.getLocation().add(0, 1, 0);
                    Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 3, 0.3, 0.3, 0.3, 0.05);
                    Fx.spawn(world, Particle.CLOUD, at, 3, 0.25, 0.25, 0.25, 0.01);
                    Fx.spawn(world, Particle.END_ROD, at, 2, 0.2, 0.2, 0.2, 0.02);
                    Vector v = player.getVelocity();
                    if (ticks % 3 == 0 && v.lengthSquared() > 0.05) {
                        Fx.ringFacing(world, at, v, 0.9, Fx.n(18), Particle.END_ROD);
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);

            return true;
        });

        // ---- SONIC_BOOM: a warden-style beam that damages everything along a line ----
        register("SONIC_BOOM", (plugin, player, options) -> {
            ConfigurationSection o = options != null ? options : new MemoryConfiguration();
            double damage = o.getDouble("damage", 20);
            double range = o.getDouble("range", 20);
            double knockback = o.getDouble("knockback", 1.5);
            double hitRadius = o.getDouble("hit-radius", 0.8);
            boolean stopAtBlocks = o.getBoolean("stop-at-blocks", false);
            boolean hitPlayers = o.getBoolean("hit-players", true);
            boolean trueDamage = o.getBoolean("true-damage", true);

            Location eye = player.getEyeLocation();
            World world = eye.getWorld();
            Vector dir = eye.getDirection().normalize();
            Vector origin = eye.toVector();

            double maxDist = range;
            if (stopAtBlocks) {
                RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
                if (blockHit != null) maxDist = blockHit.getHitPosition().distance(origin);
            }

            sonicBoomVisuals(world, eye, dir, maxDist);

            // Damage everything the beam passes through
            Location mid = eye.clone().add(dir.clone().multiply(maxDist / 2.0));
            double half = maxDist / 2.0 + 2.0;
            for (Entity e : world.getNearbyEntities(mid, half, half, half)) {
                if (!(e instanceof LivingEntity living) || e.equals(player) || e instanceof ArmorStand) continue;
                if (e instanceof Player target) {
                    if (!hitPlayers || target.getGameMode() == GameMode.SPECTATOR) continue;
                }

                BoundingBox box = e.getBoundingBox().expand(hitRadius);
                if (box.rayTrace(origin, dir, maxDist) == null) continue;

                if (trueDamage) {
                    TRUE_DAMAGE.add(living.getUniqueId());
                    try {
                        living.damage(damage, player);
                    } finally {
                        TRUE_DAMAGE.remove(living.getUniqueId());
                    }
                } else {
                    living.damage(damage, player);
                }

                if (knockback > 0) {
                    Vector push = dir.clone().multiply(knockback);
                    push.setY(Math.max(push.getY(), 0.3));
                    living.setVelocity(living.getVelocity().add(push));
                }

                // Impact on each victim
                Location hitLoc = living.getLocation().add(0, living.getHeight() / 2.0, 0);
                Fx.spawn(world, Particle.FLASH, hitLoc, 1);
                Fx.spawn(world, Particle.SONIC_BOOM, hitLoc, 1);
                Fx.burst(world, hitLoc, 40, 0.35, Particle.SCULK_SOUL);
                Fx.burst(world, hitLoc, 25, 0.3, Particle.END_ROD);
                world.playSound(hitLoc, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.2f, 1f);
            }
            return true;
        });

        // ---- SIFT_ENTER: send yourself to the sift world (or back, if you're already there) ----
        register("SIFT_ENTER", (plugin, player, options) -> plugin.getSiftManager().enter(player));

        // ---- BLIND_AREA: blind everyone within a radius ----
        register("BLIND_AREA", (plugin, player, options) -> {
            ConfigurationSection o = options != null ? options : new MemoryConfiguration();
            double radius = o.getDouble("radius", 50);
            int ticks = (int) Math.round(o.getDouble("duration", 30) * 20);
            int amplifier = o.getInt("amplifier", 255);
            boolean excludeSelf = o.getBoolean("exclude-self", true);
            boolean includeMobs = o.getBoolean("include-mobs", false);

            Location origin = player.getLocation();
            PotionEffect effect = new PotionEffect(PotionEffectType.BLINDNESS, ticks, amplifier);

            int count = 0;
            for (Entity e : player.getNearbyEntities(radius, radius, radius)) {
                if (!(e instanceof LivingEntity living)) continue;
                if (!(e instanceof Player) && !includeMobs) continue;
                if (e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) continue;
                if (!e.getWorld().equals(origin.getWorld())) continue;
                if (e.getLocation().distanceSquared(origin) > radius * radius) continue;

                living.addPotionEffect(effect);
                count++;

                if (e instanceof Player victim) {
                    victim.playSound(victim.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1f);
                    Location head = victim.getEyeLocation();
                    Fx.burst(victim.getWorld(), head, 40, 0.2, Particle.SQUID_INK);
                    Fx.spawn(victim.getWorld(), Particle.SCULK_SOUL, head, Fx.n(20), 0.4, 0.4, 0.4, 0.02);
                    plugin.getWeaponManager().message(victim, "blinded", "{player}", player.getName());
                }
            }

            if (count == 0) {
                plugin.getWeaponManager().message(player, "blind-no-targets");
                return false;
            }

            if (!excludeSelf) player.addPotionEffect(effect);

            World bw = player.getWorld();
            Location bc = origin.clone().add(0, 1, 0);
            Fx.spawn(bw, Particle.FLASH, bc, 2, 0.3, 0.3, 0.3, 0);
            Fx.burst(bw, bc, 120, 0.5, Particle.SQUID_INK);
            Fx.burst(bw, bc, 80, 0.35, Particle.SCULK_SOUL);
            Fx.shockwave(bw, origin.clone().add(0, 0.3, 0), Math.min(radius, 40), 14, Particle.SQUID_INK);
            Fx.shockwave(bw, origin.clone().add(0, 0.3, 0), Math.min(radius, 40), 10, Particle.SCULK_SOUL);
            Fx.ringDust(bw, origin.clone().add(0, 0.3, 0), 3.0, Fx.n(48), Color.fromRGB(40, 0, 70), Color.BLACK, 2.2f);
            bw.playSound(origin, Sound.ENTITY_WARDEN_ROAR, 1.5f, 1f);
            plugin.getWeaponManager().message(player, "blind-used", "{count}", String.valueOf(count));
            return true;
        });
    }

    private ActiveAbilities() {}

    /** The sonic boom beam: muzzle flash, double helix around the line, rings along it, impact flash at the end. */
    private static void sonicBoomVisuals(World world, Location eye, Vector dir, double maxDist) {
        Vector n = dir.clone().normalize();
        Location muzzle = eye.clone().add(n.clone().multiply(1.2));

        Fx.spawn(world, Particle.FLASH, muzzle, 2, 0.1, 0.1, 0.1, 0);
        Fx.burst(world, muzzle, 40, 0.4, Particle.END_ROD);
        Fx.ringFacing(world, muzzle, n, 0.8, Fx.n(28), Particle.SOUL_FIRE_FLAME);
        Fx.ringFacing(world, muzzle, n, 1.4, Fx.n(40), Particle.END_ROD);

        Vector up = Math.abs(n.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = n.clone().crossProduct(up).normalize();
        Vector v = n.clone().crossProduct(u).normalize();

        for (double d = 1.0; d <= maxDist; d += 0.5) {
            Location centre = eye.clone().add(n.clone().multiply(d));
            if (d % 1.0 == 0) Fx.spawn(world, Particle.SONIC_BOOM, centre, 1);

            for (int s = 0; s < 2; s++) {
                double a = d * 1.4 + s * Math.PI;
                Location p = centre.clone()
                        .add(u.clone().multiply(Math.cos(a) * 0.55))
                        .add(v.clone().multiply(Math.sin(a) * 0.55));
                Fx.spawn(world, s == 0 ? Particle.SOUL_FIRE_FLAME : Particle.END_ROD, p, 1);
            }
            if (d % 4.0 == 0) Fx.ringFacing(world, centre, n, 1.0, Fx.n(16), Particle.SCULK_SOUL);
        }

        Location end = eye.clone().add(n.clone().multiply(maxDist));
        Fx.spawn(world, Particle.FLASH, end, 1);
        Fx.burst(world, end, 40, 0.3, Particle.SCULK_SOUL);

        world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 1f);
        world.playSound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
    }

    public static void register(String name, ActiveAbility ability) {
        REGISTRY.put(name.toUpperCase(Locale.ROOT), ability);
    }

    public static ActiveAbility get(String name) {
        return name == null ? null : REGISTRY.get(name.toUpperCase(Locale.ROOT));
    }

    public static Set<String> names() {
        return REGISTRY.keySet();
    }
}
