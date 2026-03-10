package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

public class HoplitePlugin extends JavaPlugin {
    private final Map<Integer, Lobby> lobbies = new HashMap<>();
    private final Queue<UUID> waitingQueue = new LinkedList<>();
    private final Map<UUID,Integer> elo = new HashMap<>();
    private final Map<UUID,Long> coins = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("HoplitePlugin enabling...");

        saveDefaultConfig();
        loadElo();
        loadCoins();

        // Build lobby list from configuration
        lobbies.clear();
        int id = 1;
        for (Map<?,?> entry : getConfig().getMapList("lobbies")) {
            String lobbyWorld = (String)entry.get("lobbyWorld");
            String gameWorld = (String)entry.get("gameWorld");
            int max = entry.containsKey("maxPlayers") ? ((Number)entry.get("maxPlayers")).intValue() : 10;
            int min = entry.containsKey("minPlayers") ? ((Number)entry.get("minPlayers")).intValue() : 5;

            World w = Bukkit.getWorld(lobbyWorld);
            if (w == null) {
                getLogger().severe("Configured lobby world missing: " + lobbyWorld + " — check your config.");
                // do not disable plugin; just skip this lobby
                continue;
            }
            Lobby lobby = new Lobby(this, id++, lobbyWorld, gameWorld, max, min);
            lobbies.put(lobby.getId(), lobby);
        }

        // register event listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // register commands
        HopliteCommand executor = new HopliteCommand(this);
        getCommand("queue").setExecutor(executor);
        getCommand("q").setExecutor(executor);
        getCommand("elo").setExecutor(executor);
        getCommand("eloleader").setExecutor(executor);
        getCommand("cbal").setExecutor(executor);
        getCommand("cleader").setExecutor(executor);
        getCommand("coingive").setExecutor(executor);
        getCommand("k").setExecutor(executor);
        getCommand("leaveq").setExecutor(executor);

        // custom recipes
        addCustomRecipes();

        getLogger().info("HoplitePlugin enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HoplitePlugin disabling...");
        lobbies.values().forEach(Lobby::forceReset);
    }

    public Lobby getLobbyByWorld(String worldName) {
        return lobbies.values().stream().filter(l -> l.getLobbyWorld().equals(worldName)).findFirst().orElse(null);
    }

    public Lobby getLobbyById(int id) {
        return lobbies.get(id);
    }

    public Queue<UUID> getWaitingQueue() {
        return waitingQueue;
    }

    public java.util.Collection<Lobby> getLobbies() {
        return lobbies.values();
    }

    // ELO management
    private void loadElo() {
        if (getConfig().contains("elo")) {
            for (String key : getConfig().getConfigurationSection("elo").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    int val = getConfig().getInt("elo." + key, 1000);
                    elo.put(id, val);
                } catch (IllegalArgumentException ignore) {
                }
            }
        }
    }

    public int getElo(UUID player) {
        return elo.computeIfAbsent(player, u -> {
            int val = getConfig().getInt("elo." + u.toString(), 1000);
            return val;
        });
    }

    public void changeElo(UUID player, int delta) {
        int current = getElo(player);
        int updated = current + delta;
        elo.put(player, updated);
        getConfig().set("elo." + player.toString(), updated);
        saveConfig();
    }

    public void adjustEloAfterMatch(Set<UUID> participants, UUID winner) {
        // simple system: winner +25, losers -5
        for (UUID p : participants) {
            if (p.equals(winner)) {
                changeElo(p, 25);
            } else {
                changeElo(p, -5);
            }
        }
    }

    public void sendLeaderboard(org.bukkit.command.CommandSender sender) {
        List<Map.Entry<UUID,Integer>> list = new ArrayList<>(elo.entrySet());
        list.sort((a,b) -> b.getValue().compareTo(a.getValue()));
        sender.sendMessage("---- ELO Leaderboard ----");
        int rank=1;
        for (Map.Entry<UUID,Integer> e : list) {
            if (rank>10) break;
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            sender.sendMessage(rank + ". " + name + " - " + e.getValue());
            rank++;
        }
    }

    private void addCustomRecipes() {
        // golden apple recipe: 8 gold ingots around apple
        NamespacedKey gaKey = new NamespacedKey(this, "custom_golden_apple");
        ShapedRecipe ga = new ShapedRecipe(gaKey, new ItemStack(Material.GOLDEN_APPLE));
        ga.shape("GGG", "GAG", "GGG");
        ga.setIngredient('G', Material.GOLD_INGOT);
        ga.setIngredient('A', Material.APPLE);
        getServer().addRecipe(ga);

        // auto-smelter pickaxe: iron pickaxe with coal in top corners
        NamespacedKey apKey = new NamespacedKey(this, "autosmelter_pickaxe");
        ItemStack auto = new ItemStack(Material.IRON_PICKAXE);
        ShapedRecipe ap = new ShapedRecipe(apKey, auto);
        ap.shape("CIC", " I ", " I ");
        ap.setIngredient('C', Material.COAL);
        ap.setIngredient('I', Material.IRON_INGOT);
        getServer().addRecipe(ap);
    }

    // Coin management
    private void loadCoins() {
        if (getConfig().contains("coins")) {
            for (String key : getConfig().getConfigurationSection("coins").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    long val = getConfig().getLong("coins." + key, 0);
                    coins.put(id, val);
                } catch (IllegalArgumentException ignore) {
                }
            }
        }
    }

    public long getCoins(UUID player) {
        return coins.computeIfAbsent(player, u -> {
            long val = getConfig().getLong("coins." + u.toString(), 0);
            return val;
        });
    }

    public void addCoins(UUID player, long amount) {
        long current = getCoins(player);
        long updated = current + amount;
        coins.put(player, updated);
        getConfig().set("coins." + player.toString(), updated);
        saveConfig();
    }

    public boolean removeCoins(UUID player, long amount) {
        long current = getCoins(player);
        if (current < amount) return false;
        long updated = current - amount;
        coins.put(player, updated);
        getConfig().set("coins." + player.toString(), updated);
        saveConfig();
        return true;
    }

    public void sendCoinLeaderboard(org.bukkit.command.CommandSender sender) {
        List<Map.Entry<UUID,Long>> list = new ArrayList<>(coins.entrySet());
        list.sort((a,b) -> b.getValue().compareTo(a.getValue()));
        sender.sendMessage("---- Coin Leaderboard ----");
        int rank=1;
        for (Map.Entry<UUID,Long> e : list) {
            if (rank>10) break;
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            sender.sendMessage(rank + ". " + name + " - " + e.getValue() + " coins");
            rank++;
        }
    }
}
