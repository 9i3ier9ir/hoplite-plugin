package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HopliteCommand implements CommandExecutor {
    private final HoplitePlugin plugin;

    public HopliteCommand(HoplitePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();
        if (cmd.equals("hoplitereload")) {
            if (!sender.hasPermission("hoplite.reload")) {
                sender.sendMessage("§cYou do not have permission to reload HoplitePlugin.");
                return true;
            }
            plugin.reloadAllConfigs();
            sender.sendMessage("§aHoplite configuration reloaded.");
            return true;
        }
        if (cmd.equals("broadcast")) {
            handleBroadcast(sender, args);
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        Player p = (Player) sender;
        switch (cmd) {
            case "queue":
            case "q":
                handleQueue(p, args);
                return true;
            case "leaveq":
                handleLeaveQ(p);
                return true;
            case "hoplitesetspawn":
                plugin.setPlayerSpawn(p.getLocation());
                p.sendMessage("§aHoplite spawn set to your current location.");
                return true;
            case "lobbycenter":
                if (args.length != 1) {
                    p.sendMessage("§eUsage: /lobbycenter <lobby-id>");
                    return true;
                }
                try {
                    int lobbyId = Integer.parseInt(args[0]);
                    if (plugin.setLobbyCenter(lobbyId, p.getLocation())) {
                        p.sendMessage("§aCenter saved for queue lobby " + lobbyId + ".");
                    } else {
                        p.sendMessage("§cNo queue lobby has id " + lobbyId + ".");
                    }
                } catch (NumberFormatException exception) {
                    p.sendMessage("§cLobby id must be a number.");
                }
                return true;
            case "teamchat":
                handleTeamChat(p, args);
                return true;
            case "party":
                handleParty(p, args);
                return true;
            case "elo":
                int rating = plugin.getElo(p.getUniqueId());
                p.sendMessage("§eYour ELO: §a" + rating);
                return true;
            case "eloleader":
                plugin.sendLeaderboard(sender);
                return true;
            case "cbal":
                long coins = plugin.getCoins(p.getUniqueId());
                p.sendMessage("§eYour coins: §6" + coins);
                return true;
            case "cleader":
                plugin.sendCoinLeaderboard(sender);
                return true;
            case "coingive":
                handleCoinGive(p, args);
                return true;
            case "k":
                if (args.length == 0) {
                    openKitMenu(p);
                } else {
                    handleKitManagement(p, args);
                }
                return true;
        }
        return false;
    }

    private void handleBroadcast(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hoplite.broadcast")) {
            sender.sendMessage("§cYou do not have permission to broadcast.");
            return;
        }
        if (args.length != 2) {
            sender.sendMessage("§eUsage: /broadcast <link> <youtube|twitch>");
            return;
        }

        String platform = args[1].equalsIgnoreCase("youtube") ? "YouTube"
                : args[1].equalsIgnoreCase("twitch") ? "Twitch" : null;
        if (platform == null || !isPlatformLink(args[0], platform)) {
            sender.sendMessage("§cProvide a valid YouTube or Twitch link and matching platform.");
            return;
        }

        String message = configuredMessage("broadcast", "&6[Live] &e%player% IS LIVE ON %platform%: &b%link%",
                "%player%", sender.getName(), "%platform%", platform, "%link%", args[0]);
        Bukkit.broadcastMessage(message);
    }

    private boolean isPlatformLink(String link, String platform) {
        try {
            URI uri = new URI(link);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            if (platform.equals("YouTube")) {
                return host.equals("youtu.be") || host.equals("youtube.com")
                        || host.endsWith(".youtube.com");
            }
            return host.equals("twitch.tv") || host.endsWith(".twitch.tv");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private void handleParty(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("§eUsage: /party <create|invite|join|leave|disband|game|broadcast>");
            return;
        }

        UUID playerId = player.getUniqueId();
        switch (args[0].toLowerCase()) {
            case "create":
                if (!plugin.createParty(playerId)) {
                    player.sendMessage("§cYou are already in a party.");
                } else {
                    player.sendMessage("§aParty created. Use /party invite <player> to invite members.");
                }
                return;
            case "invite":
                if (args.length != 2 || !plugin.isPartyOwner(playerId)) {
                    player.sendMessage("§eOnly the party owner can use /party invite <player>.");
                    return;
                }
                Player invitee = Bukkit.getPlayer(args[1]);
                if (invitee == null) {
                    player.sendMessage("§cThat player is not online.");
                } else if (!plugin.invitePartyMember(playerId, invitee.getUniqueId())) {
                    player.sendMessage("§cThat player is already in a party, or the party is unavailable.");
                } else {
                    player.sendMessage("§aInvited " + invitee.getName() + " to your party.");
                    invitee.sendMessage("§e" + player.getName() + " invited you to a party. Use /party join "
                            + player.getName() + " to join.");
                }
                return;
            case "join":
                if (args.length != 2) {
                    player.sendMessage("§eUsage: /party join <owner>");
                    return;
                }
                Player owner = Bukkit.getPlayer(args[1]);
                if (owner == null || !plugin.joinParty(playerId, owner.getUniqueId())) {
                    player.sendMessage("§cYou do not have a pending invite from that online player.");
                } else {
                    player.sendMessage("§aYou joined " + owner.getName() + "'s party.");
                    owner.sendMessage("§a" + player.getName() + " joined your party.");
                }
                return;
            case "leave":
                UUID partyOwner = plugin.getPartyOwner(playerId);
                if (partyOwner == null) {
                    player.sendMessage("§cYou are not in a party.");
                } else if (partyOwner.equals(playerId)) {
                    player.sendMessage("§cYou own this party. Use /party disband instead.");
                } else {
                    plugin.leaveParty(playerId);
                    player.sendMessage("§eYou left the party.");
                    Player ownerPlayer = Bukkit.getPlayer(partyOwner);
                    if (ownerPlayer != null) ownerPlayer.sendMessage("§e" + player.getName() + " left your party.");
                }
                return;
            case "disband":
                if (!plugin.isPartyOwner(playerId)) {
                    player.sendMessage("§cOnly the party owner can disband the party.");
                    return;
                }
                List<UUID> disbanded = plugin.disbandParty(playerId);
                for (UUID member : disbanded) {
                    Player onlineMember = Bukkit.getPlayer(member);
                    if (onlineMember != null) onlineMember.sendMessage("§eThe party was disbanded.");
                }
                return;
            case "game":
                startPartyGame(player);
                return;
            case "broadcast":
                broadcastParty(player);
                return;
            default:
                player.sendMessage("§eUsage: /party <create|invite|join|leave|disband|game|broadcast>");
        }
    }

    private void startPartyGame(Player owner) {
        UUID ownerId = owner.getUniqueId();
        if (!plugin.isPartyOwner(ownerId)) {
            owner.sendMessage("§cOnly the party owner can start a party game.");
            return;
        }

        List<UUID> memberIds = plugin.getPartyMembers(ownerId);
        if (memberIds.size() < 2) {
            owner.sendMessage("§cInvite at least one player before starting a party game.");
            return;
        }

        List<Player> members = new ArrayList<>();
        for (UUID memberId : memberIds) {
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline()) {
                owner.sendMessage("§cAll party members must be online to start a game.");
                return;
            }
            boolean alreadyQueued = plugin.getWaitingQueue().contains(memberId)
                    || plugin.getWaitingQueueDuos().contains(memberId);
            boolean alreadyInLobby = plugin.getLobbies().stream().anyMatch(lobby ->
                    lobby.hasPlayer(memberId) || lobby.isParticipant(memberId));
            if (alreadyQueued || alreadyInLobby) {
                owner.sendMessage("§cAll party members must leave queues and matches first.");
                return;
            }
            members.add(member);
        }

        Lobby lobby = plugin.getLobbies().stream()
                .filter(candidate -> candidate.isAvailableForParty()
                        && candidate.getMaxPlayers() >= members.size())
                .findFirst().orElse(null);
        if (lobby == null || !lobby.startPartyMatch(members)) {
            owner.sendMessage("§cNo empty lobby can fit your party right now.");
        }
    }

    private void broadcastParty(Player player) {
        if (!player.hasPermission("hoplite.party.broadcast")) {
            player.sendMessage("§cYou do not have permission to broadcast your party.");
            return;
        }
        UUID ownerId = plugin.getPartyOwner(player.getUniqueId());
        if (ownerId == null) {
            player.sendMessage("§cYou are not in a party.");
            return;
        }

        List<String> names = new ArrayList<>();
        for (UUID member : plugin.getPartyMembers(player.getUniqueId())) {
            String name = Bukkit.getOfflinePlayer(member).getName();
            names.add(name == null ? member.toString() : name);
        }
        String ownerName = Bukkit.getOfflinePlayer(ownerId).getName();
        String message = configuredMessage("party-broadcast",
                "&d[Party] &e%owner%'s party members: &f%members%",
                "%owner%", ownerName == null ? ownerId.toString() : ownerName,
                "%members%", String.join(", ", names));
        Bukkit.broadcastMessage(message);
    }

    private String configuredMessage(String key, String fallback, String... replacements) {
        String message = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("messages." + key, fallback));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        return message;
    }

    private void handleQueue(Player p, String[] args) {
        boolean wantDuos = args != null && args.length > 0 && args[0].equalsIgnoreCase("duos");
        if (wantDuos) {
            handleQueueDuos(p);
            return;
        }
        handleQueueSingles(p);
    }

    private void handleQueueSingles(Player p) {
        UUID id = p.getUniqueId();
        // already in a lobby?
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(id) || l.isParticipant(id)) {
                p.sendMessage("§cYou are already in a lobby.");
                return;
            }
        }
        if (plugin.getWaitingQueue().contains(id)) {
            p.sendMessage("§cYou are already in the waiting queue.");
            return;
        }

        // Join the fullest available singles lobby.
        Lobby fullestLobby = plugin.getLobbies().stream()
                .filter(Lobby::isAvailableForSingles)
                .max(Comparator.comparingInt(Lobby::getPlayerCount))
                .orElse(null);
        if (fullestLobby != null) {
            if (fullestLobby.addPlayer(p)) {
                World lobbyWorld = Bukkit.getWorld(fullestLobby.getLobbyWorld());
                if (lobbyWorld == null) {
                    lobbyWorld = new org.bukkit.WorldCreator(fullestLobby.getLobbyWorld()).createWorld();
                }
                if (lobbyWorld != null) {
                    p.teleport(lobbyWorld.getSpawnLocation());
                }
                p.sendMessage("§aYou have joined lobby " + fullestLobby.getId() + ".");
                return;
            }
        }
        // all busy/full
        if (!plugin.getWaitingQueue().contains(id)) plugin.getWaitingQueue().add(id);
        p.sendMessage("§eAll lobbies are full or active. You joined the global queue.");
    }

    private void handleQueueDuos(Player p) {
        UUID id = p.getUniqueId();
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(id) || l.isParticipant(id)) {
                p.sendMessage("§cYou are already in a lobby.");
                return;
            }
        }
        if (plugin.getWaitingQueueDuos().contains(id)) {
            p.sendMessage("§cYou are already in the duos waiting queue.");
            return;
        }

        // Join the fullest available duos lobby.
        Lobby fullestLobby = plugin.getLobbies().stream()
                .filter(Lobby::isAvailableForDuos)
                .max(Comparator.comparingInt(Lobby::getPlayerCount))
                .orElse(null);
        if (fullestLobby != null) {
            if (fullestLobby.addPlayer(p)) {
                World lobbyWorld = Bukkit.getWorld(fullestLobby.getLobbyWorld());
                if (lobbyWorld == null) {
                    lobbyWorld = new org.bukkit.WorldCreator(fullestLobby.getLobbyWorld()).createWorld();
                }
                if (lobbyWorld != null) {
                    p.teleport(lobbyWorld.getSpawnLocation());
                }
                p.sendMessage("§aYou have joined duo lobby " + fullestLobby.getId() + ".");
                return;
            }
        }

        if (!plugin.getWaitingQueueDuos().contains(id)) plugin.getWaitingQueueDuos().add(id);
        p.sendMessage("§eAll duo lobbies are full or active. You joined the duo global queue.");
    }

    private void handleCoinGive(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage("§eUsage: /coingive <player> <amount>");
            return;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            p.sendMessage("§cPlayer not found.");
            return;
        }
        try {
            long amount = Long.parseLong(args[1]);
            if (amount <= 0) {
                p.sendMessage("§cAmount must be positive.");
                return;
            }
            if (!plugin.removeCoins(p.getUniqueId(), amount)) {
                p.sendMessage("§cYou don't have enough coins.");
                return;
            }
            plugin.addCoins(target.getUniqueId(), amount);
            p.sendMessage("§aGave " + amount + " coins to " + target.getName() + ".");
            target.sendMessage("§aReceived " + amount + " coins from " + p.getName() + ".");
        } catch (NumberFormatException e) {
            p.sendMessage("§cInvalid amount.");
        }
    }

    private void handleTeamChat(Player p, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("ingame")) {
            p.sendMessage("§eUsage: /teamchat ingame");
            return;
        }

        UUID playerId = p.getUniqueId();
        boolean inDuoGame = plugin.getLobbies().stream().anyMatch(lobby ->
                lobby.isActiveGame()
                        && "duos".equalsIgnoreCase(lobby.getMode())
                        && lobby.isParticipant(playerId));
        if (!inDuoGame) {
            p.sendMessage("§cTeam chat is only available during a duos match.");
            return;
        }

        boolean enabled = plugin.toggleTeamChat(playerId);
        p.sendMessage(enabled
                ? "§aTeam chat enabled. Use /teamchat ingame again to disable it."
                : "§eTeam chat disabled.");
    }

    private void openKitMenu(Player p) {
        plugin.refreshKitDefinitionsFromDatabase();
        // Check if player is in an active game
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(p.getUniqueId()) && l.isActiveGame()) {
                p.sendMessage("§cYou cannot use commands during a game.");
                return;
            }
        }
        
        Inventory inv = Bukkit.createInventory(null, 45, "§e§lKits Menu");
        for (int i = 0; i < KitData.KITS.size(); i++) {
            KitData.Kit kit = KitData.KITS.get(i);
            ItemStack is = new ItemStack(kit.icon);
            ItemMeta meta = is.getItemMeta();
            meta.setDisplayName("§6" + kit.name);
            java.util.List<String> lore = new java.util.ArrayList<>();
            if (plugin.ownsKit(p.getUniqueId(), kit.name)) {
                lore.add("§aOwned - left-click to load");
            } else {
                lore.add("§eCost: §6" + kit.price + " coins");
                lore.add("§7Left-click to buy permanently");
            }
            lore.add("§7Right-click to preview");
            meta.setLore(lore);
            is.setItemMeta(meta);
            inv.setItem(i, is);
        }
        p.openInventory(inv);
    }

    private void handleKitManagement(Player player, String[] args) {
        if (!player.hasPermission("hoplite.kit.admin")) {
            player.sendMessage("§cYou do not have permission to manage kits.");
            return;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            String name = args[1].trim();
            if (!name.matches("[A-Za-z0-9_-]+")) {
                player.sendMessage("§eKit names may contain letters, numbers, underscores, and hyphens.");
                return;
            }
            try {
                int price = Integer.parseInt(args[2]);
                List<ItemStack> kitItems = new ArrayList<>();
                for (ItemStack item : player.getInventory().getStorageContents()) {
                    if (item != null && item.getType() != Material.AIR) kitItems.add(item.clone());
                }
                ItemStack heldItem = player.getInventory().getItemInMainHand();
                Material icon = heldItem.getType() == Material.AIR ? Material.CHEST : heldItem.getType();
                if (kitItems.isEmpty()) {
                    player.sendMessage("§cPut the kit items in your inventory before creating it.");
                } else if (plugin.createCustomKit(name, price, kitItems.toArray(new ItemStack[0]), icon)) {
                    player.sendMessage("§aCreated kit " + name + " for " + price + " coins.");
                } else {
                    player.sendMessage("§cCould not create kit. Check the price or whether the name already exists.");
                }
            } catch (NumberFormatException exception) {
                player.sendMessage("§cKit price must be a whole number.");
            }
            return;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("edit")) {
            try {
                int price = Integer.parseInt(args[2]);
                if (plugin.setKitPrice(args[1], price)) {
                    player.sendMessage("§aUpdated " + args[1] + " to " + price + " coins.");
                } else {
                    player.sendMessage("§cKit not found or price is invalid.");
                }
            } catch (NumberFormatException exception) {
                player.sendMessage("§cKit price must be a whole number.");
            }
            return;
        }

        player.sendMessage("§eUsage: /k create <name> <coins> or /k edit <name> <new-price>");
    }

    private void handleLeaveQ(Player p) {
        UUID id = p.getUniqueId();
        boolean found = false;
        
        // Clear all tracked memberships, including stale duplicate queue entries.
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(id) || l.isParticipant(id)) {
                l.removePlayer(p);
                p.sendMessage("§cYou left the queue.");
                found = true;
            }
        }
        
        // Check both waiting queues in all cases
        if (plugin.getWaitingQueue().remove(id)) {
            p.sendMessage("§cYou left the global waiting queue.");
            found = true;
        }
        if (plugin.getWaitingQueueDuos().remove(id)) {
            p.sendMessage("§cYou left the duos waiting queue.");
            found = true;
        }
        
        if (!found) {
            p.sendMessage("§cYou are not in any queue.");
        } else {
            plugin.teleportToPlayerSpawn(p);
        }
    }

}

