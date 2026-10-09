package com.example.weapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared particle toolkit: rings, shockwaves, bursts, helixes, beams.
 *
 * Every spawn is wrapped in try/catch, so a particle type that doesn't exist (or needs different data)
 * on your server version just doesn't show - it can never break an ability.
 * Overall amount is scaled by "effects.intensity" in config.yml.
 */
public final class Fx {

    private static WeaponPlugin plugin;
    private static double intensity = 1.0;

    private Fx() {}

    public static void init(WeaponPlugin p) {
        plugin = p;
        double value = p.getConfig().getDouble("effects.intensity", 1.0);
        intensity = Math.max(0.1, Math.min(3.0, value));
    }

    /** Scales a particle count by the configured intensity (never below 1). */
    public static int n(int base) {
        return Math.max(1, (int) Math.round(base * intensity));
    }

    // ---------- Basic spawning ----------

    public static void spawn(World w, Particle p, Location l, int count) {
        spawn(w, p, l, count, 0, 0, 0, 0);
    }

    public static void spawn(World w, Particle p, Location l, int count, double dx, double dy, double dz, double speed) {
        try {
            w.spawnParticle(p, l, count, dx, dy, dz, speed);
        } catch (Throwable ignored) {
            // particle not supported here
        }
    }

    /** A single particle that flies in the given direction (uses the count-0 velocity trick). */
    public static void fly(World w, Particle p, Location l, Vector dir, double speed) {
        try {
            w.spawnParticle(p, l, 0, dir.getX(), dir.getY(), dir.getZ(), speed);
        } catch (Throwable ignored) {
            // particle not supported here
        }
    }

    /** Coloured dust that fades from one colour to another. */
    public static void dust(World w, Location l, int count, double spread, Color from, Color to, float size) {
        try {
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, count, spread, spread, spread, 0,
                    new Particle.DustTransition(from, to, size));
        } catch (Throwable ignored) {
            // not supported here
        }
    }

    // ---------- Shapes ----------

    /** Flat horizontal ring. */
    public static void ring(World w, Location c, double radius, int points, Particle p) {
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            spawn(w, p, c.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius), 1);
        }
    }

    /** Flat horizontal ring of fading coloured dust. */
    public static void ringDust(World w, Location c, double radius, int points, Color from, Color to, float size) {
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            dust(w, c.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius), 1, 0, from, to, size);
        }
    }

    /** Ring standing perpendicular to a direction (like a ring you fly through). */
    public static void ringFacing(World w, Location c, Vector dir, double radius, int points, Particle p) {
        Vector n = dir.clone().normalize();
        Vector up = Math.abs(n.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = n.clone().crossProduct(up).normalize();
        Vector v = n.clone().crossProduct(u).normalize();
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            Vector off = u.clone().multiply(Math.cos(a) * radius).add(v.clone().multiply(Math.sin(a) * radius));
            spawn(w, p, c.clone().add(off), 1);
        }
    }

    /** Ring that expands outward over several ticks. */
    public static void shockwave(World w, Location center, double maxRadius, int ticks, Particle p) {
        if (plugin == null || ticks < 1) {
            ring(w, center, maxRadius, n(30), p);
            return;
        }
        final Location c = center.clone();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double r = maxRadius * t / ticks;
                int pts = Math.min(200, n((int) Math.max(14, r * 10)));
                ring(w, c, r, pts, p);
                if (t >= ticks) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Rising spiral (several strands) around a point. */
    public static void helix(World w, Location base, double radius, double height, int ticks, Particle p, int strands) {
        if (plugin == null || ticks < 1) return;
        final Location b = base.clone();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                for (int s = 0; s < strands; s++) {
                    for (int k = 0; k < 3; k++) { // short tail so it reads as a continuous strand
                        double a = (t - k * 0.35) * 0.7 + s * 2 * Math.PI / strands;
                        double h = height * (t - k * 0.35) / ticks;
                        if (h < 0) continue;
                        spawn(w, p, b.clone().add(Math.cos(a) * radius, h, Math.sin(a) * radius), 1);
                    }
                }
                if (t >= ticks) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Vertical column of rings (a pillar of light / lightning). */
    public static void pillar(World w, Location base, double height, double radius, Particle p) {
        for (double h = 0; h <= height; h += 0.5) {
            ring(w, base.clone().add(0, h, 0), radius, n(10), p);
        }
        spawn(w, p, base.clone().add(0, height / 2, 0), n(40), 0.15, height / 2, 0.15, 0.02);
    }

    /** Straight line of particles between two points. */
    public static void line(World w, Location from, Location to, double step, Particle p) {
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.normalize();
        double s = Math.max(step, len / 160.0); // cap the amount for very long lines
        for (double x = 0; x <= len; x += s) {
            spawn(w, p, from.clone().add(d.clone().multiply(x)), 1);
        }
    }

    // ---------- Bursts ----------

    /** Explosion of particles flying out in all directions. */
    public static void burst(World w, Location c, int count, double speed, Particle p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int amount = n(count);
        for (int i = 0; i < amount; i++) {
            Vector d = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
            if (d.lengthSquared() < 1e-6) continue;
            fly(w, p, c, d.normalize(), speed * (0.5 + r.nextDouble()));
        }
    }

    /** Particles spraying out in a cone around a direction. */
    public static void cone(World w, Location origin, Vector dir, double spread, int count, double speed, Particle p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector base = dir.clone().normalize();
        int amount = n(count);
        for (int i = 0; i < amount; i++) {
            Vector d = base.clone().add(new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).multiply(spread));
            if (d.lengthSquared() < 1e-6) continue;
            fly(w, p, origin, d.normalize(), speed * (0.5 + r.nextDouble()));
        }
    }

    /** Particles that start away from the center and stream inward (charging up). */
    public static void inward(World w, Location center, double dist, int count, double speed, Particle p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int amount = n(count);
        for (int i = 0; i < amount; i++) {
            Vector d = new Vector(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
            if (d.lengthSquared() < 1e-6) continue;
            d.normalize();
            fly(w, p, center.clone().add(d.clone().multiply(dist)), d.clone().multiply(-1), speed);
        }
    }
}
