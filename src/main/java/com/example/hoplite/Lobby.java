package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

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
    private final Set<BalloonDrop> balloonDrops = new HashSet<>();
    private final Map<UUID, BalloonDrop> playerBalloons = new HashMap<>();
    private final Set<UUID> slowFallingPlayers = new HashSet<>();

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
    private BukkitTask balloonTask;
    private BukkitTask winnerExitTask;
    private BukkitTask worldDeleteTask;
    private final Map<UUID, Integer> winnerReturnTicks = new HashMap<>();

    private static final class BalloonVisual {
        private final ArmorStand stand;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;

        private BalloonVisual(ArmorStand stand, double offsetX, double offsetY, double offsetZ) {
            this.stand = stand;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }
    }

    private static final class BalloonDrop {
        private final Minecart carrier;
        private final Vector direction;
        private final List<BalloonVisual> visuals = new ArrayList<>();
        private final Set<UUID> passengers = new HashSet<>();
        private double distanceTravelled;
        private int nextWarningDistance = 30;

        private BalloonDrop(Minecart carrier, Vector direction) {
            this.carrier = carrier;
            this.direction = direction;
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

    private synchronized void sendLobbyMessage(String message) {
        for (UUID playerId : new HashSet<>(players)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    public synchronized boolean addPlayer(Player p) {
        UUID playerId = p.getUniqueId();
        if (players.contains(playerId)) return true;
        if (activeGame || (partyMatch && !privatePartyMembers.contains(playerId))) return false;
        if (players.size() >= maxPlayers) return false;
        boolean refreshTeams = teamsAnnounced;
        players.add(playerId);
        p.sendMessage("§aJoined lobby " + id + " (" + players.size() + "/" + maxPlayers + ")");
        sendLobbyMessage("§a" + p.getName() + " joined your lobby (" + players.size() + "/" + maxPlayers + ")");

        if (players.size() >= minPlayers) startCountdownIfNeeded();
        if (players.size() == maxPlayers) startFullLobbyCountdown();
        else if (refreshTeams && teamsAnnounced) assignDuosAndNotify();
        return true;
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
        UUID playerId = p.getUniqueId();
        participants.remove(playerId);
        privatePartyMembers.remove(playerId);
        if (players.remove(playerId)) {
            sendLobbyMessage("§c" + p.getName() + " left your lobby (" + players.size() + "/" + maxPlayers + ")");
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
            if (timeLeft == 30) sendLobbyMessage("§e30 seconds until the game starts.");
            if (timeLeft == 10) sendLobbyMessage("§e10 seconds until the game starts.");
            if (timeLeft == 10 && "duos".equalsIgnoreCase(mode)) assignDuosAndNotify();
            if (timeLeft <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                startGame();
            }
        }, 20L, 20L);
        sendLobbyMessage("§eYour lobby countdown started: 60 seconds.");
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
            sendLobbyMessage("§eYour lobby is full. The game starts in 10 seconds.");
        }
        if ("duos".equalsIgnoreCase(mode)) assignDuosAndNotify();
    }

    private synchronized void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
            sendLobbyMessage("§cThe countdown was cancelled because there are not enough players.");
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
        sendLobbyMessage("§aYour game is starting.");

        if ("duos".equalsIgnoreCase(mode)) {
            applyTeammateNameTags();
        }

        for (UUID u : new HashSet<>(players)) {
            Player player = Bukkit.getPlayer(u);
            if (player != null && player.isOnline()) {
                player.sendTitle("§c§lWORLD LOADING", "§7Preparing the match world...", 10, 100, 10);
                Location center = plugin.getLobbyCenter(id);
                if (center != null) player.teleport(center);
            }
        }

        String cmd = "mv create " + gameWorld + " normal -g TerraformGenerator";
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);

        pollForGeneratedWorld(0);
    }

    private void pollForGeneratedWorld(int elapsedTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World gw = Bukkit.getWorld(gameWorld);
            if (gw == null) {
                if (elapsedTicks < 200) {
                    pollForGeneratedWorld(elapsedTicks + 10);
                } else {
                    sendLobbyMessage("§cFailed to create the game world. Please try again later.");
                    endGame();
                }
                return;
            }

            for (UUID u : new HashSet<>(players)) {
                Player player = Bukkit.getPlayer(u);
                if (player != null && player.isOnline()) {
                    player.sendTitle("§a§lWORLD GENERATED", "§fTeleporting now", 10, 60, 10);
                }
            }

                Location center = plugin.getLobbyCenter(id);
                gw.getWorldBorder().setCenter(center == null ? 0 : center.getX(),
                    center == null ? 0 : center.getZ());
            gw.getWorldBorder().setSize(1000.0);

            Bukkit.getScheduler().runTask(plugin, () -> ChestSpawner.populate(gw, 50));

            for (UUID u : new HashSet<>(players)) {
                Player player = Bukkit.getPlayer(u);
                if (player != null && player.isOnline()) {
                    clearPlayerInventory(player);
                    plugin.grantPendingKit(player);
                }
            }
            startBalloonDrops(gw);

            long graceSeconds = Math.max(0, plugin.getConfig().getLong("timers.grace-period-seconds", 300));
            gracePeriod = graceSeconds > 0;
            if (gracePeriod) {
                sendLobbyMessage("§eGrace period has started and will last " + graceSeconds + " seconds.");
                graceEndTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    gracePeriod = false;
                    graceEndTask = null;
                    for (UUID u : new HashSet<>(players)) {
                        Player pl = Bukkit.getPlayer(u);
                        if (pl != null && pl.isOnline()) {
                            pl.sendMessage("§eGrace period is over! Fight now!");
                        }
                    }
                }, graceSeconds * 20L);
            }

            long compassMinutes = Math.max(0, plugin.getConfig().getLong("timers.tracking-compass-minutes", 10));
            if (compassMinutes > 0) {
                compassEnableTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!activeGame) return;
                    sendLobbyMessage("§e" + compassMinutes + " minutes have passed. Everyone gets a tracking compass!");
                    for (UUID u : new HashSet<>(players)) {
                        Player pl = Bukkit.getPlayer(u);
                        if (pl != null && pl.isOnline()) {
                            pl.getInventory().addItem(new ItemStack(Material.COMPASS));
                        }
                    }
                    compassTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                        updateCompassTargets();
                    }, 0L, 20L);
                }, compassMinutes * 60 * 20L);
            }

            borderTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                World w = Bukkit.getWorld(gameWorld);
                if (w == null) return;
                double size = w.getWorldBorder().getSize();
                double next = Math.max(10.0, size - 10.0);
                w.getWorldBorder().setSize(next);
            }, 0L, 1200L);

        }, 10L);
    }

    private synchronized void startBalloonDrops(World world) {
        Set<UUID> assigned = new HashSet<>();
        for (UUID playerId : new HashSet<>(players)) {
            if (!assigned.add(playerId)) continue;
            Set<UUID> team = new LinkedHashSet<>();
            team.add(playerId);
            UUID teammate = teammates.get(playerId);
            if (teammate != null && players.contains(teammate)) {
                team.add(teammate);
                assigned.add(teammate);
            }
            beginBalloonDrop(team, world);
        }
    }

    private synchronized void beginBalloonDrop(Set<UUID> team, World world) {
        Location target = findSafeLanding(world);
        if (target == null) {
            for (UUID playerId : team) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    player.sendMessage("§cCould not find a safe landing spot. Please rejoin the match.");
                }
            }
            return;
        }

        double angle = Math.random() * Math.PI * 2;
        Vector direction = new Vector(Math.cos(angle), 0, Math.sin(angle)).normalize();
        Location start = target.clone().add(-direction.getX() * 100, 130, -direction.getZ() * 100);
        Minecart carrier = world.spawn(start, Minecart.class);
        carrier.setGravity(false);
        carrier.setInvulnerable(true);
        carrier.setSilent(true);
        carrier.setCustomName("§6Hot Air Balloon");
        carrier.setCustomNameVisible(true);

        BalloonDrop drop = new BalloonDrop(carrier, direction);
        spawnBalloonVisuals(drop, start, world);
        for (UUID playerId : team) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            player.setFallDistance(0);
            player.teleport(start);
            carrier.addPassenger(player);
            drop.passengers.add(playerId);
            playerBalloons.put(playerId, drop);
        }
        balloonDrops.add(drop);
        if (balloonTask == null) {
            balloonTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBalloons, 1L, 1L);
        }
    }

    private void spawnBalloonVisuals(BalloonDrop drop, Location base, World world) {
        Material[] layerColors = {
                Material.YELLOW_WOOL, Material.ORANGE_WOOL, Material.RED_WOOL,
                Material.ORANGE_WOOL, Material.YELLOW_WOOL
        };
        for (int layer = -2; layer <= 2; layer++) {
            int radiusSquared = 4 - Math.abs(layer);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (x * x + z * z > radiusSquared) continue;
                    double offsetX = x * 0.75;
                    double offsetY = 4.0 + layer * 0.75;
                    double offsetZ = z * 0.75;
                    ArmorStand stand = world.spawn(base.clone().add(offsetX, offsetY, offsetZ), ArmorStand.class);
                    stand.setInvisible(true);
                    stand.setMarker(true);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setSilent(true);
                    stand.getEquipment().setHelmet(new ItemStack(layerColors[layer + 2]));
                    drop.visuals.add(new BalloonVisual(stand, offsetX, offsetY, offsetZ));
                }
            }
        }
    }

    private Location findSafeLanding(World world) {
        Location center = plugin.getLobbyCenter(id);
        if (center != null) {
            Location centeredTarget = safeSurfaceAt(world, center.getBlockX(), center.getBlockZ());
            if (centeredTarget != null) return centeredTarget;
        }
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

    private synchronized void tickBalloons() {
        for (BalloonDrop drop : new HashSet<>(balloonDrops)) {
            if (!drop.carrier.isValid()) {
                finishBalloon(drop);
                continue;
            }
            if (drop.passengers.isEmpty()) {
                finishBalloon(drop);
                continue;
            }

            if (drop.distanceTravelled >= 100) {
                for (UUID playerId : new HashSet<>(drop.passengers)) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) departBalloon(player, drop);
                    else drop.passengers.remove(playerId);
                }
                finishBalloon(drop);
                continue;
            }

            double step = Math.min(0.25, 100 - drop.distanceTravelled);
            Location next = drop.carrier.getLocation().add(drop.direction.clone().multiply(step));
            next.setYaw((float) Math.toDegrees(Math.atan2(-drop.direction.getX(), drop.direction.getZ())));
            drop.carrier.teleport(next);
            drop.distanceTravelled += step;
            for (BalloonVisual visual : drop.visuals) {
                visual.stand.teleport(next.clone().add(visual.offsetX, visual.offsetY, visual.offsetZ));
            }

            int blocksRemaining = (int) Math.ceil(100 - drop.distanceTravelled);
            if (blocksRemaining <= drop.nextWarningDistance && drop.nextWarningDistance > 0) {
                for (UUID playerId : drop.passengers) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        int departIn = Math.max(0, blocksRemaining);
                        player.sendMessage("§eDepart in " + departIn
                                + " blocks. Shift to depart early.");
                    }
                }
                drop.nextWarningDistance -= 10;
            }
        }

        if (balloonDrops.isEmpty() && balloonTask != null) {
            balloonTask.cancel();
            balloonTask = null;
        }
    }

    public synchronized boolean departFromBalloon(Player player) {
        BalloonDrop drop = playerBalloons.get(player.getUniqueId());
        if (drop == null) return false;
        departBalloon(player, drop);
        return true;
    }

    private void departBalloon(Player player, BalloonDrop drop) {
        drop.carrier.removePassenger(player);
        playerBalloons.remove(player.getUniqueId());
        drop.passengers.remove(player.getUniqueId());
        slowFallingPlayers.add(player.getUniqueId());
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 60 * 60, 0, false, false));
        if (drop.passengers.isEmpty()) finishBalloon(drop);
    }

    private void finishBalloon(BalloonDrop drop) {
        balloonDrops.remove(drop);
        for (UUID playerId : new HashSet<>(drop.passengers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) departBalloon(player, drop);
        }
        drop.carrier.remove();
        for (BalloonVisual visual : drop.visuals) visual.stand.remove();
    }

    public synchronized boolean clearSlowFallingOnLanding(Player player, Location destination) {
        UUID playerId = player.getUniqueId();
        if (!slowFallingPlayers.contains(playerId) || destination == null) return false;
        Block feet = destination.getBlock();
        Block below = destination.getWorld().getBlockAt(destination.getBlockX(),
                (int) Math.floor(destination.getY() - 0.1), destination.getBlockZ());
        if (!feet.isLiquid() && !below.isLiquid() && !below.getType().isSolid()) return false;
        slowFallingPlayers.remove(playerId);
        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
        return true;
    }

    public synchronized boolean isProtectedFromFall(UUID player) {
        return playerBalloons.containsKey(player) || slowFallingPlayers.contains(player);
    }

    public synchronized void cancelBalloonDrop(UUID playerId) {
        BalloonDrop drop = playerBalloons.remove(playerId);
        if (drop != null) {
            drop.passengers.remove(playerId);
            if (drop.passengers.isEmpty()) finishBalloon(drop);
        }
        slowFallingPlayers.remove(playerId);
        if (Bukkit.getPlayer(playerId) != null) {
            Bukkit.getPlayer(playerId).removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
        if (balloonDrops.isEmpty() && balloonTask != null) {
            balloonTask.cancel();
            balloonTask = null;
        }
    }

    private synchronized void clearBalloonDrops() {
        if (balloonTask != null) {
            balloonTask.cancel();
            balloonTask = null;
        }
        for (BalloonDrop drop : new HashSet<>(balloonDrops)) {
            for (UUID playerId : new HashSet<>(drop.passengers)) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) player.leaveVehicle();
            }
            drop.carrier.remove();
            for (BalloonVisual visual : drop.visuals) visual.stand.remove();
        }
        balloonDrops.clear();
        playerBalloons.clear();
        for (UUID playerId : slowFallingPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.removePotionEffect(PotionEffectType.SLOW_FALLING);
            }
        }
        slowFallingPlayers.clear();
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
                Set<UUID> winnerIds = new HashSet<>();
                for (Player winner : remaining) winnerIds.add(winner.getUniqueId());
                String winnerName = remaining.isEmpty() ? "No one" : remaining.stream()
                    .map(Player::getName).collect(java.util.stream.Collectors.joining(", "));
                sendLobbyMessage("§6Game finished. Winner: " + winnerName);
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
                endGame(winnerIds);
            }
        } else {
            if (remaining.size() <= 1) {
                String winnerName = remaining.size() == 1 ? remaining.get(0).getName() : "No one";
                Set<UUID> winnerIds = new HashSet<>();
                if (remaining.size() == 1) {
                    Player winner = remaining.get(0);
                    winnerIds.add(winner.getUniqueId());
                    if (!partyMatch) plugin.addCoins(winner.getUniqueId(), 50);
                }
                sendLobbyMessage("§6Game finished. Winner: " + winnerName);
                if (remaining.size() == 1 && !partyMatch) {
                    UUID winnerId = remaining.get(0).getUniqueId();
                    plugin.adjustEloAfterMatch(participants, winnerId);
                }
                endGame(winnerIds);
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
        endGame(Collections.emptySet());
    }

    private synchronized void endGame(Set<UUID> winners) {
        activeGame = false;
        gracePeriod = false;
        clearBalloonDrops();
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
            if (winners.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                if (!plugin.teleportToPlayerSpawn(p) && lw != null) {
                    p.teleport(lw.getSpawnLocation());
                }
                clearPlayerInventory(p);
            }
        }
        players.clear();
        participants.clear();
        teammates.clear();
        teamsAnnounced = false;
        privatePartyMembers.clear();
        partyMatch = false;

        if (winners.isEmpty()) {
            deleteWorldWhenEmpty();
        } else {
            beginWinnerExit(winners);
        }
    }

    private synchronized void beginWinnerExit(Set<UUID> winners) {
        winnerReturnTicks.clear();
        for (UUID winner : winners) winnerReturnTicks.put(winner, 0);
        if (winnerExitTask != null) winnerExitTask.cancel();

        winnerExitTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            synchronized (Lobby.this) {
                World game = Bukkit.getWorld(gameWorld);
                for (UUID winnerId : new HashSet<>(winnerReturnTicks.keySet())) {
                    Player winner = Bukkit.getPlayer(winnerId);
                    if (winner == null || !winner.isOnline() || game == null || winner.getWorld() != game) {
                        if (winner != null && winner.isOnline()) {
                            winner.clearTitle();
                            clearPlayerInventory(winner);
                        }
                        winnerReturnTicks.remove(winnerId);
                        continue;
                    }

                    int elapsedTicks = winnerReturnTicks.get(winnerId);
                    if (elapsedTicks % 20 == 0) {
                        winner.sendTitle("§a§lYOU WON!", "§eReturning to spawn...", 0, 40, 0);
                    }
                    if (elapsedTicks >= 100) {
                        Location spawn = plugin.getPlayerSpawn();
                        boolean teleported = spawn != null
                                && !gameWorld.equals(spawn.getWorld().getName())
                                && winner.teleport(spawn);
                        if (!teleported && lwExistsOutsideGame()) {
                            World waitingWorld = Bukkit.getWorld(lobbyWorld);
                            teleported = waitingWorld != null
                                    && winner.teleport(waitingWorld.getSpawnLocation());
                        }
                        if (teleported && winner.getWorld() != game) {
                            winner.clearTitle();
                            clearPlayerInventory(winner);
                            winnerReturnTicks.remove(winnerId);
                        }
                    } else {
                        winnerReturnTicks.put(winnerId, elapsedTicks + 5);
                    }
                }

                if (winnerReturnTicks.isEmpty()) {
                    winnerExitTask.cancel();
                    winnerExitTask = null;
                    deleteWorldWhenEmpty();
                }
            }
        }, 0L, 5L);
    }

    private boolean lwExistsOutsideGame() {
        World waitingWorld = Bukkit.getWorld(lobbyWorld);
        return waitingWorld != null && !lobbyWorld.equals(gameWorld);
    }

    private void clearPlayerInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
    }

    private synchronized void deleteWorldWhenEmpty() {
        if (worldDeleteTask != null) worldDeleteTask.cancel();
        worldDeleteTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World world = Bukkit.getWorld(gameWorld);
            if (world != null && !world.getPlayers().isEmpty()) return;

            worldDeleteTask.cancel();
            worldDeleteTask = null;
            Bukkit.getServer().dispatchCommand(
                    Bukkit.getConsoleSender(), "mv delete " + gameWorld + " -f");
            Bukkit.getLogger().info("[Hoplite] Sent forced Multiverse deletion for game world: " + gameWorld);
        }, 1L, 20L);
    }
}
