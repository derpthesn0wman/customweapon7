package com.example.weapons;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The chain axe:
 *  - hit the same target N times and they get chained in place for a while
 *  - hit two different people in a row and they get chained together: damage to one is also dealt to the other
 *
 * Config (on the weapon):
 *   chain:
 *     hits, hit-timeout, duration
 *     damage-link: window, duration, cooldown, share, include-mobs
 */
public class ChainManager implements Listener {

    private static final Color CHAIN_DARK = Color.fromRGB(120, 120, 130);
    private static final Color CHAIN_LIGHT = Color.fromRGB(225, 225, 235);

    private final WeaponPlugin plugin;
    private final WeaponManager manager;

    private final Map<UUID, Map<UUID, HitCount>> hitCounts = new HashMap<>(); // attacker -> target -> hits
    private final Map<UUID, Long> chained = new HashMap<>();                   // entity -> chained until (ms)
    private final Map<UUID, Link> links = new HashMap<>();                     // entity -> link (both ends)
    private final Map<UUID, LastHit> lastHits = new HashMap<>();               // attacker -> previous target
    private final Map<UUID, Long> linkCooldowns = new HashMap<>();             // attacker -> next allowed link (ms)
    private boolean mirroring = false;
    private BukkitTask task;

    private static class HitCount {
        int count = 0;
        long last = 0;
    }

    private static class LastHit {
        final UUID target;
        final long time;

        LastHit(UUID target, long time) {
            this.target = target;
            this.time = time;
        }
    }

    private static class Link {
        final UUID a;
        final UUID b;
        final UUID owner;
        final long until;
        final double share;

        Link(UUID a, UUID b, UUID owner, long until, double share) {
            this.a = a;
            this.b = b;
            this.owner = owner;
            this.until = until;
            this.share = share;
        }

        UUID other(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    public ChainManager(WeaponPlugin plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(chained.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity living) removeRoot(living);
        }
        chained.clear();
        links.clear();
        hitCounts.clear();
        lastHits.clear();
        linkCooldowns.clear();
    }

    // ---------- Axe hits ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (mirroring) return; // mirrored link damage isn't a real hit
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (target.equals(attacker)) return;

        Weapon weapon = manager.getWeapon(attacker.getInventory().getItemInMainHand());
        if (weapon == null || weapon.getChainConfig() == null) return;

        ConfigurationSection cfg = weapon.getChainConfig();
        long now = System.currentTimeMillis();

        countHit(attacker, target, cfg, now);
        tryLink(attacker, target, cfg.getConfigurationSection("damage-link"), now);
    }

    /** N hits on the same target chains them in place. */
    private void countHit(Player attacker, LivingEntity target, ConfigurationSection cfg, long now) {
        if (chained.containsKey(target.getUniqueId())) return;

        int needed = Math.max(1, cfg.getInt("hits", 10));
        long timeoutMs = (long) (cfg.getDouble("hit-timeout", 8) * 1000);

        HitCount hc = hitCounts
                .computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(target.getUniqueId(), k -> new HitCount());

        if (hc.count > 0 && now - hc.last > timeoutMs) hc.count = 0;
        hc.count++;
        hc.last = now;

        if (hc.count >= needed) {
            hc.count = 0;
            chain(attacker, target, cfg.getDouble("duration", 4), now);
        } else {
            actionBar(attacker, "&7Chain: &f" + hc.count + "&7/&f" + needed);
        }
    }

    private void chain(Player attacker, LivingEntity target, double seconds, long now) {
        long until = now + (long) (seconds * 1000);
        chained.put(target.getUniqueId(), until);
        applyRoot(target, until);
        bindEffect(target);

        manager.message(attacker, "chain-bound-attacker", "{target}", target.getName());
        if (target instanceof Player victim) {
            manager.message(victim, "chain-bound-victim", "{player}", attacker.getName());
        }
    }

    /** Hitting two different people in a row (within the window) chains them together. */
    private void tryLink(Player attacker, LivingEntity target, ConfigurationSection cfg, long now) {
        if (cfg == null) return;
        if (!(target instanceof Player) && !cfg.getBoolean("include-mobs", false)) return;

        UUID attackerId = attacker.getUniqueId();
        long windowMs = (long) (cfg.getDouble("window", 5) * 1000);
        LastHit last = lastHits.get(attackerId);

        if (last != null && !last.target.equals(target.getUniqueId()) && now - last.time <= windowMs) {
            Entity previous = Bukkit.getEntity(last.target);
            Long cooldownEnd = linkCooldowns.get(attackerId);
            boolean onCooldown = cooldownEnd != null && now < cooldownEnd;

            if (previous instanceof LivingEntity other && other.isValid() && !other.isDead()
                    && !links.containsKey(other.getUniqueId()) && !links.containsKey(target.getUniqueId())
                    && !onCooldown) {

                long until = now + (long) (cfg.getDouble("duration", 10) * 1000);
                Link link = new Link(other.getUniqueId(), target.getUniqueId(), attackerId, until,
                        cfg.getDouble("share", 1.0));
                links.put(link.a, link);
                links.put(link.b, link);
                linkCooldowns.put(attackerId, now + (long) (cfg.getDouble("cooldown", 15) * 1000));
                lastHits.remove(attackerId);

                linkEffect(other, target);
                manager.message(attacker, "chain-linked-attacker", "{a}", other.getName(), "{b}", target.getName());
                if (other instanceof Player p) {
                    manager.message(p, "chain-linked-victim", "{other}", target.getName());
                }
                if (target instanceof Player p) {
                    manager.message(p, "chain-linked-victim", "{other}", other.getName());
                }
                return;
            }
        }
        lastHits.put(attackerId, new LastHit(target.getUniqueId(), now));
    }

    // ---------- Shared damage ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (mirroring || links.isEmpty()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        Link link = links.get(victim.getUniqueId());
        if (link == null) return;

        double amount = event.getFinalDamage() * link.share;
        if (amount <= 0) return;

        Entity otherEntity = Bukkit.getEntity(link.other(victim.getUniqueId()));
        if (!(otherEntity instanceof LivingEntity other) || !other.isValid() || other.isDead()) {
            endLink(link);
            return;
        }

        Player owner = Bukkit.getPlayer(link.owner);
        mirroring = true;
        ActiveAbilities.TRUE_DAMAGE.add(other.getUniqueId()); // the amount was already reduced once, don't reduce it again
        try {
            other.setNoDamageTicks(0);
            if (owner != null && owner.isOnline()) {
                other.damage(amount, owner);
            } else {
                other.damage(amount);
            }
        } finally {
            ActiveAbilities.TRUE_DAMAGE.remove(other.getUniqueId());
            mirroring = false;
        }

        // Pulse along the chain
        World w = victim.getWorld();
        Fx.burst(w, other.getLocation().add(0, other.getHeight() / 2.0, 0), 20, 0.25, Particle.CRIT);
        if (victim.getWorld().equals(other.getWorld())) {
            chainLine(w, victim.getLocation().add(0, 1, 0), other.getLocation().add(0, 1, 0), 0.4);
        }
    }

    // ---------- Keeping chained players in place ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (chained.isEmpty() || !chained.containsKey(event.getPlayer().getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location fixed = to.clone();
            fixed.setX(from.getX());
            fixed.setZ(from.getZ());
            event.setTo(fixed); // can still look around and fall, but can't walk
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!chained.containsKey(event.getPlayer().getUniqueId())) return;
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        chained.remove(id);
        Link link = links.get(id);
        if (link != null) endLink(link);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (chained.remove(id) != null) removeRoot(event.getPlayer());
        hitCounts.remove(id);
        lastHits.remove(id);
        Link link = links.get(id);
        if (link != null) endLink(link);
    }

    // ---------- Periodic: visuals + expiry ----------

    private void tick() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, Long>> it = chained.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Entity e = Bukkit.getEntity(entry.getKey());

            if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                it.remove();
                continue;
            }
            if (now >= entry.getValue()) {
                it.remove();
                removeRoot(living);
                breakEffect(living);
                continue;
            }
            applyRoot(living, entry.getValue()); // keep the effect topped up (milk, etc.)
            chainVisuals(living);
        }

        for (Link link : new HashSet<>(links.values())) {
            Entity ea = Bukkit.getEntity(link.a);
            Entity eb = Bukkit.getEntity(link.b);
            boolean ok = ea instanceof LivingEntity && eb instanceof LivingEntity
                    && ea.isValid() && eb.isValid() && !ea.isDead() && !eb.isDead() && now < link.until;
            if (!ok) {
                endLink(link);
                continue;
            }
            if (ea.getWorld().equals(eb.getWorld())) {
                chainLine(ea.getWorld(), ea.getLocation().add(0, 1, 0), eb.getLocation().add(0, 1, 0), 0.5);
            }
        }

        // Forget stale hit counts
        long cutoff = now - 60_000L;
        hitCounts.values().removeIf(map -> {
            map.values().removeIf(hc -> hc.last < cutoff);
            return map.isEmpty();
        });
    }

    private void endLink(Link link) {
        links.remove(link.a);
        links.remove(link.b);
        for (UUID id : new UUID[]{link.a, link.b}) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity living && living.isValid()) breakEffect(living);
        }
    }

    // ---------- Root (slowness) ----------

    private void applyRoot(LivingEntity entity, long untilMs) {
        PotionEffectType slow = PotionEffectType.getByKey(NamespacedKey.minecraft("slowness"));
        if (slow == null) return;
        int ticks = (int) Math.max(1, (untilMs - System.currentTimeMillis()) / 50) + 10;
        entity.addPotionEffect(new PotionEffect(slow, ticks, 255, true, false, false));
    }

    private void removeRoot(LivingEntity entity) {
        PotionEffectType slow = PotionEffectType.getByKey(NamespacedKey.minecraft("slowness"));
        if (slow != null) entity.removePotionEffect(slow);
    }

    // ---------- Visuals ----------

    /** Metal rings around the legs, waist and chest, plus chains anchoring the target to the ground. */
    private void chainVisuals(LivingEntity e) {
        World w = e.getWorld();
        Location base = e.getLocation();

        double[] heights = {0.15, 0.9, 1.5};
        for (double y : heights) {
            Fx.ringDust(w, base.clone().add(0, y, 0), 0.55, Fx.n(10), CHAIN_DARK, CHAIN_LIGHT, 1.3f);
        }
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2 + Math.PI / 4;
            Location body = base.clone().add(Math.cos(angle) * 0.4, 0.9, Math.sin(angle) * 0.4);
            Location anchor = base.clone().add(Math.cos(angle) * 1.8, 0.05, Math.sin(angle) * 1.8);
            chainLine(w, body, anchor, 0.35);
        }
    }

    /** A chain drawn as links of grey dust with the occasional spark. */
    private void chainLine(World w, Location from, Location to, double step) {
        org.bukkit.util.Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 0.1 || len > 64) return;
        d.normalize();
        double s = Math.max(step, len / 120.0);
        boolean alt = false;
        for (double x = 0; x <= len; x += s) {
            Location p = from.clone().add(d.clone().multiply(x));
            Fx.dust(w, p, 1, 0, alt ? CHAIN_LIGHT : CHAIN_DARK, CHAIN_LIGHT, 1.2f);
            alt = !alt;
        }
        Fx.spawn(w, Particle.CRIT, from.clone().add(d.clone().multiply(len / 2)), 1);
    }

    private void bindEffect(LivingEntity e) {
        World w = e.getWorld();
        Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        Location ground = e.getLocation().add(0, 0.15, 0);
        Fx.spawn(w, Particle.FLASH, mid, 1);
        Fx.burst(w, mid, 60, 0.35, Particle.CRIT);
        Fx.burst(w, mid, 25, 0.2, Particle.ELECTRIC_SPARK);
        Fx.ringDust(w, ground, 1.8, Fx.n(40), CHAIN_DARK, CHAIN_LIGHT, 2f);
        Fx.shockwave(w, ground, 3.5, 6, Particle.CRIT);
        w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.5f, 0.7f);
        w.playSound(mid, Sound.BLOCK_ANVIL_PLACE, 0.8f, 0.8f);
    }

    private void linkEffect(LivingEntity a, LivingEntity b) {
        for (LivingEntity e : new LivingEntity[]{a, b}) {
            World w = e.getWorld();
            Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
            Fx.spawn(w, Particle.FLASH, mid, 1);
            Fx.burst(w, mid, 40, 0.3, Particle.CRIT);
            Fx.ringDust(w, mid, 0.9, Fx.n(24), CHAIN_DARK, CHAIN_LIGHT, 1.8f);
            w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.5f, 1.0f);
        }
        if (a.getWorld().equals(b.getWorld())) {
            chainLine(a.getWorld(), a.getLocation().add(0, 1, 0), b.getLocation().add(0, 1, 0), 0.3);
        }
    }

    private void breakEffect(LivingEntity e) {
        World w = e.getWorld();
        Location mid = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        Fx.burst(w, mid, 40, 0.3, Particle.CRIT);
        Fx.ringDust(w, e.getLocation().add(0, 0.2, 0), 1.2, Fx.n(24), CHAIN_LIGHT, CHAIN_DARK, 1.6f);
        w.playSound(mid, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.2f, 1.5f);
    }

    private void actionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', text)));
    }
}
