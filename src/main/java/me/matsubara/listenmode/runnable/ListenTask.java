package me.matsubara.listenmode.runnable;

import me.matsubara.listenmode.ListenModePlugin;
import me.matsubara.listenmode.data.EntityData;
import me.matsubara.listenmode.glowapi.GlowAPI;
import me.matsubara.listenmode.util.PluginUtils;
import me.matsubara.listenmode.util.RedWarning;
import org.bukkit.Sound;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class ListenTask extends BukkitRunnable {

    // Instance of the plugin.
    private final ListenModePlugin plugin;

    // Instance of the player who's using this ability.
    private final Player player;

    // Previous walking speed.
    private final float walkingSpeed;

    // Previous jump potion effect.
    private final PotionEffect jumpEffect;

    // Previous speed potion effect.
    private final PotionEffect speedEffect;

    // Map containing the respective team of a player (if any).
    private final Map<String, Team> teams;

    // The breathing sound.
    private final Sound sound;

    // Heart-beat effect related.
    private int beats;
    private final int frequence;

    public ListenTask(ListenModePlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;

        // Save copy of previous walking speed.
        this.walkingSpeed = player.getWalkSpeed();

        // Save copy of previous potion effects.
        this.jumpEffect = player.getPotionEffect(PotionEffectType.JUMP);
        this.speedEffect = player.getPotionEffect(PotionEffectType.SPEED);

        if (!plugin.isSoundOnly()) {
            player.removePotionEffect(PotionEffectType.SPEED);
        }

        if (plugin.isFreezeEnabled()) {
            // Reduce walk speed (default is .2f).
            player.setWalkSpeed(plugin.getWalkSpeed());

            if (plugin.preventJump()) {
                // Remove previous effect.
                player.removePotionEffect(PotionEffectType.JUMP);

                // Prevent player from jumping.
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, Integer.MAX_VALUE, -10, false, false));
            }
        }

        this.teams = new HashMap<>();

        this.sound = Sound.valueOf(plugin.getHeartBeatSound());

        this.beats = 0;
        this.frequence = 32;

        runTaskTimer(plugin, 1L, 1L);
    }

    @Override
    public void run() {
        if (player.isSneaking()) {
            double radius = plugin.getMaximumRadius() + 5.0d;

            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (!appliesTo(entity)) continue;

                EntityData data = plugin.getDataByType(entity.getType());

                // Out of range; remove glow for near entities (if any).
                if (player.getLocation().distance(entity.getLocation()) > data.getRadius()) {
                    removeGlowing(entity, player);
                    continue;
                }

                // Already glowing.
                if (GlowAPI.isGlowing(entity, player)) continue;

                // Handle tamed.
                if (isTamedBy(entity, player)) {
                    GlowAPI.setGlowing(entity, plugin.getDefaultColor("tamed"), player);
                    continue;
                }

                // Handle players (they may be on a real team).
                if (entity instanceof Player) {
                    Team team = getPlayerTeam(player.getScoreboard(), (Player) entity);
                    if (team != null) teams.put(entity.getName(), team);
                }

                GlowAPI.Color color = data.getColor();
                if (color == GlowAPI.Color.NONE) color = getByType(entity);

                GlowAPI.setGlowing(entity, color, player);
            }

            // Play red warning effect if enabled.
            if (plugin.isRedWarningEnabled()) RedWarning.warning(player, true);

            // Play heart-beat effect if enabled.
            if (plugin.isHeartBeatEnabled()) playHeartBeat();

            return;
        }

        cancel();
        plugin.getTasks().remove(this);

        removeGlowing();

        // Remove red warning effect if enabled.
        if (plugin.isRedWarningEnabled()) RedWarning.warning(player, false);

        // Set previous walking speed & jump effect.
        if (plugin.isFreezeEnabled()) {
            player.setWalkSpeed(walkingSpeed);

            if (plugin.preventJump()) {
                player.removePotionEffect(PotionEffectType.JUMP);
                if (jumpEffect != null) player.addPotionEffect(jumpEffect);
            }
        }

        // Set previous speed effect.
        if (!plugin.isSoundOnly()) {
            player.removePotionEffect(PotionEffectType.SPEED);
            if (speedEffect != null) player.addPotionEffect(speedEffect);
        }
    }

    private GlowAPI.Color getByType(Entity entity) {
        if (isBoss(entity)) {
            return plugin.getDefaultColor("boss");
        } else if (isMonster(entity)) {
            return plugin.getDefaultColor("monster");
        }
        return plugin.getDefaultColor("passive");
    }

    private boolean isMonster(Entity entity) {
        return entity instanceof Monster || PluginUtils.containsAny(entity.getType().name(), "GHAST", "PHANTOM");
    }

    private boolean isBoss(Entity entity) {
        return entity.getType() == EntityType.ENDER_DRAGON || entity.getType() == EntityType.WITHER;
    }

    private Team getPlayerTeam(Scoreboard board, Player player) {
        for (Team team : board.getTeams()) {
            if (team.getEntries().contains(player.getName())) return team;
        }
        return null;
    }

    @SuppressWarnings("ConstantConditions")
    private boolean isTamedBy(Entity entity, Player owner) {
        if (!(entity instanceof Tameable)) return false;
        Tameable tameable = (Tameable) entity;
        return tameable.isTamed() && tameable.getOwner().getUniqueId().equals(owner.getUniqueId());
    }

    public void removeGlowing() {
        double radius = plugin.getMaximumRadius() + 5.0d;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (appliesTo(entity)) removeGlowing(entity, player);
        }
    }

    private void removeGlowing(Entity entity, Player player) {
        if (!GlowAPI.isGlowing(entity, player)) return;

        // Remove glowing.
        GlowAPI.setGlowing(entity, false, player);

        // Update previous team status.
        if (entity instanceof Player) {
            updateTeam((Player) entity);
        }
    }

    private void updateTeam(Player player) {
        String name = player.getName();

        Team team = teams.get(name);
        if (team == null) return;

        // Re-add to the team.
        team.removeEntry(name);
        team.addEntry(name);
    }

    private boolean appliesTo(Entity entity) {
        // We won't add glow of an entity who's already glowing for everyone due to potion effect.
        if (entity instanceof LivingEntity && ((LivingEntity) entity).hasPotionEffect(PotionEffectType.GLOWING)) {
            return false;
        }
        boolean projectiles = !(entity instanceof Projectile) || !plugin.ignoreProjectiles();
        return !plugin.getIgnoredEntities().contains(entity.getType().name()) && projectiles;
    }

    private void playHeartBeat() {
        beats++;

        if (beats < frequence) return;

        if (beats == frequence) {
            playHeartBeat(player, true);
            return;
        }

        if (beats > (frequence + frequence / 3)) {
            playHeartBeat(player, false);
            beats = 0;
        }
    }

    private void playHeartBeat(Player player, boolean breathingEffect) {
        // Play breathing effect.
        if (breathingEffect && !plugin.isSoundOnly()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 10, 0, false, false));
        }

        // Play breathing sound.
        if (sound == null) return;

        // Random pitch to make the effect more realistic.
        float pitch = (float) ThreadLocalRandom.current().nextDouble(-1.0d, 1.0d);

        if (plugin.isSoundGlobal()) {
            player.getWorld().playSound(player.getLocation(), sound, 1.0f, pitch);
        } else {
            player.playSound(player.getLocation(), sound, 1.0f, pitch);
        }
    }

    public Player getPlayer() {
        return player;
    }
}