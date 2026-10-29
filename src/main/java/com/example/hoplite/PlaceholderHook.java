package com.example.hoplite;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class PlaceholderHook extends PlaceholderExpansion {
    private final HoplitePlugin plugin;
    private final String identifier;

    public PlaceholderHook(HoplitePlugin plugin) {
        this(plugin, "hoplite");
    }

    public PlaceholderHook(HoplitePlugin plugin, String identifier) {
        this.plugin = plugin;
        this.identifier = identifier;
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
        return identifier;
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
        if (this.identifier.equals("cleader")) {
            return plugin.getCoinLeaderboardEntry(parseRank(identifier));
        }
        if (this.identifier.equals("eloleader")) {
            return plugin.getEloLeaderboardEntry(parseRank(identifier));
        }
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

    private int parseRank(String identifier) {
        try {
            int rank = Integer.parseInt(identifier);
            return rank >= 1 && rank <= 10 ? rank : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
