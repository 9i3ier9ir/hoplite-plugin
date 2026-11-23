package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.block.Block;
import org.bukkit.Material;
import java.util.UUID;


public class PlayerListener implements Listener {
    private final HoplitePlugin plugin;

    public PlayerListener(HoplitePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // ensure player has an ELO entry
        plugin.getElo(p.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.disableTeamChat(p.getUniqueId());
        plugin.clearPendingKit(p.getUniqueId());
        for (Lobby l : plugin.getLobbies()) l.cancelBalloonDrop(p.getUniqueId());
        // Remove from any lobby
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(p.getUniqueId())) l.removePlayer(p);
        }
        // also remove from waiting queue if present
        plugin.getWaitingQueue().remove(p.getUniqueId());
        plugin.getWaitingQueueDuos().remove(p.getUniqueId());
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player player = e.getPlayer();
        for (Lobby lobby : plugin.getLobbies()) {
            if (lobby.departFromBalloon(player)) return;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        for (Lobby lobby : plugin.getLobbies()) {
            if (lobby.clearSlowFallingOnLanding(e.getPlayer(), e.getTo())) return;
        }
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player) || e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        Player player = (Player) e.getEntity();
        for (Lobby lobby : plugin.getLobbies()) {
            if (lobby.isProtectedFromFall(player.getUniqueId())) {
                e.setCancelled(true);
                player.setFallDistance(0);
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String[] commandParts = e.getMessage().trim().split("\\s+", 2);
        String rootCommand = commandParts[0].startsWith("/")
                ? commandParts[0].substring(1)
                : commandParts[0];
        rootCommand = rootCommand.toLowerCase(java.util.Locale.ROOT);

        String state = null;
        for (Lobby lobby : plugin.getLobbies()) {
            if (lobby.isActiveGame() && (lobby.hasPlayer(p.getUniqueId())
                    || lobby.isParticipant(p.getUniqueId()))) {
                state = "game";
                break;
            }
            if (!lobby.isActiveGame() && lobby.hasPlayer(p.getUniqueId())) {
                state = "lobby";
            }
        }
        if (state == null && (plugin.getWaitingQueue().contains(p.getUniqueId())
                || plugin.getWaitingQueueDuos().contains(p.getUniqueId()))) {
            state = "queue";
        }

        if (state != null && !plugin.isCommandAllowed(state, rootCommand)
                && !p.hasPermission("hoplite.command.bypass")) {
            e.setCancelled(true);
            p.sendMessage("§cYou cannot use that command in " + state + ".");
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        Player sender = e.getPlayer();
        UUID senderId = sender.getUniqueId();
        if (!plugin.isTeamChatEnabled(senderId)) return;

        for (Lobby lobby : plugin.getLobbies()) {
            if (!lobby.isActiveGame()
                    || !"duos".equalsIgnoreCase(lobby.getMode())
                    || !lobby.isParticipant(senderId)) continue;

            UUID teammate = lobby.getTeammate(senderId);
            e.getRecipients().removeIf(recipient ->
                    !recipient.getUniqueId().equals(senderId)
                            && (teammate == null || !recipient.getUniqueId().equals(teammate)));
            return;
        }
        plugin.disableTeamChat(senderId);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        for (Lobby lobby : plugin.getLobbies()) {
            if (!lobby.hasPlayer(p.getUniqueId())) continue;
            if (lobby.isActiveGame()) {
                lobby.playerDied(p.getUniqueId());
            } else {
                lobby.keepPlayerInQueue(p);
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Lobby activeLobby = null;
        for (Lobby lobby : plugin.getLobbies()) {
            if (lobby.isParticipant(p.getUniqueId()) && lobby.isActiveGame()) {
                activeLobby = lobby;
                plugin.getWaitingQueue().remove(p.getUniqueId());
                plugin.getWaitingQueueDuos().remove(p.getUniqueId());
                break;
            }
            if (!lobby.isActiveGame() && lobby.hasPlayer(p.getUniqueId())) {
                Location center = plugin.getLobbyCenter(lobby.getId());
                if (center != null) e.setRespawnLocation(center);
                p.setFallDistance(0);
                return;
            }
        }
        if (activeLobby != null) {
            Location spawn = plugin.getPlayerSpawn();
            if (spawn != null) e.setRespawnLocation(spawn);
            p.setFallDistance(0);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
        Player attacker = (Player) e.getDamager();
        Player victim = (Player) e.getEntity();
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(attacker.getUniqueId()) && l.hasPlayer(victim.getUniqueId())) {
                if (l.isGracePeriod()) {
                    e.setCancelled(true);
                    attacker.sendMessage("§eGrace period is active. No PvP until it ends.");
                    return;
                }
                if ("duos".equalsIgnoreCase(l.getMode())) {
                    java.util.UUID mate = l.getTeammate(attacker.getUniqueId());
                    if (mate != null && mate.equals(victim.getUniqueId())) {
                        e.setCancelled(true);
                        attacker.sendMessage("§cYou cannot hurt your teammate.");
                        return;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView().getTitle().equals("§e§lKits Menu")) {
            e.setCancelled(true);
            Player p = (Player) e.getWhoClicked();
            int slot = e.getRawSlot();
            if (slot >= 0 && slot < KitData.KITS.size()) {
                KitData.Kit kit = KitData.KITS.get(slot);
                if (e.isLeftClick()) {
                    if (plugin.ownsKit(p.getUniqueId(), kit.name)) {
                        plugin.loadKitForNextGame(p.getUniqueId(), kit.name);
                        p.sendMessage("§aKit loaded for next game: " + kit.name);
                        p.closeInventory();
                    } else if (plugin.purchaseKit(p.getUniqueId(), kit.name, kit.price)) {
                        p.sendMessage("§aPermanently purchased kit '" + kit.name + "'!");
                        p.closeInventory();
                    } else {
                        p.sendMessage("§cYou don't have enough coins. Need: " + kit.price);
                    }
                } else if (e.isRightClick()) {
                    // Preview kit
                    Inventory preview = Bukkit.createInventory(null, 27, "§6Preview: " + kit.name);
                    for (org.bukkit.inventory.ItemStack item : kit.items) {
                        preview.addItem(item.clone());
                    }
                    p.openInventory(preview);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick2(InventoryClickEvent e) {
        // Prevent editing preview/menu inventories — block ALL interactions
        String title = e.getView().getTitle();
        if (title.startsWith("§6Preview:") || title.equals("§e§lKits Menu")) {
            e.setCancelled(true);
            if (title.startsWith("§6Preview:")) {
                ((Player)e.getWhoClicked()).sendMessage("§cYou cannot modify the preview.");
            }
        }
    }
}

