package com.example.hoplite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ChestSpawner {
    public enum Rarity {
        COMMON, UNCOMMON, MYTHIC, LEGENDARY, GODLY, ENCHANTING
    }

    private static final Random RANDOM = new Random();

    // approximate distribution weights (as percentages)
    private static final int WEIGHT_UNCOMMON = 25;
    private static final int WEIGHT_MYTHIC = 15;
    private static final int WEIGHT_LEGENDARY = 10;
    private static final int WEIGHT_GODLY = 1;
    private static final int WEIGHT_ENCHANTING = 4;
    private static final int WEIGHT_COMMON = 100 - (WEIGHT_UNCOMMON + WEIGHT_MYTHIC + WEIGHT_LEGENDARY + WEIGHT_GODLY + WEIGHT_ENCHANTING);

    // spawn a fixed number of chests, distribution according to weights
    public static void populate(World world, int totalChests) {
        for (int i = 0; i < totalChests; i++) {
            Rarity r = pickRarity();
            spawnChest(world, r);
        }
    }

    private static Rarity pickRarity() {
        int roll = RANDOM.nextInt(100) + 1;
        if (roll <= WEIGHT_GODLY) return Rarity.GODLY;
        if (roll <= WEIGHT_GODLY + WEIGHT_LEGENDARY) return Rarity.LEGENDARY;
        if (roll <= WEIGHT_GODLY + WEIGHT_LEGENDARY + WEIGHT_MYTHIC) return Rarity.MYTHIC;
        if (roll <= WEIGHT_GODLY + WEIGHT_LEGENDARY + WEIGHT_MYTHIC + WEIGHT_ENCHANTING) return Rarity.ENCHANTING;
        if (roll <= WEIGHT_GODLY + WEIGHT_LEGENDARY + WEIGHT_MYTHIC + WEIGHT_ENCHANTING + WEIGHT_UNCOMMON) return Rarity.UNCOMMON;
        return Rarity.COMMON;
    }

    private static void spawnChest(World world, Rarity rarity) {
        int radius = (int) (world.getWorldBorder().getSize() / 2) - 2;
        int x = RANDOM.nextInt(radius * 2) - radius;
        int z = RANDOM.nextInt(radius * 2) - radius;
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location loc = new Location(world, x + 0.5, y, z + 0.5);
        Block b = world.getBlockAt(loc);
        b.setType(Material.CHEST);
        Chest chest = (Chest) b.getState();
        Inventory inv = chest.getBlockInventory();
        fillInventory(inv, rarity);
    }

    private static void fillInventory(Inventory inv, Rarity rarity) {
        List<ItemStack> contents = itemsForRarity(rarity);
        for (ItemStack item : contents) {
            inv.addItem(item);
        }
    }

    private static List<ItemStack> itemsForRarity(Rarity r) {
        List<ItemStack> items = new ArrayList<>();
        switch (r) {
            case COMMON:
                items.add(new ItemStack(Material.COBBLESTONE, randomBetween(8,16)));
                items.add(new ItemStack(Material.OAK_LOG, randomBetween(4,8)));
                items.add(new ItemStack(randomBetween(0,1)==0?Material.BREAD:Material.APPLE, randomBetween(1,3)));
                items.add(new ItemStack(Material.IRON_INGOT, randomBetween(1,2)));
                items.add(new ItemStack(Material.TORCH, randomBetween(1,2)));
                break;
            case UNCOMMON:
                items.add(new ItemStack(Material.IRON_NUGGET, randomBetween(4,8)));
                // pick either pick or sword
                items.add(new ItemStack(RANDOM.nextBoolean()?Material.IRON_PICKAXE:Material.IRON_SWORD, 1));
                items.add(new ItemStack(RANDOM.nextBoolean()?Material.COOKED_PORKCHOP:Material.COOKED_BEEF, randomBetween(4,6)));
                // random leather piece
                Material leather = new Material[]{Material.LEATHER_HELMET,Material.LEATHER_CHESTPLATE,Material.LEATHER_LEGGINGS,Material.LEATHER_BOOTS}[RANDOM.nextInt(4)];
                items.add(new ItemStack(leather, 1));
                items.add(new ItemStack(Material.GOLD_NUGGET, randomBetween(1,3)));
                break;
            case MYTHIC:
                items.add(new ItemStack(Material.DIAMOND_SWORD, 1));
                items.get(items.size()-1).addEnchantment(Enchantment.DAMAGE_ALL,1);
                items.add(new ItemStack(Material.BOW,1));
                items.get(items.size()-1).addEnchantment(Enchantment.ARROW_DAMAGE,1);
                items.add(new ItemStack(Material.ARROW,8));
                items.add(new ItemStack(Material.IRON_HORSE_ARMOR,1));
                items.add(new ItemStack(Material.GOLDEN_APPLE, randomBetween(4,6)));
                items.add(new ItemStack(Material.ENCHANTED_BOOK, randomBetween(1,2)));
                break;
            case LEGENDARY:
                items.add(new ItemStack(Material.DIAMOND_CHESTPLATE,1));
                items.get(items.size()-1).addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL,2);
                items.add(new ItemStack(Material.NETHERITE_SWORD,1));
                items.get(items.size()-1).addEnchantment(Enchantment.DAMAGE_ALL,2);
                items.add(new ItemStack(Material.ELYTRA,1));
                items.add(new ItemStack(Material.FIREWORK_ROCKET, randomBetween(1,5)));
                items.add(new ItemStack(Material.TOTEM_OF_UNDYING,1));
                items.add(new ItemStack(Material.ENCHANTED_BOOK,1));
                items.get(items.size()-1).addEnchantment(Enchantment.MENDING,1);
                break;
            case GODLY:
                items.add(new ItemStack(Material.NETHERITE_HELMET,1));
                items.get(items.size()-1).addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL,3);
                items.add(new ItemStack(Material.NETHERITE_CHESTPLATE,1));
                items.get(items.size()-1).addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL,3);
                items.add(new ItemStack(Material.NETHERITE_LEGGINGS,1));
                items.get(items.size()-1).addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL,3);
                items.add(new ItemStack(Material.NETHERITE_BOOTS,1));
                items.get(items.size()-1).addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL,3);
                ItemStack sword = new ItemStack(Material.NETHERITE_SWORD,1);
                sword.addEnchantment(Enchantment.DAMAGE_ALL,5);
                sword.addEnchantment(Enchantment.DURABILITY,3);
                sword.addEnchantment(Enchantment.MENDING,1);
                items.add(sword);
                items.add(new ItemStack(Material.BEACON,1));
                items.add(new ItemStack(Material.DRAGON_EGG,1));
                items.add(new ItemStack(Material.ENCHANTED_BOOK,1));
                items.get(items.size()-1).addEnchantment(Enchantment.ARROW_INFINITE,1);
                break;
            case ENCHANTING:
                items.add(new ItemStack(Material.LAPIS_LAZULI, randomBetween(15,20)));
                items.add(new ItemStack(Material.ENCHANTED_BOOK, randomBetween(1,2)));
                items.add(new ItemStack(Material.EXPERIENCE_BOTTLE,1));
                items.add(new ItemStack(Material.WRITTEN_BOOK,1)); // BOOK_AND_QUILL replaced by WRITTEN_BOOK
                items.add(new ItemStack(Material.ANVIL,1));
                break;
        }
        return items;
    }

    private static int randomBetween(int a, int b) {
        return a + RANDOM.nextInt(b - a + 1);
    }
}
