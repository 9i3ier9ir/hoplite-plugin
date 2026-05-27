package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.block.Block;
import org.bukkit.Material;


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
        plugin.getWaitingQueueDuos().remove(p.getUniqueId());
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
                    // Buy kit
                    if (plugin.removeCoins(p.getUniqueId(), kit.price)) {
                        kit.grantToPlayer(p);
                        p.sendMessage("§aKit '" + kit.name + "' purchased!");
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

