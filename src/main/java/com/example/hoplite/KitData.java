package com.example.hoplite;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

public class KitData {
    public static class KitPotion {
        public final PotionEffectType type;
        public final int duration;
        public final int amplifier;
        public KitPotion(PotionEffectType type, int duration, int amplifier) {
            this.type = type;
            this.duration = duration;
            this.amplifier = amplifier;
        }
    }

    public static final List<Kit> KITS = new ArrayList<>();

    static {
        // 1. Brawler's Blitz (2200-2800)
        KITS.add(new Kit("Brawler's Blitz", 2500, Material.IRON_SWORD,
            item(Material.IRON_SWORD, 1, Enchantment.DAMAGE_ALL, 2),
            item(Material.SHIELD, 1),
            item(Material.CHAINMAIL_CHESTPLATE, 1),
            item(Material.ENDER_PEARL, 8)
        ));

        // 2. Archer's Fury (1900-2400)
        KITS.add(new Kit("Archer's Fury", 2150, Material.BOW,
            item(Material.BOW, 1, Enchantment.ARROW_DAMAGE, 3),
            item(Material.ARROW, 48),
            item(Material.LEATHER_HELMET, 1)
        ).addPotion(PotionEffectType.SPEED, 600, 1));

        // 3. Tank-Mode (2500-3200)
        KITS.add(new Kit("Tank-Mode", 2850, Material.DIAMOND_CHESTPLATE,
            item(Material.DIAMOND_HELMET, 1),
            item(Material.DIAMOND_CHESTPLATE, 1),
            item(Material.DIAMOND_LEGGINGS, 1),
            item(Material.DIAMOND_BOOTS, 1),
            item(Material.IRON_AXE, 1, Enchantment.DAMAGE_UNDEAD, 3),
            item(Material.GOLDEN_APPLE, 2)
        ));

        // 4. Assassin's Edge (2000-2600)
        KITS.add(new Kit("Assassin's Edge", 2300, Material.PURPLE_DYE,
            item(Material.IRON_SWORD, 1, Enchantment.DAMAGE_ALL, 3),
            item(Material.SNOWBALL, 16)
        ).addPotion(PotionEffectType.INVISIBILITY, 300, 1));

        // 5. Ranged & Ready (1800-2300)
        KITS.add(new Kit("Ranged & Ready", 2050, Material.CROSSBOW,
            item(Material.CROSSBOW, 1, Enchantment.QUICK_CHARGE, 1),
            item(Material.SPECTRAL_ARROW, 24),
            item(Material.LEATHER_BOOTS, 1)
        ).addPotion(PotionEffectType.HEAL, 0, 2));

        // 6. Hybrid Warrior (2300-3000)
        KITS.add(new Kit("Hybrid Warrior", 2650, Material.NETHERITE_SWORD,
            item(Material.NETHERITE_SWORD, 1, Enchantment.FIRE_ASPECT, 1),
            item(Material.SHIELD, 1),
            item(Material.ENDER_PEARL, 4)
        ).addPotion(PotionEffectType.INCREASE_DAMAGE, 900, 1));

        // 7. Miner's Fortune (1200-1500)
        KITS.add(new Kit("Miner's Fortune", 1350, Material.IRON_PICKAXE,
            item(Material.IRON_PICKAXE, 1, Enchantment.LOOT_BONUS_BLOCKS, 1),
            item(Material.COAL, 64),
            item(Material.TORCH, 32),
            item(Material.LAVA_BUCKET, 1)
        ));

        // 8. Farmhand's Bundle (1000-1300)
        KITS.add(new Kit("Farmhand's Bundle", 1150, Material.IRON_HOE,
            item(Material.IRON_HOE, 1),
            item(Material.WHEAT_SEEDS, 64),
            item(Material.CARROT, 32),
            item(Material.POTATO, 32),
            item(Material.BONE_MEAL, 4)
        ));

        // 9. Nether-Run (2000-2500)
        KITS.add(new Kit("Nether-Run", 2250, Material.DIAMOND_PICKAXE,
            item(Material.DIAMOND_PICKAXE, 1, Enchantment.DIG_SPEED, 2),
            item(Material.OBSIDIAN, 8),
            item(Material.FLINT_AND_STEEL, 1),
            item(Material.GOLD_NUGGET, 4)
        ));

        // 10. XP-Harvester (1800-2200)
        KITS.add(new Kit("XP-Harvester", 2000, Material.ENCHANTING_TABLE,
            item(Material.ENCHANTING_TABLE, 1),
            item(Material.LAPIS_LAZULI, 20),
            item(Material.EXPERIENCE_BOTTLE, 1)
        ));

        // 11. Mob-Slayer Grinder (2100-2600)
        KITS.add(new Kit("Mob-Slayer Grinder", 2350, Material.SPAWNER,
            item(Material.IRON_SWORD, 1, Enchantment.DAMAGE_UNDEAD, 3),
            item(Material.BOW, 1, Enchantment.ARROW_DAMAGE, 2),
            item(Material.ARROW, 32),
            item(Material.COOKED_BEEF, 8)
        ));

        // 12. End-Explorer (2500-3200)
        KITS.add(new Kit("End-Explorer", 2850, Material.ENDER_EYE,
            item(Material.ENDER_EYE, 2),
            item(Material.ENDER_PEARL, 4),
            item(Material.DIAMOND_PICKAXE, 1, Enchantment.SILK_TOUCH, 1),
            item(Material.END_STONE, 1)
        ));
    }

    private static ItemStack item(Material mat, int amount) {
        return new ItemStack(mat, amount);
    }

    private static ItemStack item(Material mat, int amount, Enchantment ench, int level) {
        ItemStack is = new ItemStack(mat, amount);
        is.addEnchantment(ench, level);
        return is;
    }

    public static class Kit {
        public final String name;
        public final int price;
        public final Material icon;
        public final ItemStack[] items;
        public final List<KitPotion> potions = new ArrayList<>();

        public Kit(String name, int price, Material icon, ItemStack... items) {
            this.name = name;
            this.price = price;
            this.icon = icon;
            this.items = items;
        }

        public Kit addPotion(PotionEffectType type, int duration, int amplifier) {
            potions.add(new KitPotion(type, duration, amplifier));
            return this;
        }

        public void grantToPlayer(Player p) {
            for (ItemStack item : items) {
                p.getInventory().addItem(item.clone());
            }
            for (KitPotion kp : potions) {
                p.addPotionEffect(new PotionEffect(kp.type, kp.duration, kp.amplifier, true, true));
            }
        }
    }
}
