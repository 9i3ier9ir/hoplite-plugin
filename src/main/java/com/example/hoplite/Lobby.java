package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;

public class Lobby {
    private final HoplitePlugin plugin;
    private final int id;
    private final String lobbyWorld;
    private final String gameWorld;
    private final int maxPlayers;
    private final int minPlayers;
    private final String mode; // "singles" or "duos"
    private Map<UUID,UUID> teammates = new HashMap<>();

    private final Set<UUID> players = new HashSet<>();
    private Set<UUID> participants = new HashSet<>(); 
    private final Map<UUID,Integer> killCount = new HashMap<>(); 
    private BukkitTask countdownTask;
    private int timeLeft;
    private boolean activeGame = false;
    private boolean gracePeriod = false;
    private BukkitTask graceEndTask;
    private BukkitTask compassTask;
    private BukkitTask compassEnableTask;
    private BukkitTask borderTask;

    public Lobby(HoplitePlugin plugin, int id, String lobbyWorld, String gameWorld, int maxPlayers, int minPlayers) {
        this(plugin, id, lobbyWorld, gameWorld, maxPlayers, minPlayers, "singles");
    }

    public Lobby(HoplitePlugin plugin, int id, String lobbyWorld, String gameWorld, int maxPlayers, int minPlayers, String mode) {
        this.plugin = plugin;
        this.id = id;
        this.lobbyWorld = lobbyWorld;
        this.gameWorld = gameWorld;
        this.maxPlayers = maxPlayers;
        this.minPlayers = minPlayers;
        this.mode = mode == null ? "singles" : mode;
    }

    // --- GETTERS & STATUS CHECKS (Fixed missing symbols) ---

    public int getId() { return id; }
    public String getLobbyWorld() { return lobbyWorld; }
    public String getGameWorld() { return gameWorld; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getPlayerCount() { return players.size(); }

    public String getMode() { return mode; }
    public UUID getTeammate(UUID player) { return teammates.get(player); }

    public boolean isAvailableForSingles() {
        return !activeGame && players.size() < maxPlayers && !"duos".equalsIgnoreCase(mode);
    }

    public boolean isAvailableForDuos() {
        return !activeGame && players.size() < maxPlayers && "duos".equalsIgnoreCase(mode);
    }

    public boolean hasPlayer(UUID uuid) {
        return players.contains(uuid);
    }

    public boolean isActiveGame() {
        return activeGame;
    }

    // --- LOBBY LOGIC ---

    public synchronized void addPlayer(Player p) {
        if (activeGame) return;
        if (players.size() >= maxPlayers) return;
        players.add(p.getUniqueId());
        p.sendMessage("§aJoined lobby " + id + " (" + players.size() + "/" + maxPlayers + ")");
         Bukkit.broadcastMessage("§a" + p.getName() + " Joined Queue " + players.size() + "/" + maxPlayers);

        if (players.size() >= minPlayers) startCountdownIfNeeded();
        if (players.size() == maxPlayers) startFullLobbyCountdown();
    }

    public synchronized void removePlayer(Player p) {
        if (players.remove(p.getUniqueId())) {
            Bukkit.broadcastMessage("§c" + p.getName() + " Left Queue " + players.size() + "/" + maxPlayers);
        }
        if (!activeGame && players.size() < minPlayers) cancelCountdown();
        if (activeGame) checkForWinner();
    }

    private synchronized void startCountdownIfNeeded() {
        if (countdownTask != null) return;
        timeLeft = 60;
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            timeLeft--;
            if (timeLeft == 30) Bukkit.broadcastMessage("30-second warning");
            if (timeLeft == 10) Bukkit.broadcastMessage("10-second warning");
            if (timeLeft <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                startGame();
            }
        }, 20L, 20L);
        Bukkit.broadcastMessage("Lobby " + id + " countdown started: 60 seconds");
    }

    private synchronized void startFullLobbyCountdown() {
        if (countdownTask != null) countdownTask.cancel();
        timeLeft = 10;
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            timeLeft--;
            if (timeLeft == 0) {
                countdownTask.cancel();
                countdownTask = null;
                startGame();
            }
        }, 20L, 20L);
        Bukkit.broadcastMessage("Lobby " + id + " is full — 10-second start");
    }

    private synchronized void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
            Bukkit.broadcastMessage("Lobby " + id + " countdown cancelled (not enough players)");
        }
    }

    // --- GAME LOGIC ---

    private synchronized void startGame() {
        if (activeGame) return;
        activeGame = true;
        participants = new HashSet<>(players);
        Bukkit.broadcastMessage("Starting game for lobby " + id);

        // when duos mode, pair teammates arbitrarily
        if ("duos".equalsIgnoreCase(mode)) {
            List<UUID> list = new ArrayList<>(participants);
            teammates.clear();
            for (int i = 0; i + 1 < list.size(); i += 2) {
                UUID a = list.get(i);
                UUID b = list.get(i+1);
                teammates.put(a, b);
                teammates.put(b, a);
            }
        }

        String cmd = "mv create " + gameWorld + " normal -g TerraformGenerator";
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World gw = Bukkit.getWorld(gameWorld);
            if (gw == null) {
                Bukkit.broadcastMessage("Failed to find created world: " + gameWorld);
                activeGame = false;
                return;
            }

            gw.getWorldBorder().setCenter(0, 0);
            gw.getWorldBorder().setSize(1000.0);

            Bukkit.getScheduler().runTask(plugin, () -> ChestSpawner.populate(gw, 50));

            for (UUID u : new HashSet<>(players)) {
                Player pl = Bukkit.getPlayer(u);
                if (pl != null && pl.isOnline()) {
                    Location loc = randomLocationInWorld(gw, 500);
                    pl.teleport(loc);
                }
            }

            gracePeriod = true;
            Bukkit.broadcastMessage("Grace period has started for lobby " + id + " and will last 5 minutes.");
            graceEndTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                gracePeriod = false;
                for (UUID u : new HashSet<>(players)) {
                    Player pl = Bukkit.getPlayer(u);
                    if (pl != null && pl.isOnline()) {
                        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "msg " + pl.getName() + " Grace period is over! Fight now!");
                    }
                }
            }, 5 * 60 * 20L);

            compassEnableTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!activeGame) return;
                Bukkit.broadcastMessage("10 minutes have passed — everyone gets a tracking compass!");
                for (UUID u : new HashSet<>(players)) {
                    Player pl = Bukkit.getPlayer(u);
                    if (pl != null && pl.isOnline()) {
                        pl.getInventory().addItem(new ItemStack(Material.COMPASS));
                    }
                }
                compassTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    updateCompassTargets();
                }, 0L, 20L);
            }, 10 * 60 * 20L);

            borderTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                World w = Bukkit.getWorld(gameWorld);
                if (w == null) return;
                double size = w.getWorldBorder().getSize();
                double next = Math.max(10.0, size - 10.0);
                w.getWorldBorder().setSize(next);
            }, 0L, 1200L);

        }, 200L);
    }

    private Location randomLocationInWorld(World w, int radius) {
        Random r = new Random();
        int x = r.nextInt(radius * 2) - radius;
        int z = r.nextInt(radius * 2) - radius;
        int y = w.getHighestBlockYAt(x, z) + 1;
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    public synchronized void playerDied(UUID playerId) {
        players.remove(playerId);
        checkForWinner();
    }

    public synchronized boolean isGracePeriod() {
        return gracePeriod;
    }

    private void updateCompassTargets() {
        List<Player> livePlayers = new ArrayList<>();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline() && p.getWorld().getName().equals(gameWorld)) {
                livePlayers.add(p);
            }
        }
        for (Player p : livePlayers) {
            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (Player other : livePlayers) {
                if (other.equals(p)) continue;
                if (!other.getWorld().equals(p.getWorld())) continue;
                double dist = p.getLocation().distanceSquared(other.getLocation());
                if (dist < nearestDistance) {
                    nearestDistance = dist;
                    nearest = other;
                }
            }
            if (nearest != null) {
                p.setCompassTarget(nearest.getLocation());
            }
        }
    }

    public synchronized void recordKill(UUID killer) {
        killCount.put(killer, killCount.getOrDefault(killer, 0) + 1);
        Player k = Bukkit.getPlayer(killer);
        if (k != null) {
            plugin.addCoins(killer, 5);
        }
    }

    public synchronized void checkForWinner() {
        if (!activeGame) return;
        List<Player> remaining = new ArrayList<>();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline() && p.getWorld().getName().equals(gameWorld)) remaining.add(p);
        }
        if ("duos".equalsIgnoreCase(mode)) {
            // determine remaining teams
            Set<UUID> aliveTeams = new HashSet<>();
            for (Player p : remaining) {
                UUID uid = p.getUniqueId();
                UUID mate = teammates.get(uid);
                if (mate != null) {
                    aliveTeams.add(mate);
                    aliveTeams.add(uid);
                } else {
                    aliveTeams.add(uid);
                }
            }
            // If zero or one team/player remains, end
            if (aliveTeams.size() <= 2) {
                String winnerName = remaining.size() == 1 ? remaining.get(0).getName() : "No one";
                Bukkit.broadcastMessage("Game " + id + " finished. Winner: " + winnerName);
                if (remaining.size() >= 1) {
                    // award both members if team exists
                    Player pwin = remaining.get(0);
                    UUID winUid = pwin.getUniqueId();
                    UUID mate = teammates.get(winUid);
                    if (mate != null) {
                        plugin.addCoins(winUid, 50);
                        plugin.addCoins(mate, 50);
                        plugin.changeElo(winUid, 25);
                        plugin.changeElo(mate, 25);
                        // losers -5
                        for (UUID part : participants) {
                            if (!part.equals(winUid) && !part.equals(mate)) plugin.changeElo(part, -5);
                        }
                    } else {
                        plugin.addCoins(winUid, 50);
                        plugin.changeElo(winUid, 25);
                        for (UUID part : participants) {
                            if (!part.equals(winUid)) plugin.changeElo(part, -5);
                        }
                    }
                }
                endGame();
            }
        } else {
            if (remaining.size() <= 1) {
                String winnerName = remaining.size() == 1 ? remaining.get(0).getName() : "No one";
                if (remaining.size() == 1) {
                    Player winner = remaining.get(0);
                    winner.sendTitle("§a§lYOU WON!", "", 10, 70, 20);
                    plugin.addCoins(winner.getUniqueId(), 50);
                }
                Bukkit.broadcastMessage("Game " + id + " finished. Winner: " + winnerName);
                if (remaining.size() == 1) {
                    UUID winnerId = remaining.get(0).getUniqueId();
                    plugin.adjustEloAfterMatch(participants, winnerId);
                }
                endGame();
            }
        }
    }

    // --- CLEANUP & DELETION LOGIC ---

    public void forceReset() {
        if (activeGame) {
            endGame();
        } else {
            cancelCountdown();
            players.clear();
        }
    }

    private synchronized void endGame() {
        activeGame = false;
        if (borderTask != null) {
            borderTask.cancel();
            borderTask = null;
        }
        if (graceEndTask != null) {
            graceEndTask.cancel();
            graceEndTask = null;
        }
        if (compassEnableTask != null) {
            compassEnableTask.cancel();
            compassEnableTask = null;
        }
        if (compassTask != null) {
            compassTask.cancel();
            compassTask = null;
        }

        World lw = Bukkit.getWorld(lobbyWorld);
        for (UUID u : new HashSet<>(players)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                if (lw != null) p.teleport(lw.getSpawnLocation());
            }
        }
        players.clear();

        // 1. Remove from Multiverse (Bypasses confirmation code)
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "mv remove " + gameWorld);

        // 2. Unload world and delete files
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World world = Bukkit.getWorld(gameWorld);
            if (world != null) {
                Bukkit.unloadWorld(world, false);
            }

            File worldFolder = new File(Bukkit.getWorldContainer(), gameWorld);
            deleteDirectory(worldFolder);
            
            Bukkit.getLogger().info("[Hoplite] Successfully deleted game world: " + gameWorld);
        }, 40L); 
    }

    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}
