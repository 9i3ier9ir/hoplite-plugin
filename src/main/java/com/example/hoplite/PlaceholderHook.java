package com.example.hoplite;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class PlaceholderHook extends PlaceholderExpansion {
    private final HoplitePlugin plugin;

    public PlaceholderHook(HoplitePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String getIdentifier() {
        return "hoplite";
    }

    @Override
    public String getAuthor() {
        return "Hoplite";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) return "";
        switch (identifier.toLowerCase()) {
            case "elo":
                return String.valueOf(plugin.getElo(player.getUniqueId()));
            case "coins":
                return String.valueOf(plugin.getCoins(player.getUniqueId()));
            case "inqueue":
                return String.valueOf(plugin.getWaitingQueue().contains(player.getUniqueId()) || plugin.getWaitingQueueDuos().contains(player.getUniqueId()));
            case "queue_status":
                if (plugin.getWaitingQueue().contains(player.getUniqueId())) {
                    return "singles";
                }
                if (plugin.getWaitingQueueDuos().contains(player.getUniqueId())) {
                    return "duos";
                }
                return "none";
            case "ingame":
                boolean inGame = plugin.getLobbies().stream().anyMatch(l -> l.isActiveGame() && l.hasPlayer(player.getUniqueId()));
                return String.valueOf(inGame);
            default:
                return "";
        }
    }
}
