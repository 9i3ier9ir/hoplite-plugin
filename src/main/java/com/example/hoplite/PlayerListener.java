package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;


public class PlayerListener implements Listener {
    private final HoplitePlugin plugin;

    public PlayerListener(HoplitePlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // ensure player has an ELO entry
        plugin.getElo(p.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // Remove from any lobby
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(p.getUniqueId())) l.removePlayer(p);
        }
        // also remove from waiting queue if present
        plugin.getWaitingQueue().remove(p.getUniqueId());
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        // If player is in any active game, block commands
        for (Lobby l : plugin.getLobbies()) {
            if (l.isActiveGame() && l.hasPlayer(p.getUniqueId())) {
                e.setCancelled(true);
                p.sendMessage("You cannot use commands.");
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(p.getUniqueId())) {
                // mark as dead/eliminated
                l.playerDied(p.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getView().getTitle().equals("Kits Menu")) {
            e.setCancelled(true);
            Player p = (Player) e.getWhoClicked();
            int slot = e.getRawSlot();
            if (slot >= 0 && slot < KitData.KITS.size()) {
                KitData.Kit kit = KitData.KITS.get(slot);
                if (plugin.removeCoins(p.getUniqueId(), kit.price)) {
                    kit.grantToPlayer(p);
                    p.sendMessage("Kit '" + kit.name + "' purchased!");
                    p.closeInventory();
                } else {
                    p.sendMessage("You don't have enough coins. Need: " + kit.price);
                }
            }
        }
    }
}
