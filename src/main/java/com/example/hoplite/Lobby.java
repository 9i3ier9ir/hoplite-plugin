package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

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
    private final Set<String> scoreboardTeamNames = new HashSet<>();
    private final Map<UUID, ChickenDrop> chickenDrops = new HashMap<>();

    private final Set<UUID> players = new HashSet<>();
    private Set<UUID> participants = new HashSet<>(); 
    private final Map<UUID,Integer> killCount = new HashMap<>(); 
    private BukkitTask countdownTask;
    private int timeLeft;
    private boolean activeGame = false;
    private boolean teamsAnnounced = false;
    private boolean partyMatch = false;
    private final Set<UUID> privatePartyMembers = new HashSet<>();
    private boolean gracePeriod = false;
    private BukkitTask graceEndTask;
    private BukkitTask compassTask;
    private BukkitTask compassEnableTask;
    private BukkitTask borderTask;
    private BukkitTask chickenDropTask;

    private static final class ChickenDrop {
        private final Chicken chicken;
        private final Location target;
        private boolean landing;
        private boolean warned;

        private ChickenDrop(Chicken chicken, Location target) {
            this.chicken = chicken;
            this.target = target;
        }
    }

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
    public synchronized UUID getTeammate(UUID player) { return teammates.get(player); }

    public synchronized boolean isAvailableForSingles() {
        return !activeGame && !partyMatch && players.size() < maxPlayers && !"duos".equalsIgnoreCase(mode);
    }

    public synchronized boolean isAvailableForDuos() {
        return !activeGame && !partyMatch && players.size() < maxPlayers && "duos".equalsIgnoreCase(mode);
    }

    public synchronized boolean isAvailableForParty() {
        return !activeGame && !partyMatch && players.isEmpty() && countdownTask == null;
    }

    public synchronized boolean hasPlayer(UUID uuid) {
        return players.contains(uuid);
    }

    public synchronized boolean isActiveGame() {
        return activeGame;
    }

    public synchronized boolean isParticipant(UUID uuid) {
        return participants.contains(uuid);
    }

    // --- LOBBY LOGIC ---

    public synchronized void addPlayer(Player p) {
        if (activeGame || (partyMatch && !privatePartyMembers.contains(p.getUniqueId()))) return;
        if (players.size() >= maxPlayers) return;
        boolean refreshTeams = teamsAnnounced;
        players.add(p.getUniqueId());
        p.sendMessage("§aJoined lobby " + id + " (" + players.size() + "/" + maxPlayers + ")");
         Bukkit.broadcastMessage("§a" + p.getName() + " Joined Queue " + players.size() + "/" + maxPlayers);

        if (players.size() >= minPlayers) startCountdownIfNeeded();
        if (players.size() == maxPlayers) startFullLobbyCountdown();
        else if (refreshTeams && teamsAnnounced) assignDuosAndNotify();
    }

    public synchronized boolean startPartyMatch(Collection<Player> partyPlayers) {
        if (!isAvailableForParty() || partyPlayers.size() < 2 || partyPlayers.size() > maxPlayers) return false;
        World waitingWorld = Bukkit.getWorld(lobbyWorld);
        if (waitingWorld == null) return false;
        for (Player player : partyPlayers) {
            if (player == null || !player.isOnline()) return false;
        }

        partyMatch = true;
        privatePartyMembers.clear();
        for (Player player : partyPlayers) {
            UUID playerId = player.getUniqueId();
            privatePartyMembers.add(playerId);
            players.add(playerId);
            player.teleport(waitingWorld.getSpawnLocation());
            player.sendMessage("§aYour party joined private lobby " + id + ".");
        }
        startFullLobbyCountdown();
        return true;
    }

    public synchronized void removePlayer(Player p) {
        if (players.remove(p.getUniqueId())) {
            Bukkit.broadcastMessage("§c" + p.getName() + " Left Queue " + players.size() + "/" + maxPlayers);
        }
        if (partyMatch && !activeGame) {
            privatePartyMembers.remove(p.getUniqueId());
            if (privatePartyMembers.size() < 2) {
                cancelCountdown();
                World waitingWorld = Bukkit.getWorld(lobbyWorld);
                for (UUID member : new HashSet<>(privatePartyMembers)) {
                    Player remaining = Bukkit.getPlayer(member);
                    if (remaining != null && remaining.isOnline()) {
                        if (!plugin.teleportToPlayerSpawn(remaining) && waitingWorld != null) {
                            remaining.teleport(waitingWorld.getSpawnLocation());
                        }
                        remaining.sendMessage("§eThe party game was cancelled because too few members remained.");
                    }
                }
                players.clear();
                privatePartyMembers.clear();
                partyMatch = false;
                return;
            }
        }
        if (!activeGame && players.size() < minPlayers) cancelCountdown();
        else if (!activeGame && teamsAnnounced) assignDuosAndNotify();
        if (activeGame) checkForWinner();
    }

    private synchronized void startCountdownIfNeeded() {
        if (countdownTask != null) return;
        timeLeft = 60;
        teamsAnnounced = false;
        teammates.clear();
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            timeLeft--;
            if (timeLeft == 30) Bukkit.broadcastMessage("30-second warning");
            if (timeLeft == 10) Bukkit.broadcastMessage("10-second warning");
            if (timeLeft == 10 && "duos".equalsIgnoreCase(mode)) assignDuosAndNotify();
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
        if (partyMatch) {
            for (UUID member : privatePartyMembers) {
                Player player = Bukkit.getPlayer(member);
                if (player != null) player.sendMessage("§eYour party game starts in 10 seconds.");
            }
        } else {
            Bukkit.broadcastMessage("Lobby " + id + " is full — 10-second start");
        }
        if ("duos".equalsIgnoreCase(mode)) assignDuosAndNotify();
    }

    private synchronized void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
            Bukkit.broadcastMessage("Lobby " + id + " countdown cancelled (not enough players)");
        }
        teamsAnnounced = false;
        teammates.clear();
    }

    // --- GAME LOGIC ---

    private synchronized void startGame() {
        if (activeGame) return;
        if ("duos".equalsIgnoreCase(mode) && !teamsAnnounced) assignDuosAndNotify();
        activeGame = true;
        participants = new HashSet<>(players);
        Bukkit.broadcastMessage("Starting game for lobby " + id);

        if ("duos".equalsIgnoreCase(mode)) {
            applyTeammateNameTags();
        }

        String cmd = "mv create " + gameWorld + " normal -g TerraformGenerator";
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World gw = Bukkit.getWorld(gameWorld);
            if (gw == null) {
                Bukkit.broadcastMessage("Failed to find created world: " + gameWorld);
                endGame();
                return;
            }

            gw.getWorldBorder().setCenter(0, 0);
            gw.getWorldBorder().setSize(1000.0);

            Bukkit.getScheduler().runTask(plugin, () -> ChestSpawner.populate(gw, 50));

            for (UUID u : new HashSet<>(players)) {
                Player pl = Bukkit.getPlayer(u);
                if (pl != null && pl.isOnline()) {
                    beginChickenDrop(pl, gw);
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

    private synchronized void beginChickenDrop(Player player, World world) {
        Location target = findSafeLanding(world);
        if (target == null) {
            player.sendMessage("§cCould not find a safe landing spot. Please rejoin the match.");
            return;
        }

        double angle = Math.random() * Math.PI * 2;
        Location start = target.clone().add(Math.cos(angle) * 50, 130, Math.sin(angle) * 50);
        Chicken chicken = world.spawn(start, Chicken.class);
        chicken.setAI(false);
        chicken.setInvulnerable(true);
        chicken.setSilent(true);
        player.setFallDistance(0);
        player.teleport(start);
        chicken.addPassenger(player);
        chickenDrops.put(player.getUniqueId(), new ChickenDrop(chicken, target));
        if (chickenDropTask == null) {
            chickenDropTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickChickenDrops, 1L, 1L);
        }
    }

    private Location findSafeLanding(World world) {
        Random random = new Random();
        for (int attempt = 0; attempt < 300; attempt++) {
            int x = random.nextInt(761) - 380;
            int z = random.nextInt(761) - 380;
            Location location = safeSurfaceAt(world, x, z);
            if (location != null) return location;
        }
        return null;
    }

    private Location safeSurfaceAt(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        Block ground = world.getBlockAt(x, y, z);
        Material type = ground.getType();
        if (!type.isSolid() || type == Material.WATER || type == Material.LAVA || type.name().endsWith("_LEAVES")
                || type == Material.CACTUS || type == Material.MAGMA_BLOCK
                || !ground.getRelative(0, 1, 0).isPassable()
                || !ground.getRelative(0, 2, 0).isPassable()) {
            return null;
        }
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    private synchronized void tickChickenDrops() {
        Iterator<Map.Entry<UUID, ChickenDrop>> iterator = chickenDrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ChickenDrop> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            ChickenDrop drop = entry.getValue();
            if (player == null || !player.isOnline() || player.isDead() || !drop.chicken.isValid()) {
                drop.landing = true;
                if (drop.chicken.isValid()) drop.chicken.remove();
                iterator.remove();
                continue;
            }

            player.setFallDistance(0);
            Location current = drop.chicken.getLocation();
            double dx = drop.target.getX() - current.getX();
            double dz = drop.target.getZ() - current.getZ();
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

            if (drop.chicken.isOnGround()) {
                Location landing = safeSurfaceAt(current.getWorld(), current.getBlockX(), current.getBlockZ());
                landChickenDrop(player, drop, landing == null ? drop.target : landing);
                iterator.remove();
                continue;
            }
            if (horizontalDistance <= 0.8 && current.getY() <= drop.target.getY() + 1.0) {
                landChickenDrop(player, drop, drop.target);
                iterator.remove();
                continue;
            }

            Vector horizontal = new Vector(dx, 0, dz);
            if (horizontalDistance > 0.8) horizontal.normalize().multiply(0.25);
            double verticalSpeed = Math.max(-0.8, Math.min(-0.5,
                    (drop.target.getY() - current.getY()) * 0.006));
            drop.chicken.setVelocity(new Vector(horizontal.getX(), verticalSpeed, horizontal.getZ()));
        }

        if (chickenDrops.isEmpty() && chickenDropTask != null) {
            chickenDropTask.cancel();
            chickenDropTask = null;
        }
    }

    private void landChickenDrop(Player player, ChickenDrop drop, Location landing) {
        drop.landing = true;
        drop.chicken.eject();
        drop.chicken.remove();
        player.teleport(landing);
        player.setFallDistance(0);
    }

    public synchronized boolean preventChickenDismount(Player player, Entity vehicle) {
        ChickenDrop drop = chickenDrops.get(player.getUniqueId());
        if (drop == null || drop.landing || !drop.chicken.getUniqueId().equals(vehicle.getUniqueId())) return false;
        if (!drop.warned) {
            player.sendMessage("§eYou can't get off your chicken until it's on the ground.");
            drop.warned = true;
        }
        return true;
    }

    public synchronized boolean isProtectedFromFall(UUID player) {
        return chickenDrops.containsKey(player);
    }

    public synchronized void cancelChickenDrop(UUID playerId) {
        ChickenDrop drop = chickenDrops.remove(playerId);
        if (drop != null) {
            drop.landing = true;
            drop.chicken.eject();
            drop.chicken.remove();
        }
        if (chickenDrops.isEmpty() && chickenDropTask != null) {
            chickenDropTask.cancel();
            chickenDropTask = null;
        }
    }

    private synchronized void clearChickenDrops() {
        for (ChickenDrop drop : chickenDrops.values()) {
            drop.landing = true;
            drop.chicken.eject();
            drop.chicken.remove();
        }
        chickenDrops.clear();
        if (chickenDropTask != null) {
            chickenDropTask.cancel();
            chickenDropTask = null;
        }
    }

    private synchronized void assignDuosAndNotify() {
        if (!"duos".equalsIgnoreCase(mode)) return;
        teammates.clear();
        List<UUID> list = new ArrayList<>(players);
        for (int i = 0; i + 1 < list.size(); i += 2) {
            UUID first = list.get(i);
            UUID second = list.get(i + 1);
            teammates.put(first, second);
            teammates.put(second, first);

            Player firstPlayer = Bukkit.getPlayer(first);
            Player secondPlayer = Bukkit.getPlayer(second);
            if (firstPlayer != null && secondPlayer != null) {
                firstPlayer.sendMessage("§aYour teammate is " + secondPlayer.getName() + "!");
                secondPlayer.sendMessage("§aYour teammate is " + firstPlayer.getName() + "!");
            }
        }
        teamsAnnounced = true;
    }

    private void applyTeammateNameTags() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Set<UUID> assigned = new HashSet<>();
        int teamNumber = 1;
        for (Map.Entry<UUID, UUID> entry : teammates.entrySet()) {
            UUID first = entry.getKey();
            UUID second = entry.getValue();
            if (!assigned.add(first) || !assigned.add(second)) continue;

            String teamName = "hp" + id + "t" + teamNumber++;
            Team existing = scoreboard.getTeam(teamName);
            if (existing != null) existing.unregister();
            Team team = scoreboard.registerNewTeam(teamName);
            team.setColor(ChatColor.GREEN);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            Player firstPlayer = Bukkit.getPlayer(first);
            Player secondPlayer = Bukkit.getPlayer(second);
            if (firstPlayer != null) team.addEntry(firstPlayer.getName());
            if (secondPlayer != null) team.addEntry(secondPlayer.getName());
            scoreboardTeamNames.add(teamName);
        }
    }

    private void clearTeammateNameTags() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String teamName : scoreboardTeamNames) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) team.unregister();
        }
        scoreboardTeamNames.clear();
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
        if (k != null && !partyMatch) {
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
                if (!partyMatch && remaining.size() >= 1) {
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
                    if (!partyMatch) plugin.addCoins(winner.getUniqueId(), 50);
                }
                Bukkit.broadcastMessage("Game " + id + " finished. Winner: " + winnerName);
                if (remaining.size() == 1 && !partyMatch) {
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
            privatePartyMembers.clear();
            partyMatch = false;
        }
    }

    private synchronized void endGame() {
        activeGame = false;
        clearChickenDrops();
        clearTeammateNameTags();
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
        for (UUID u : new HashSet<>(participants)) {
            plugin.disableTeamChat(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                if (!plugin.teleportToPlayerSpawn(p) && lw != null) {
                    p.teleport(lw.getSpawnLocation());
                }
            }
        }
        players.clear();
        participants.clear();
        teammates.clear();
        teamsAnnounced = false;
        privatePartyMembers.clear();
        partyMatch = false;

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
