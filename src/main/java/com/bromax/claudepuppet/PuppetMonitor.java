package com.bromax.claudepuppet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One {@code /claudemonitor} session: a recap of what happened around the player over a few seconds, written as a
 * compact text report (and optionally a flipbook from the player's own camera) and delivered to Claude in one go.
 *
 * Samples every 3 seconds: position, health, food, held item, what the player looks at, and which mobs came and went
 * within 24 blocks. Between samples it records damage taken, kills, deaths, blocks broken/placed (aggregated), chat,
 * dimension changes and advancements as they happen.
 */
public class PuppetMonitor {
    private static final int SAMPLE_TICKS = 60;
    private static final double RANGE = 24;

    final int durationTicks;
    final boolean view;
    private final long startTick;
    private int elapsed = 0;
    private final List<String> timeline = new ArrayList<>();
    private Map<String, Integer> lastMobs = new TreeMap<>();
    private final Map<String, Integer> broken = new TreeMap<>();
    private final Map<String, Integer> placed = new TreeMap<>();
    private final Map<String, Integer> kills = new TreeMap<>();
    private float damageTaken = 0;
    boolean viewDone;
    String viewResult;

    public PuppetMonitor(int seconds, boolean view, long startTick) {
        this.durationTicks = seconds * 20;
        this.view = view;
        this.viewDone = !view;
        this.startTick = startTick;
    }

    private String stamp() {
        return String.format("%5.1fs ", this.elapsed / 20.0);
    }

    public void note(String line) {
        this.timeline.add(this.stamp() + line);
    }

    public void onDamage(float amount, String source) {
        this.damageTaken += amount;
        this.note("took " + Math.round(amount * 10) / 10.0 + " damage from " + source);
    }

    public void onKill(Entity victim) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).getPath();
        this.kills.merge(type, 1, Integer::sum);
        this.note("killed " + type);
    }

    public void onBreak(String block) {
        this.broken.merge(block, 1, Integer::sum);
    }

    public void onPlace(String block) {
        this.placed.merge(block, 1, Integer::sum);
    }

    /** Called every server tick; returns true when the time is up. **/
    public boolean tick(ServerPlayer player) {
        if (player != null && this.elapsed % SAMPLE_TICKS == 0) {
            this.sample(player);
        }
        this.elapsed++;
        return this.elapsed > this.durationTicks;
    }

    private void sample(ServerPlayer player) {
        StringBuilder line = new StringBuilder();
        line.append(String.format("@%.0f %.0f %.0f", player.getX(), player.getY(), player.getZ()))
                .append(" hp ").append(Math.round(player.getHealth())).append(" food ").append(player.getFoodData().getFoodLevel())
                .append(" hand ").append(itemId(player.getMainHandItem()));
        HitResult hit = player.pick(20, 1, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            line.append(" looking at ").append(BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(blockHit.getBlockPos()).getBlock()).getPath());
        }
        if (this.elapsed == 0) {
            line.append(" | ").append(player.level().dimension().location().getPath()).append(", ")
                    .append(player.level().getBiome(player.blockPosition()).unwrapKey().map(key -> key.location().getPath()).orElse("?"))
                    .append(", time ").append(player.level().getDayTime() % 24000)
                    .append(player.level().isThundering() ? ", thunder" : player.level().isRaining() ? ", rain" : "");
        }

        // Mobs within range: counts, arrivals/departures, and the closest hostile.
        Map<String, Integer> mobs = new TreeMap<>();
        Entity closestHostile = null;
        for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(RANGE),
                entity -> entity instanceof LivingEntity && entity.isAlive() && !(entity instanceof ServerPlayer))) {
            String type = entity instanceof PuppetEntity ? "claude's puppet" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
            mobs.merge(type, 1, Integer::sum);
            if (entity instanceof Enemy && (closestHostile == null || entity.distanceTo(player) < closestHostile.distanceTo(player))) {
                closestHostile = entity;
            }
        }
        if (this.elapsed == 0) {
            line.append(" | around: ").append(mobs.isEmpty() ? "nothing" : format(mobs));
        } else {
            Map<String, Integer> arrived = new LinkedHashMap<>();
            Map<String, Integer> left = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : mobs.entrySet()) {
                int change = entry.getValue() - this.lastMobs.getOrDefault(entry.getKey(), 0);
                if (change > 0) {
                    arrived.put(entry.getKey(), change);
                }
            }
            for (Map.Entry<String, Integer> entry : this.lastMobs.entrySet()) {
                int change = entry.getValue() - mobs.getOrDefault(entry.getKey(), 0);
                if (change > 0) {
                    left.put(entry.getKey(), change);
                }
            }
            if (!arrived.isEmpty()) {
                line.append(" | +").append(format(arrived));
            }
            if (!left.isEmpty()) {
                line.append(" | -").append(format(left));
            }
        }
        if (closestHostile != null) {
            line.append(" | nearest hostile ").append(BuiltInRegistries.ENTITY_TYPE.getKey(closestHostile.getType()).getPath())
                    .append(" ").append(Math.round(closestHostile.distanceTo(player))).append("m");
        }
        this.lastMobs = mobs;
        this.note(line.toString());
    }

    public String report(String playerName, int seconds, List<String> chatBefore, String viewNote) {
        StringBuilder report = new StringBuilder();
        report.append("MONITOR REPORT: ").append(playerName).append(", ").append(seconds).append("s\n");
        report.append("chat in the minute before: ").append(chatBefore.isEmpty() ? "none" : "").append("\n");
        for (String chat : chatBefore) {
            report.append("  ").append(chat).append("\n");
        }
        report.append("timeline (samples every 3s; + arrived, - left range of 24):\n");
        for (String entry : this.timeline) {
            report.append("  ").append(entry).append("\n");
        }
        report.append("totals: damage taken ").append(Math.round(this.damageTaken * 10) / 10.0)
                .append(" | kills ").append(this.kills.isEmpty() ? "none" : format(this.kills))
                .append(" | broke ").append(this.broken.isEmpty() ? "none" : format(this.broken))
                .append(" | placed ").append(this.placed.isEmpty() ? "none" : format(this.placed)).append("\n");
        if (viewNote != null) {
            report.append("view: ").append(viewNote).append("\n");
        }
        return report.toString();
    }

    private static String format(Map<String, Integer> counts) {
        StringBuilder builder = new StringBuilder();
        counts.forEach((key, count) -> builder.append(builder.isEmpty() ? "" : ", ").append(key).append(count > 1 ? " x" + count : ""));
        return builder.toString();
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
