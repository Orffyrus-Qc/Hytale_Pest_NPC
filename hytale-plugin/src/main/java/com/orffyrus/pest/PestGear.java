package com.orffyrus.pest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Player-like loadout for Pest.
 *
 * Hotbar (teaching kit):
 *  0 stone-tier pick  — mine stone/ore
 *  1 stone axe        — chop wood / clear plants for fibre path
 *  2 stone sword      — fight when attacked / hostiles
 *  3 softwood planks  — base walls / roof
 *  4 crude door       — close the room
 *  5 crude bed        — place inside base + set spawn
 *
 * Storage: extra planks for a full room + fibre/hide backup.
 *
 * Hytale has no Tool_Pickaxe_Stone; Tool_Pickaxe_Crude is the early stone-tier pick.
 * Stone axe/sword: Weapon_Axe_Stone_Trork / Weapon_Sword_Stone_Trork.
 * Sleeping bag item: Furniture_Crude_Bed (Bedroll model, RespawnBlock + Bed use).
 * Base kit: Wood_Softwood_Planks + Furniture_Crude_Door.
 */
public final class PestGear {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final int SLOT_PICK = 0;
    public static final int SLOT_AXE = 1;
    public static final int SLOT_SWORD = 2;
    public static final int SLOT_WOOD = 3;
    public static final int SLOT_DOOR = 4;
    public static final int SLOT_BED = 5;

    public static final String ITEM_PICK = "Tool_Pickaxe_Crude";
    public static final String ITEM_AXE = "Weapon_Axe_Stone_Trork";
    public static final String ITEM_SWORD = "Weapon_Sword_Stone_Trork";
    public static final String ITEM_WOOD = "Wood_Softwood_Planks";
    public static final String ITEM_DOOR = "Furniture_Crude_Door";
    public static final String ITEM_BED = "Furniture_Crude_Bed";
    public static final String ITEM_FIBRE = "Ingredient_Fibre";
    public static final String ITEM_HIDE = "Ingredient_Hide_Light";

    /** Planks for 9×9 home (floor+walls 3 high+roof) ≈ 200+; give full stacks. */
    public static final int WOOD_STARTER_QTY = 128;

    /** Hotbar starter (null = leave empty). */
    public static final String[] STARTER_HOTBAR = {
            ITEM_PICK,
            ITEM_AXE,
            ITEM_SWORD,
            ITEM_WOOD,
            ITEM_DOOR,
            ITEM_BED
    };

    private PestGear() { }

    public static String equipStarter(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || store == null) {
            return "no entity";
        }
        try {
            LivingEntity living = store.getComponent(ref, NPCEntity.getComponentType());
            if (living == null) {
                return "not NPCEntity";
            }
            Inventory inv = living.getInventory();
            if (inv == null) {
                return "no inventory";
            }

            ItemContainer hotbar = inv.getHotbar();
            ItemContainer tools = inv.getTools();
            ItemContainer storage = inv.getStorage();
            int equipped = 0;

            if (hotbar != null) {
                for (int i = 0; i < STARTER_HOTBAR.length && i < hotbar.getCapacity(); i++) {
                    String id = STARTER_HOTBAR[i];
                    if (id == null) {
                        continue;
                    }
                    // Planks max stack 100 in assets; hotbar holds one stack for building
                    int qty = (i == SLOT_WOOD) ? 100 : 1;
                    if (i == SLOT_DOOR) {
                        qty = 1;
                    }
                    hotbar.setItemStackForSlot((short) i, item(id, qty));
                    equipped++;
                }
            }

            if (tools != null && tools.getCapacity() > 0) {
                tools.setItemStackForSlot((short) 0, item(ITEM_PICK, 1));
            }

            // Extra base mats: second plank stack for 9x9 home + bedcraft backup
            if (storage != null && storage.getCapacity() >= 4) {
                storage.setItemStackForSlot((short) 0, item(ITEM_FIBRE, 3));
                storage.setItemStackForSlot((short) 1, item(ITEM_HIDE, 2));
                storage.setItemStackForSlot((short) 2, item(ITEM_WOOD, 100));
                storage.setItemStackForSlot((short) 3, item(ITEM_DOOR, 2));
            }

            try {
                inv.setActiveHotbarSlot(ref, (byte) SLOT_WOOD, store);
            } catch (Exception e) {
                LOGGER.atFine().log("setActiveHotbarSlot: " + e);
            }

            PestEntityTracker.remember(ref, store, inv);
            String summary = "tools + " + WOOD_STARTER_QTY + " planks + door + bed (base kit)";
            LOGGER.atInfo().log("Pest gear equipped: " + equipped + " hotbar - " + summary);
            return summary;
        } catch (Exception e) {
            LOGGER.atWarning().log("PestGear.equipStarter failed: " + e);
            return "equip failed: " + e.getMessage();
        }
    }

    public static String describeInventory(Inventory inv) {
        if (inv == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendSection(sb, inv.getHotbar());
        appendSection(sb, inv.getTools());
        appendSection(sb, inv.getStorage());
        ItemStack hand = inv.getItemInHand();
        if (hand != null && !hand.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("hand=").append(hand.getItemId());
        }
        return sb.toString();
    }

    public static boolean invContains(String invSummary, String itemId) {
        return invSummary != null && invSummary.contains(itemId);
    }

    private static void appendSection(StringBuilder sb, ItemContainer c) {
        if (c == null) {
            return;
        }
        for (short i = 0; i < c.getCapacity(); i++) {
            ItemStack s = c.getItemStack(i);
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.getItemId());
            if (s.getQuantity() > 1) {
                sb.append('x').append(s.getQuantity());
            }
        }
    }

    private static ItemStack item(String id, int qty) {
        return new ItemStack(id, qty);
    }
}
