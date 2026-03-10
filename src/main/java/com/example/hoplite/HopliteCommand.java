package com.example.hoplite;

import org.bukkit.Bukkit;
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

public class HopliteCommand implements CommandExecutor {
    private final HoplitePlugin plugin;

    public HopliteCommand(HoplitePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        Player p = (Player) sender;
        switch (cmd) {
            case "queue":
            case "q":
                handleQueue(p);
                return true;
            case "leaveq":
                handleLeaveQ(p);
                return true;
            case "elo":
                int rating = plugin.getElo(p.getUniqueId());
                p.sendMessage("Your ELO: " + rating);
                return true;
            case "eloleader":
                plugin.sendLeaderboard(sender);
                return true;
            case "cbal":
                long coins = plugin.getCoins(p.getUniqueId());
                p.sendMessage("Your coins: " + coins);
                return true;
            case "cleader":
                plugin.sendCoinLeaderboard(sender);
                return true;
            case "coingive":
                handleCoinGive(p, args);
                return true;
            case "k":
                openKitMenu(p);
                return true;
        }
        return false;
    }

    private void handleQueue(Player p) {
        UUID id = p.getUniqueId();
        // already in a lobby?
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(id)) {
                p.sendMessage("You are already in a lobby.");
                return;
            }
        }
        if (plugin.getWaitingQueue().contains(id)) {
            p.sendMessage("You are already in the waiting queue.");
            return;
        }

        // find available lobby
        for (Lobby l : plugin.getLobbies()) {
            if (!l.isActiveGame() && l.getPlayerCount() < l.getMaxPlayers()) {
                // teleport and add
                World lobbyWorld = Bukkit.getWorld(l.getLobbyWorld());
                if (lobbyWorld != null) {
                    p.teleport(lobbyWorld.getSpawnLocation());
                }
                l.addPlayer(p);
                p.sendMessage("You have joined lobby " + l.getId() + ".");
                return;
            }
        }
        // all busy/full
        plugin.getWaitingQueue().add(id);
        p.sendMessage("All lobbies are currently full or active. You've been placed in the global queue.");
    }

    private void handleCoinGive(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage("Usage: /coingive <player> <amount>");
            return;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            p.sendMessage("Player not found.");
            return;
        }
        try {
            long amount = Long.parseLong(args[1]);
            if (amount <= 0) {
                p.sendMessage("Amount must be positive.");
                return;
            }
            if (!plugin.removeCoins(p.getUniqueId(), amount)) {
                p.sendMessage("You don't have enough coins.");
                return;
            }
            plugin.addCoins(target.getUniqueId(), amount);
            p.sendMessage("Gave " + amount + " coins to " + target.getName());
            target.sendMessage("Received " + amount + " coins from " + p.getName());
        } catch (NumberFormatException e) {
            p.sendMessage("Invalid amount.");
        }
    }

    private void openKitMenu(Player p) {
        // Check if player is in an active game
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(p.getUniqueId()) && l.isActiveGame()) {
                p.sendMessage("You cannot use commands during a game.");
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
            lore.add("§eCost: §6" + kit.price + " coins");
            lore.add("§7Right-click to preview");
            meta.setLore(lore);
            is.setItemMeta(meta);
            inv.setItem(i, is);
        }
        p.openInventory(inv);
    }

    private void handleLeaveQ(Player p) {
        UUID id = p.getUniqueId();
        boolean found = false;
        
        // Check lobbies
        for (Lobby l : plugin.getLobbies()) {
            if (l.hasPlayer(id)) {
                l.removePlayer(p);
                p.sendMessage("§cYou left the queue.");
                found = true;
                break;
            }
        }
        
        // Check waiting queue
        if (!found && plugin.getWaitingQueue().remove(id)) {
            p.sendMessage("§cYou left the global waiting queue.");
            found = true;
        }
        
        if (!found) {
            p.sendMessage("§cYou are not in any queue.");
        }
    }

}

