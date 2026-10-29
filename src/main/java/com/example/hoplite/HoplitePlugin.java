package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

public class HoplitePlugin extends JavaPlugin {
    private final Map<Integer, Lobby> lobbies = new HashMap<>();
    private final Queue<UUID> waitingQueue = new LinkedList<>();
    private final Queue<UUID> waitingQueueDuos = new LinkedList<>();
    private final Map<UUID,Integer> elo = new HashMap<>();
    private final Map<UUID,Long> coins = new HashMap<>();
    private final Set<UUID> teamChatEnabled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> parties = new HashMap<>();
    private final Map<UUID, UUID> partyOwnerByMember = new HashMap<>();
    private final Map<UUID, UUID> partyInvitations = new HashMap<>();
    private File eloFile;
    private File coinsFile;
    private YamlConfiguration eloYaml;
    private YamlConfiguration coinsYaml;

    @Override
    public void onEnable() {
        getLogger().info("HoplitePlugin enabling...");

        saveDefaultConfig();
        setupDataFiles();
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
            String mode = entry.containsKey("mode") ? (String)entry.get("mode") : "singles";

            World w = Bukkit.getWorld(lobbyWorld);
            if (w == null) {
                getLogger().info("Lobby world " + lobbyWorld + " is not loaded; attempting to load it.");
                w = new WorldCreator(lobbyWorld).createWorld();
            }
            if (w == null) {
                getLogger().severe("Configured lobby world missing: " + lobbyWorld + " — check your config.");
                // do not disable plugin; just skip this lobby
                continue;
            }
            Lobby lobby = new Lobby(this, id++, lobbyWorld, gameWorld, max, min, mode);
            lobbies.put(lobby.getId(), lobby);
        }

        // register event listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Register PlaceholderAPI expansion if available
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new PlaceholderHook(this).register();
                new PlaceholderHook(this, "cleader").register();
                new PlaceholderHook(this, "eloleader").register();
                getLogger().info("Registered PlaceholderAPI expansions.");
            } catch (NoClassDefFoundError e) {
                getLogger().warning("PlaceholderAPI present but expansion failed to register (build without PAPI?).");
            }
        }

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
        getCommand("teamchat").setExecutor(executor);
        getCommand("hoplitesetspawn").setExecutor(executor);
        getCommand("broadcast").setExecutor(executor);
        getCommand("party").setExecutor(executor);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; skipping PlaceholderAPI expansion.");
        }

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

    public Queue<UUID> getWaitingQueueDuos() {
        return waitingQueueDuos;
    }

    public Queue<UUID> getWaitingQueue() {
        return waitingQueue;
    }

    public java.util.Collection<Lobby> getLobbies() {
        return lobbies.values();
    }

    public void setPlayerSpawn(Location location) {
        getConfig().set("spawn.world", location.getWorld().getName());
        getConfig().set("spawn.x", location.getX());
        getConfig().set("spawn.y", location.getY());
        getConfig().set("spawn.z", location.getZ());
        getConfig().set("spawn.yaw", location.getYaw());
        getConfig().set("spawn.pitch", location.getPitch());
        saveConfig();
    }

    public Location getPlayerSpawn() {
        String worldName = getConfig().getString("spawn.world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
            if (!worldFolder.isDirectory()) return null;
            world = new WorldCreator(worldName).createWorld();
        }
        if (world == null) return null;
        return new Location(world,
                getConfig().getDouble("spawn.x"),
                getConfig().getDouble("spawn.y"),
                getConfig().getDouble("spawn.z"),
                (float) getConfig().getDouble("spawn.yaw"),
                (float) getConfig().getDouble("spawn.pitch"));
    }

    public boolean teleportToPlayerSpawn(Player player) {
        Location spawn = getPlayerSpawn();
        if (spawn == null) return false;
        player.teleport(spawn);
        return true;
    }

    public boolean toggleTeamChat(UUID player) {
        if (teamChatEnabled.remove(player)) return false;
        teamChatEnabled.add(player);
        return true;
    }

    public boolean isTeamChatEnabled(UUID player) {
        return teamChatEnabled.contains(player);
    }

    public void disableTeamChat(UUID player) {
        teamChatEnabled.remove(player);
    }

    public synchronized boolean createParty(UUID owner) {
        if (partyOwnerByMember.containsKey(owner)) return false;
        Set<UUID> members = new LinkedHashSet<>();
        members.add(owner);
        parties.put(owner, members);
        partyOwnerByMember.put(owner, owner);
        return true;
    }

    public synchronized UUID getPartyOwner(UUID player) {
        return partyOwnerByMember.get(player);
    }

    public synchronized List<UUID> getPartyMembers(UUID player) {
        UUID owner = partyOwnerByMember.get(player);
        Set<UUID> members = owner == null ? null : parties.get(owner);
        return members == null ? new ArrayList<>() : new ArrayList<>(members);
    }

    public synchronized boolean isPartyOwner(UUID player) {
        return parties.containsKey(player);
    }

    public synchronized boolean invitePartyMember(UUID owner, UUID invitee) {
        if (!parties.containsKey(owner) || partyOwnerByMember.containsKey(invitee)) return false;
        partyInvitations.put(invitee, owner);
        return true;
    }

    public synchronized boolean joinParty(UUID player, UUID owner) {
        if (partyOwnerByMember.containsKey(player)
                || !parties.containsKey(owner)
                || !owner.equals(partyInvitations.get(player))) return false;
        parties.get(owner).add(player);
        partyOwnerByMember.put(player, owner);
        partyInvitations.remove(player);
        return true;
    }

    public synchronized boolean leaveParty(UUID player) {
        UUID owner = partyOwnerByMember.get(player);
        if (owner == null || owner.equals(player)) return false;
        Set<UUID> members = parties.get(owner);
        if (members != null) members.remove(player);
        partyOwnerByMember.remove(player);
        return true;
    }

    public synchronized List<UUID> disbandParty(UUID owner) {
        Set<UUID> members = parties.remove(owner);
        if (members == null) return new ArrayList<>();
        List<UUID> disbandedMembers = new ArrayList<>(members);
        for (UUID member : disbandedMembers) partyOwnerByMember.remove(member);
        partyInvitations.entrySet().removeIf(invitation -> owner.equals(invitation.getValue()));
        return disbandedMembers;
    }

    // ELO management
    private void loadElo() {
        if (eloYaml != null && eloYaml.contains("elo")) {
            for (String key : eloYaml.getConfigurationSection("elo").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    int val = eloYaml.getInt("elo." + key, 1000);
                    elo.put(id, val);
                } catch (IllegalArgumentException ignore) {
                }
            }
        }
    }

    public int getElo(UUID player) {
        return elo.computeIfAbsent(player, u -> {
            int val = 1000;
            if (eloYaml != null) val = eloYaml.getInt("elo." + u.toString(), 1000);
            else val = getConfig().getInt("elo." + u.toString(), 1000);
            return val;
        });
    }

    public void changeElo(UUID player, int delta) {
        int current = getElo(player);
        int updated = current + delta;
        elo.put(player, updated);
        if (eloYaml != null) {
            eloYaml.set("elo." + player.toString(), updated);
            try { eloYaml.save(eloFile); } catch (IOException ignored) {}
        }
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

    public String getEloLeaderboardEntry(int rank) {
        if (rank < 1 || rank > 10) return "";
        List<Map.Entry<UUID,Integer>> list = new ArrayList<>(elo.entrySet());
        list.sort((a,b) -> b.getValue().compareTo(a.getValue()));
        if (rank > list.size()) return "";
        Map.Entry<UUID,Integer> entry = list.get(rank - 1);
        String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
        return (name == null ? "Unknown" : name) + " - " + entry.getValue();
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
        if (coinsYaml != null && coinsYaml.contains("coins")) {
            for (String key : coinsYaml.getConfigurationSection("coins").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    long val = coinsYaml.getLong("coins." + key, 0);
                    coins.put(id, val);
                } catch (IllegalArgumentException ignore) {
                }
            }
        }
    }

    public long getCoins(UUID player) {
        return coins.computeIfAbsent(player, u -> {
            long val = 0;
            if (coinsYaml != null) val = coinsYaml.getLong("coins." + u.toString(), 0);
            else val = getConfig().getLong("coins." + u.toString(), 0);
            return val;
        });
    }

    public void addCoins(UUID player, long amount) {
        long current = getCoins(player);
        long updated = current + amount;
        coins.put(player, updated);
        if (coinsYaml != null) {
            coinsYaml.set("coins." + player.toString(), updated);
            try { coinsYaml.save(coinsFile); } catch (IOException ignored) {}
        }
    }

    public boolean removeCoins(UUID player, long amount) {
        long current = getCoins(player);
        if (current < amount) return false;
        long updated = current - amount;
        coins.put(player, updated);
        if (coinsYaml != null) {
            coinsYaml.set("coins." + player.toString(), updated);
            try { coinsYaml.save(coinsFile); } catch (IOException ignored) {}
        }
        return true;
    }

    private void setupDataFiles() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        eloFile = new File(getDataFolder(), "elo.yml");
        coinsFile = new File(getDataFolder(), "coins.yml");
        try {
            if (!eloFile.exists()) eloFile.createNewFile();
            if (!coinsFile.exists()) coinsFile.createNewFile();
        } catch (IOException ignored) {}
        eloYaml = YamlConfiguration.loadConfiguration(eloFile);
        coinsYaml = YamlConfiguration.loadConfiguration(coinsFile);
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

    public String getCoinLeaderboardEntry(int rank) {
        if (rank < 1 || rank > 10) return "";
        List<Map.Entry<UUID,Long>> list = new ArrayList<>(coins.entrySet());
        list.sort((a,b) -> b.getValue().compareTo(a.getValue()));
        if (rank > list.size()) return "";
        Map.Entry<UUID,Long> entry = list.get(rank - 1);
        String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
        return (name == null ? "Unknown" : name) + " - " + entry.getValue() + " coins";
    }
}
