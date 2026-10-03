package com.epic60869.skyballs.features.dungeons;

import com.epic60869.skyballs.ItemPriceResolver;
import com.epic60869.skyballs.SkyBallsConfig;
import com.epic60869.skyballs.custom.RepoItems;
import com.epic60869.skyballs.custom.util.Compat;
import com.epic60869.skyballs.features.FeatureConfigs;
import com.epic60869.skyballs.mixin.SkyBallsContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon reward chest profit, like Skyblocker's chest value and Croesus profit: in a reward chest, its contents' value
 * minus its cost above the chest; in Croesus's menu for a run, each chest's profit with the best one highlighted; and
 * in Croesus's list of runs, each run tinted by whether its chests are still unopened, partly opened or all claimed.
 */
public final class DungeonChestProfit {
    private static final Pattern CHEST = Pattern.compile("^(?:Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$");
    private static final Pattern CHEST_HEAD = Pattern.compile("^(?:Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?");
    private static final Pattern CROESUS_RUN = Pattern.compile("^(?:Master )?Catacombs - Floor [IV]+$");
    private static final Pattern COUNT = Pattern.compile("^(?<name>.+?) x(?<count>[\\d,]+)$");
    private static final Pattern ESSENCE = Pattern.compile("^(?<type>\\w+) Essence$");
    private static final Pattern BOOK = Pattern.compile("^Enchanted Book \\((?<enchant>.+)\\)$");
    private static final Pattern ENCHANT = Pattern.compile("^(?<name>[A-Za-z' -]+?) (?<level>[IVX]+)$");
    private static final Pattern COINS = Pattern.compile("^(?<amount>[\\d,]+) Coins$");
    private static final long RECALC_MS = 500L;

    /** Item name -> SkyBlock id ("" when unknown), as name lookups go through every item. */
    private static final Map<String, String> IDS = new HashMap<>();

    /** One chest: what it holds is worth {@code value}, it costs {@code cost}; {@code opened} at Croesus. */
    private record Chest(double value, double cost, boolean opened) {
        double profit() {
            return value - cost;
        }
    }

    private static Map<Integer, Chest> croesusChests = Map.of();
    private static Chest openChest;
    private static long calculatedAt;

    private DungeonChestProfit() {}

    private static FeatureConfigs.ChestProfit config() {
        SkyBallsConfig config = SkyBallsConfig.current();
        return config == null ? null : config.dungeons.chestProfit;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ItemPriceResolver.warmup();
            calculatedAt = 0;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> render(container, g));
        });
    }

    private static String title(AbstractContainerScreen<?> screen) {
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return title == null ? "" : title.trim();
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        FeatureConfigs.ChestProfit config = config();
        if (config == null || !Compat.isOnSkyblock()) return;
        String title = title(screen);
        if (config.chest && CHEST.matcher(title).matches()) renderRewardChest(screen, g, config);
        else if (config.croesusChests && CROESUS_RUN.matcher(title).matches()) renderCroesusRun(screen, g, config);
        else if (config.croesusRuns && title.equals("Croesus")) renderCroesusRuns(screen, g);
    }

    // ------------------------------------------------------------------------------------------------ reward chest

    private static void renderRewardChest(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, FeatureConfigs.ChestProfit config) {
        long now = System.currentTimeMillis();
        if (now - calculatedAt > RECALC_MS) {
            calculatedAt = now;
            openChest = rewardChest(screen, config);
        }
        if (openChest == null) return;
        SkyBallsContainerScreenAccessor accessor = (SkyBallsContainerScreenAccessor) screen;
        int centre = accessor.skyballs$getLeftPos() + accessor.skyballs$getImageWidth() / 2;
        int y = accessor.skyballs$getTopPos() - 12;
        Component text = Component.literal("Profit: ").withStyle(ChatFormatting.GRAY).append(profitText(openChest.profit()))
            .append(Component.literal("  (worth " + coins(openChest.value()) + ", costs " + coins(openChest.cost()) + ")").withStyle(ChatFormatting.DARK_GRAY));
        g.centeredText(Minecraft.getInstance().font, text, centre, y, 0xFFFFFFFF);
    }

    /** The open reward chest: its items' value and the cost on its "Open Reward Chest" button; null while it's empty. */
    private static Chest rewardChest(AbstractContainerScreen<?> screen, FeatureConfigs.ChestProfit config) {
        double value = 0, cost = 0;
        boolean anything = false;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            List<String> lore = lore(stack);
            int costLine = indexOf(lore, "Cost");
            if (costLine >= 0) {
                cost = cost(lore, costLine);
                continue;
            }
            if (isFiller(stack)) continue;
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            if (name == null) continue;
            if (!config.includeEssence && ESSENCE.matcher(stripCount(name.trim())).matches()) continue;
            double itemValue = stackValue(stack, name.trim());
            if (itemValue > 0 || !name.isBlank()) anything = true;
            value += itemValue;
        }
        return anything ? new Chest(value, cost, false) : null;
    }

    private static boolean isFiller(ItemStack stack) {
        return stack.is(Items.ARROW)
            || stack.is(Items.BARRIER) || stack.is(Items.CHEST) || stack.getItem().toString().contains("glass_pane");
    }

    private static double stackValue(ItemStack stack, String name) {
        String id = Compat.neuName(stack);
        if (!id.isBlank()) {
            double value = ItemPriceResolver.value(id);
            if (value > 0) {
                Matcher count = COUNT.matcher(name);
                return value * (count.matches() ? parseInt(count.group("count")) : stack.getCount());
            }
        }
        return lineValue(name) * (COUNT.matcher(name).matches() ? 1 : stack.getCount());
    }

    // ------------------------------------------------------------------------------------------------ croesus

    private static void renderCroesusRun(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, FeatureConfigs.ChestProfit config) {
        long now = System.currentTimeMillis();
        if (now - calculatedAt > RECALC_MS) {
            calculatedAt = now;
            croesusChests = croesusChests(screen, config);
        }
        if (croesusChests.isEmpty()) return;
        int best = -1;
        double bestProfit = -Double.MAX_VALUE;
        for (Map.Entry<Integer, Chest> e : croesusChests.entrySet()) {
            if (!e.getValue().opened() && e.getValue().profit() > bestProfit) {
                bestProfit = e.getValue().profit();
                best = e.getKey();
            }
        }
        SkyBallsContainerScreenAccessor accessor = (SkyBallsContainerScreenAccessor) screen;
        int left = accessor.skyballs$getLeftPos(), top = accessor.skyballs$getTopPos();
        var font = Minecraft.getInstance().font;
        for (Map.Entry<Integer, Chest> e : croesusChests.entrySet()) {
            Slot slot = screen.getMenu().slots.get(e.getKey());
            int x = left + slot.x, y = top + slot.y;
            Chest chest = e.getValue();
            if (chest.opened()) g.fill(x, y, x + 16, y + 16, 0xA0202020);
            else if (e.getKey() == best) g.fill(x, y, x + 16, y + 16, bestProfit >= 0 ? 0x6000FF00 : 0x60FF0000);
            // The profit, small, along the bottom of the slot.
            Component text = chest.opened() ? Component.literal("Opened").withStyle(ChatFormatting.GRAY) : profitText(chest.profit());
            g.pose().pushMatrix();
            g.pose().translate(x + 8, y + 12);
            g.pose().scale(0.55f, 0.55f);
            g.centeredText(font, text, 0, 0, 0xFFFFFFFF);
            g.pose().popMatrix();
        }
    }

    /** Slot index -> chest, from the chest heads' lore ("Contents", the items, a blank line, "Cost", the cost). */
    private static Map<Integer, Chest> croesusChests(AbstractContainerScreen<?> screen, FeatureConfigs.ChestProfit config) {
        Map<Integer, Chest> chests = new HashMap<>();
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container instanceof Inventory || !slot.hasItem()) continue;
            String name = ChatFormatting.stripFormatting(slot.getItem().getHoverName().getString());
            if (name == null || !CHEST_HEAD.matcher(name.trim()).find()) continue;
            List<String> lore = lore(slot.getItem());
            int contents = indexOf(lore, "Contents");
            if (contents < 0) continue;
            double value = 0;
            for (int line = contents + 1; line < lore.size() && !lore.get(line).isBlank(); line++) {
                String item = lore.get(line).trim();
                if (!config.includeEssence && ESSENCE.matcher(stripCount(item)).matches()) continue;
                value += lineValue(item);
            }
            int costLine = indexOf(lore, "Cost");
            double cost = costLine < 0 ? 0 : cost(lore, costLine);
            boolean opened = lore.stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("already opened")
                || l.toLowerCase(Locale.ROOT).contains("already been opened"));
            chests.put(i, new Chest(value, cost, opened));
        }
        return chests;
    }

    /** Croesus's list of runs: green while no chest is opened, yellow when one is, red when none are left. */
    private static void renderCroesusRuns(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        SkyBallsContainerScreenAccessor accessor = (SkyBallsContainerScreenAccessor) screen;
        int left = accessor.skyballs$getLeftPos(), top = accessor.skyballs$getTopPos();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !slot.hasItem()) continue;
            String lore = String.join("\n", lore(slot.getItem())).toLowerCase(Locale.ROOT);
            int colour;
            if (lore.contains("no more chests to open")) colour = 0x70FF3030;
            else if (lore.contains("opened chest:")) colour = 0x70FFD000;
            else if (lore.contains("no chests opened yet") || lore.contains("chests expire in")) colour = 0x7000FF00;
            else continue;
            int x = left + slot.x, y = top + slot.y;
            g.fill(x, y, x + 16, y + 16, colour);
        }
    }

    // ------------------------------------------------------------------------------------------------ prices

    /** The cost under the "Cost" line: coins and/or a Dungeon Chest Key; "FREE" is 0. */
    private static double cost(List<String> lore, int costLine) {
        double cost = 0;
        for (int i = costLine + 1; i < lore.size() && !lore.get(i).isBlank(); i++) {
            String line = lore.get(i).trim();
            Matcher coins = COINS.matcher(line);
            if (coins.matches()) cost += parseInt(coins.group("amount"));
            else if (line.contains("Dungeon Chest Key")) cost += ItemPriceResolver.value("DUNGEON_CHEST_KEY");
        }
        return cost;
    }

    /** The value of one contents line: "Wither Essence x15", "Enchanted Book (Ultimate Wise I)", "Necron's Handle"... */
    private static double lineValue(String line) {
        int count = 1;
        Matcher m = COUNT.matcher(line);
        String name = line;
        if (m.matches()) {
            name = m.group("name").trim();
            count = parseInt(m.group("count"));
        }
        Matcher essence = ESSENCE.matcher(name);
        if (essence.matches()) return count * ItemPriceResolver.value("ESSENCE_" + essence.group("type").toUpperCase(Locale.ROOT));
        Matcher book = BOOK.matcher(name);
        if (book.matches()) return count * enchantValue(book.group("enchant"));
        String id = idFor(name);
        double value = id.isEmpty() ? 0 : ItemPriceResolver.value(id);
        if (value <= 0) value = enchantValue(name);
        if (value <= 0) value = ItemPriceResolver.valueByName(name);
        return count * value;
    }

    /** "Ultimate Wise I" -> ENCHANTMENT_ULTIMATE_WISE_1's price; ultimate enchants without "Ultimate" in the name too. */
    private static double enchantValue(String enchant) {
        Matcher m = ENCHANT.matcher(enchant.trim());
        if (!m.matches()) return 0;
        String name = m.group("name").replaceAll("[^A-Za-z ]", "").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        int level = roman(m.group("level"));
        double value = ItemPriceResolver.value("ENCHANTMENT_" + name + "_" + level);
        if (value <= 0 && !name.startsWith("ULTIMATE_")) value = ItemPriceResolver.value("ENCHANTMENT_ULTIMATE_" + name + "_" + level);
        return value;
    }

    private static String idFor(String name) {
        synchronized (IDS) {
            String cached = IDS.get(name);
            if (cached != null) return cached;
        }
        String found = "";
        for (String id : RepoItems.idsByName(name)) {
            if (ItemPriceResolver.value(id) > 0) {
                found = id;
                break;
            }
        }
        // Not cached until prices are in, or an item would stay at 0 for the session.
        if (!found.isEmpty() || !RepoItems.idsByName(name).isEmpty() && RepoItems.itemsLoaded()) {
            synchronized (IDS) {
                IDS.put(name, found);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static List<String> lore(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        for (Component line : stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()) {
            String text = ChatFormatting.stripFormatting(line.getString());
            lines.add(text == null ? "" : text);
        }
        return lines;
    }

    private static int indexOf(List<String> lore, String header) {
        for (int i = 0; i < lore.size(); i++) if (lore.get(i).trim().equals(header)) return i;
        return -1;
    }

    private static String stripCount(String name) {
        Matcher m = COUNT.matcher(name);
        return m.matches() ? m.group("name").trim() : name;
    }

    private static int parseInt(String number) {
        try {
            return Integer.parseInt(number.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int roman(String numeral) {
        int total = 0, previous = 0;
        for (int i = numeral.length() - 1; i >= 0; i--) {
            int value = switch (numeral.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    private static Component profitText(double profit) {
        ChatFormatting colour = Math.abs(profit) < 1000 ? ChatFormatting.DARK_GRAY : profit > 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
        return Component.literal((profit >= 0 ? "+" : "-") + coins(Math.abs(profit))).withStyle(colour);
    }

    private static String coins(double value) {
        if (value >= 1_000_000_000) return String.format(Locale.US, "%.2fB", value / 1_000_000_000);
        if (value >= 1_000_000) return String.format(Locale.US, "%.2fM", value / 1_000_000);
        if (value >= 1_000) return String.format(Locale.US, "%.1fk", value / 1_000);
        return String.format(Locale.US, "%.0f", value);
    }
}
