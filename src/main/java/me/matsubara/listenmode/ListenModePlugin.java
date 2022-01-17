package me.matsubara.listenmode;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.injector.GamePhase;
import me.matsubara.listenmode.data.EntityData;
import me.matsubara.listenmode.glowapi.GlowAPI;
import me.matsubara.listenmode.listener.PlayerJoin;
import me.matsubara.listenmode.listener.PlayerQuit;
import me.matsubara.listenmode.listener.PlayerToggleSneak;
import me.matsubara.listenmode.listener.protocol.EntityMetadata;
import me.matsubara.listenmode.runnable.ListenTask;
import me.matsubara.listenmode.util.PluginUtils;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ListenModePlugin extends JavaPlugin {

    private Set<EntityData> data;
    private Set<ListenTask> tasks;

    @Override
    public void onEnable() {
        // Disable plugin if server version is older than 1.13.
        if (!PluginUtils.supports(13)) {
            getLogger().info("This plugin only works from 1.13 and up disabling...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            getLogger().warning("You must install ProtocolLib to use this plugin, disabling...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Config-related.
        saveDefaultConfig();

        // Initialize entities data.
        data = new HashSet<>();
        loadData();

        tasks = new HashSet<>();

        // Register bukkit listeners.
        getServer().getPluginManager().registerEvents(new PlayerJoin(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuit(this), this);
        getServer().getPluginManager().registerEvents(new PlayerToggleSneak(this), this);

        // Register protocol listeners.
        ProtocolLibrary.getProtocolManager().addPacketListener(new EntityMetadata(this));
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketListener() {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!reduceSoundVolume()) return;

                // Player isn't listening.
                if (!isListening(event.getPlayer())) return;

                Sound playing = event.getPacket().getSoundEffects().readSafely(0);

                // Don't reduce heart-beat sound.
                Sound sound = Sound.valueOf(getHeartBeatSound());
                if (isHeartBeatEnabled() && sound == playing) return;

                // Reduce volume.
                event.getPacket().getFloat().write(0, 0.1f);
            }

            @Override
            public void onPacketReceiving(PacketEvent event) {

            }

            @Override
            public ListeningWhitelist getSendingWhitelist() {
                return ListeningWhitelist
                        .newBuilder()
                        .types(PacketType.Play.Server.NAMED_SOUND_EFFECT, PacketType.Play.Server.CUSTOM_SOUND_EFFECT)
                        .priority(ListenerPriority.HIGHEST)
                        .gamePhase(GamePhase.PLAYING)
                        .monitor()
                        .build();
            }

            @Override
            public ListeningWhitelist getReceivingWhitelist() {
                return ListeningWhitelist
                        .newBuilder()
                        .build();
            }

            @Override
            public Plugin getPlugin() {
                return ListenModePlugin.this;
            }
        });

        PluginCommand command = getCommand("listenmode");
        if (command != null) command.setExecutor(this);
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equals("listenmode")) return true;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("listenmode.reload")) {
                sender.sendMessage(PluginUtils.translate(getConfig().getString("messages.no-permission")));
                return true;
            }

            for (ListenTask task : tasks) {
                // Cancel task.
                task.cancel();

                // Remove glow.
                task.removeGlowing();
            }

            tasks.clear();

            reloadConfig();
            loadData();
            sender.sendMessage(PluginUtils.translate(getConfig().getString("messages.reload")));
            return true;
        }

        sender.sendMessage(PluginUtils.translate(getConfig().getString("messages.invalid")));
        return true;
    }

    private void loadData() {
        data.clear();

        ConfigurationSection entitiesSection = getConfig().getConfigurationSection("entities");
        if (entitiesSection == null) return;

        for (String path : entitiesSection.getKeys(false)) {
            EntityType type;
            try {
                type = EntityType.valueOf(path);
            } catch (IllegalArgumentException exception) {
                getLogger().info("Invalid entity type: " + path);
                continue;
            }

            // If color is none, will use the color based on his behaviour.
            String colorString = getConfig().getString("entities." + path + ".color", "NONE");

            GlowAPI.Color color;
            try {
                color = GlowAPI.Color.valueOf(colorString);
            } catch (IllegalArgumentException exception) {
                color = GlowAPI.Color.NONE;
            }

            double radius = getConfig().getDouble("entities." + path + ".radius", getMaximumRadius());

            // No need to save.
            if (color == GlowAPI.Color.NONE && radius == getMaximumRadius()) continue;
            data.add(new EntityData(type, color, radius));
        }
    }

    public EntityData getDataByType(EntityType type) {
        for (EntityData data : this.data) {
            if (data.getType() == type) return data;
        }
        return new EntityData(type, GlowAPI.Color.NONE, getMaximumRadius());
    }

    public Set<ListenTask> getTasks() {
        return tasks;
    }

    public boolean isListening(Player player) {
        return getTask(player) != null;
    }

    public ListenTask getTask(Player player) {
        for (ListenTask task : tasks) {
            if (task.getPlayer().equals(player)) return task;
        }
        return null;
    }

    public double getMaximumRadius() {
        return getConfig().getDouble("maximum-radius");
    }

    public String getRequiredPermission() {
        return getConfig().getString("required-permission");
    }

    public GlowAPI.Color getDefaultColor(String type) {
        String colorString = getConfig().getString("default-colors." + type);
        try {
            return GlowAPI.Color.valueOf(colorString);
        } catch (IllegalArgumentException exception) {
            return GlowAPI.Color.WHITE;
        }
    }

    public boolean isRedWarningEnabled() {
        return getConfig().getBoolean("red-warning");
    }

    public boolean isFreezeEnabled() {
        return getConfig().getBoolean("freeze-effect.enabled");
    }

    public float getWalkSpeed() {
        return (float) getConfig().getDouble("freeze-effect.walk-speed");
    }

    public boolean preventJump() {
        return getConfig().getBoolean("freeze-effect.prevent-jump");
    }

    public boolean isHeartBeatEnabled() {
        return getConfig().getBoolean("heart-beat-effect.enabled");
    }

    public String getHeartBeatSound() {
        return getConfig().getString("heart-beat-effect.sound");
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isSoundOnly() {
        return isHeartBeatEnabled() && getConfig().getBoolean("heart-beat-effect.sound-only");
    }

    public boolean isSoundGlobal() {
        return getConfig().getBoolean("heart-beat-effect.sound-global");
    }

    public boolean reduceSoundVolume() {
        return getConfig().getBoolean("heart-beat-effect.reduce-sound-volume");
    }

    public List<String> getIgnoredEntities() {
        return getConfig().getStringList("ignored-entities");
    }

    public boolean ignoreProjectiles() {
        return getConfig().getBoolean("ignore-projectiles");
    }
}