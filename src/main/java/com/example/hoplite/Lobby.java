package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class Lobby {
    private final HoplitePlugin plugin;
    private final int id;
    private final String lobbyWorld;
    private final String gameWorld;
    private final int maxPlayers;
    private final int minPlayers;

    private final Set<UUID> players = new HashSet<>();
    private Set<UUID> participants = new HashSet<>(); // for ELO adjustments
    private final Map<UUID,Integer> killCount = new HashMap<>(); // kills in current game
    private BukkitTask countdownTask;
    private int timeLeft;
    private boolean activeGame = false;
    private BukkitTask borderTask;

    public Lobby(HoplitePlugin plugin, int id, String lobbyWorld, String gameWorld, int maxPlayers, int minPlayers) {
        this.plugin = plugin;
        this.id = id;
        this.lobbyWorld = lobbyWorld;
        this.gameWorld = gameWorld;
        this.maxPlayers = maxPlayers;
        this.minPlayers = minPlayers;
    }

    public int getId() { return id; }
    public String getLobbyWorld() { return lobbyWorld; }
    public String getGameWorld() { return gameWorld; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getPlayerCount() { return players.size(); }

    public synchronized void addPlayer(Player p) {
        if (activeGame) return;
        if (players.size() >= maxPlayers) return;
        players.add(p.getUniqueId());
        p.sendMessage("Joined lobby " + id + " (" + players.size() + "/" + maxPlayers + ")");

        if (players.size() >= minPlayers) startCountdownIfNeeded();
        if (players.size() == maxPlayers) startFullLobbyCountdown();
    }

    public synchronized void removePlayer(Player p) {
        players.remove(p.getUniqueId());
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

    private synchronized void startGame() {
        if (activeGame) return;
        activeGame = true;
        // capture participants now that game is about to start
        participants = new HashSet<>(players);
        Bukkit.broadcastMessage("Starting game for lobby " + id);

        // Create game world via Multiverse
        String cmd = "mv create " + gameWorld + " normal -g TerraformGenerator";
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);

        // Schedule teleport 10s after creation
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World gw = Bukkit.getWorld(gameWorld);
            if (gw == null) {
                Bukkit.broadcastMessage("Failed to find created world: " + gameWorld);
                activeGame = false;
                return;
            }

            // Set initial border
            gw.getWorldBorder().setCenter(0, 0);
            gw.getWorldBorder().setSize(1000.0);

            // Teleport players
            for (UUID u : new HashSet<>(players)) {
                Player pl = Bukkit.getPlayer(u);
                if (pl != null && pl.isOnline()) {
                    Location loc = randomLocationInWorld(gw, 500);
                    pl.teleport(loc);
                }
            }

            // Start border shrink: shrink 10 blocks per minute (size units)
            borderTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    World w = Bukkit.getWorld(gameWorld);
                    if (w == null) return;
                    double size = w.getWorldBorder().getSize();
                    double next = Math.max(10.0, size - 10.0);
                    w.getWorldBorder().setSize(next);
                }
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

    public synchronized void recordKill(UUID killer) {
        killCount.put(killer, killCount.getOrDefault(killer, 0) + 1);
        Player k = Bukkit.getPlayer(killer);
        if (k != null) {
            plugin.addCoins(killer, 5); // 5 coins per kill
        }
    }

    public synchronized void checkForWinner() {
        if (!activeGame) return;
        List<Player> remaining = new ArrayList<>();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline() && p.getWorld().getName().equals(gameWorld)) remaining.add(p);
        }
        if (remaining.size() <= 1) {
            String winnerName = remaining.size() == 1 ? remaining.get(0).getName() : "No one";
            // announce winner with big green title to winner
            if (remaining.size() == 1) {
                Player winner = remaining.get(0);
                winner.sendTitle("§a§lYOU WON!", "", 10, 70, 20);
                plugin.addCoins(winner.getUniqueId(), 50); // 50 coins for win
            }
            Bukkit.broadcastMessage("Game " + id + " finished. Winner: " + winnerName);
            // adjust elo before ending
            if (remaining.size() == 1) {
                UUID winnerId = remaining.get(0).getUniqueId();
                plugin.adjustEloAfterMatch(participants, winnerId);
            }
            endGame();
        }
    }

    private synchronized void endGame() {
        activeGame = false;
        if (borderTask != null) {
            borderTask.cancel();
            borderTask = null;
        }

        // Teleport any remaining players back to lobby and clear
        for (UUID u : new HashSet<>(players)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                World lw = Bukkit.getWorld(lobbyWorld);
                if (lw != null) p.teleport(lw.getSpawnLocation());
            }
        }
        players.clear();

        // Remove game world via Multiverse. Deletion requires confirmation; we'll issue the delete
        // command then immediately send a confirm using the world name as code (Multiverse typically
        // echoes a random code, and in many setups the world name also works as a shorthand).
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + gameWorld);
        // confirm after short delay to allow MV to register the pending deletion
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "mv confirm " + gameWorld);
        }, 2L);

        // Fill lobby from waiting queue
        Queue<UUID> queue = plugin.getWaitingQueue();
        while (queue.size() > 0 && players.size() < maxPlayers) {
            UUID next = queue.poll();
            Player p = Bukkit.getPlayer(next);
            if (p != null && p.isOnline()) {
                p.teleport(Bukkit.getWorld(lobbyWorld).getSpawnLocation());
                players.add(next);
            }
        }

        // If after filling we have enough players, start countdown
        if (players.size() >= minPlayers) startCountdownIfNeeded();
    }

    public synchronized void forceReset() {
        if (countdownTask != null) countdownTask.cancel();
        if (borderTask != null) borderTask.cancel();
        countdownTask = null;
        borderTask = null;
        players.clear();
        killCount.clear();
        activeGame = false;
    }

    public boolean isActiveGame() { return activeGame; }

    public boolean hasPlayer(UUID u) { return players.contains(u); }
}
