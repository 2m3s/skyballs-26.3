package com.epic60869.skyballs.features.skills;

import com.epic60869.skyballs.SkyBallsConfig;
import com.epic60869.skyballs.features.FeatureConfigs;
import com.epic60869.skyballs.features.core.SkyBallsChat;
import com.epic60869.skyballs.features.core.SkyBallsHuds;
import com.epic60869.skyballs.features.core.SkyBallsLocation;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Skill Progress display (https://github.com/hannibal002/SkyHanni, LGPL-2.1): from the action bar's
 * "+12.3 Farming (1,234/50k)", the skill's level, a progress bar, percent and XP to the next level, XP per hour and the
 * time until you level up. The level is worked out from the XP the next level needs, which is different for every
 * level up to 60.
 */
public final class SkillProgress {
    private static final Pattern GAIN = Pattern.compile("\\+(?<gained>[\\d,.]+) (?<skill>[A-Z][a-z]+) \\((?<progress>[^)]+)\\)");
    /** XP to reach each level from the one before it: index 0 is level 1, up to level 60. */
    private static final long[] TO_NEXT = {50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500, 5000, 7500, 10000, 15000, 20000,
        30000, 50000, 75000, 100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000, 1200000,
        1300000, 1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000, 2500000,
        2600000, 2750000, 2900000, 3100000, 3400000, 3700000, 4000000, 4300000, 4600000, 4900000, 5200000, 5500000, 5800000,
        6100000, 6400000, 6700000, 7000000};
    /** XP gains further apart than this don't count as time spent getting XP. */
    private static final long IDLE_MS = 30_000L;
    private static final int BAR_LENGTH = 30;

    private static final class Skill {
        final String name;
        double current = -1, needed = -1, percent = -1;
        double gainedXp;
        long activeMs, lastGain;

        Skill(String name) {
            this.name = name;
        }

        /** Level being worked towards, or -1 when it can't be told (percent only, or maxed). */
        int nextLevel() {
            if (needed <= 0) return -1;
            for (int i = 0; i < TO_NEXT.length; i++) if (Math.abs(TO_NEXT[i] - needed) < 0.5) return i + 1;
            return -1;
        }

        double rate() {
            return activeMs < 10_000L ? 0 : gainedXp / (activeMs / 3_600_000d);
        }
    }

    private static final Map<String, Skill> SKILLS = new HashMap<>();
    private static Skill last;

    private SkillProgress() {}

    private static FeatureConfigs.SkillProgress config() {
        SkyBallsConfig config = SkyBallsConfig.current();
        return config == null ? null : config.skills;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> last = null);
        SkyBallsChat.onActionBar(message -> onActionBar(message.text()));
        SkyBallsHuds.setting("skill_progress", () -> config() != null && config().enabled);
        SkyBallsHuds.register("skill_progress", "Skill Progress", SkillProgress::visible, SkillProgress::lines,
            List.of(Component.literal("Farming 47 → 48").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                bar(0.457),
                Component.literal("45.70% ").withStyle(ChatFormatting.YELLOW).append(Component.literal("(1,234,567/2.7M)").withStyle(ChatFormatting.GRAY)),
                Component.literal("XP/h: ").withStyle(ChatFormatting.GRAY).append(Component.literal("1.52M").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("  Level in: ").withStyle(ChatFormatting.GRAY)).append(Component.literal("58m").withStyle(ChatFormatting.GREEN))),
            8, 300);
    }

    private static boolean visible() {
        FeatureConfigs.SkillProgress c = config();
        if (c == null || !c.enabled || last == null || !SkyBallsLocation.onSkyblock()) return false;
        return c.hideAfter <= 0 || System.currentTimeMillis() - last.lastGain < c.hideAfter * 1000L;
    }

    private static void onActionBar(String text) {
        FeatureConfigs.SkillProgress c = config();
        if (c == null || !c.enabled) return;
        Matcher m = GAIN.matcher(text);
        if (!m.find()) return;
        String name = m.group("skill");
        Skill skill = SKILLS.computeIfAbsent(name, Skill::new);
        long now = System.currentTimeMillis();
        double gained = number(m.group("gained"));
        if (skill.lastGain > 0 && now - skill.lastGain < IDLE_MS) skill.activeMs += now - skill.lastGain;
        skill.gainedXp += gained;
        skill.lastGain = now;
        String progress = m.group("progress").trim();
        if (progress.endsWith("%")) {
            skill.percent = number(progress.substring(0, progress.length() - 1));
            skill.current = skill.needed = -1;
        } else if (progress.contains("/")) {
            String[] parts = progress.split("/", 2);
            skill.current = number(parts[0]);
            skill.needed = number(parts[1]);
            skill.percent = skill.needed > 0 ? 100 * skill.current / skill.needed : -1;
        } else {
            // Maxed: just the total.
            skill.current = number(progress);
            skill.needed = skill.percent = -1;
        }
        last = skill;
    }

    /** "1,234.5", "50k", "3.1M" -> the number. */
    private static double number(String text) {
        String t = text.replace(",", "").trim();
        double scale = 1;
        if (t.endsWith("k") || t.endsWith("K")) scale = 1_000;
        else if (t.endsWith("m") || t.endsWith("M")) scale = 1_000_000;
        else if (t.endsWith("b") || t.endsWith("B")) scale = 1_000_000_000;
        if (scale > 1) t = t.substring(0, t.length() - 1);
        try {
            return Double.parseDouble(t) * scale;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<Component> lines() {
        FeatureConfigs.SkillProgress c = config();
        List<Component> lines = new ArrayList<>();
        Skill s = last;
        if (c == null || s == null) return lines;
        int next = s.nextLevel();
        MutableComponent title = Component.literal(s.name).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        if (next > 0) title.append(Component.literal(" " + (next - 1) + " → " + next).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        else if (s.percent < 0) title.append(Component.literal(" (Max)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        lines.add(title);
        if (s.percent >= 0) {
            if (c.progressBar) lines.add(bar(s.percent / 100));
            MutableComponent progress = Component.literal(String.format(Locale.US, "%.2f%% ", s.percent)).withStyle(ChatFormatting.YELLOW);
            if (s.needed > 0) progress.append(Component.literal("(" + whole(s.current) + "/" + compact(s.needed) + ")").withStyle(ChatFormatting.GRAY));
            lines.add(progress);
        } else if (s.current >= 0) {
            lines.add(Component.literal("Total XP: ").withStyle(ChatFormatting.GRAY).append(Component.literal(whole(s.current)).withStyle(ChatFormatting.YELLOW)));
        }
        if (c.rate) {
            double rate = s.rate();
            MutableComponent line = Component.literal("XP/h: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(rate > 0 ? compact(rate) : "...").withStyle(ChatFormatting.GOLD));
            if (rate > 0 && s.needed > 0) {
                long ms = (long) ((s.needed - s.current) / rate * 3_600_000d);
                line.append(Component.literal("  Level in: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(duration(ms)).withStyle(ChatFormatting.GREEN));
            }
            lines.add(line);
        }
        return lines;
    }

    private static Component bar(double fraction) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * BAR_LENGTH);
        return Component.literal("|".repeat(filled)).withStyle(ChatFormatting.GREEN)
            .append(Component.literal("|".repeat(BAR_LENGTH - filled)).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static String whole(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    private static String compact(double value) {
        if (value >= 1_000_000_000) return String.format(Locale.US, "%.2fB", value / 1_000_000_000);
        if (value >= 1_000_000) return String.format(Locale.US, "%.2fM", value / 1_000_000);
        if (value >= 1_000) return String.format(Locale.US, "%.1fk", value / 1_000);
        return String.format(Locale.US, "%.0f", value);
    }

    private static String duration(long ms) {
        long minutes = ms / 60_000L;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) return days + "d " + hours % 24 + "h";
        return hours > 0 ? hours + "h " + minutes % 60 + "m" : minutes + "m " + (ms / 1000) % 60 + "s";
    }
}
