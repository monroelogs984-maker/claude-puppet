package com.bromax.claudepuppet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Undo history for Claude's block changes, and named waypoints ("marks") saved with the world.
 */
public class PuppetWorldTools {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int UNDO_LIMIT = 20;
    private static final int UNDO_MAX_BLOCKS = 200_000;

    // ==================================================
    //                      Undo
    // ==================================================
    private record SavedBlock(BlockPos pos, BlockState state, CompoundTag blockEntity) {}

    /** One undoable change: the blocks as they were before it. **/
    public static class UndoEntry {
        final String label;
        final ResourceKey<Level> dimension;
        final List<SavedBlock> blocks = new ArrayList<>();

        UndoEntry(String label, ResourceKey<Level> dimension) {
            this.label = label;
            this.dimension = dimension;
        }
    }

    private final Deque<UndoEntry> undoStack = new ArrayDeque<>();

    /** Saves a box of blocks before a change; returns false (and saves nothing) if the box is too big. **/
    public boolean recordBox(ServerLevel level, String label, BlockPos from, BlockPos to) {
        int minX = Math.min(from.getX(), to.getX()), maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY()), maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ()), maxZ = Math.max(from.getZ(), to.getZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > UNDO_MAX_BLOCKS) {
            return false;
        }
        UndoEntry entry = new UndoEntry(label, level.dimension());
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            entry.blocks.add(new SavedBlock(pos.immutable(), level.getBlockState(pos), blockEntity != null ? blockEntity.saveWithFullMetadata(level.registryAccess()) : null));
        }
        this.push(entry);
        return true;
    }

    public void recordBlock(ServerLevel level, String label, BlockPos pos) {
        this.recordBox(level, label, pos, pos);
    }

    private void push(UndoEntry entry) {
        this.undoStack.push(entry);
        while (this.undoStack.size() > UNDO_LIMIT) {
            this.undoStack.removeLast();
        }
    }

    public int undoSize() {
        return this.undoStack.size();
    }

    /** Reverts the most recent change; returns a description, or null if there's nothing to undo. **/
    public String undo(MinecraftServer server) {
        UndoEntry entry = this.undoStack.poll();
        if (entry == null) {
            return null;
        }
        ServerLevel level = server.getLevel(entry.dimension);
        if (level == null) {
            return "skipped '" + entry.label + "' (dimension not loaded)";
        }
        int changed = 0;
        // First clear block entities that shouldn't be there, then restore states (without physics), then their data.
        for (SavedBlock saved : entry.blocks) {
            if (!level.getBlockState(saved.pos()).equals(saved.state()) || saved.blockEntity() != null) {
                level.removeBlockEntity(saved.pos());
                level.setBlock(saved.pos(), saved.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                changed++;
            }
        }
        for (SavedBlock saved : entry.blocks) {
            if (saved.blockEntity() != null) {
                BlockEntity blockEntity = level.getBlockEntity(saved.pos());
                if (blockEntity != null) {
                    blockEntity.loadWithComponents(saved.blockEntity(), level.registryAccess());
                    blockEntity.setChanged();
                }
            }
        }
        for (SavedBlock saved : entry.blocks) {
            level.blockUpdated(saved.pos(), saved.state().getBlock());
        }
        return "undid '" + entry.label + "' (" + changed + " block" + (changed == 1 ? "" : "s") + " restored)";
    }

    /**
     * Records what a fill/setblock/clone command is about to change. Coordinates may be absolute or ~relative to the
     * command position; ^local coordinates and commands inside /execute aren't parsed (returns a reason instead).
     */
    public String recordCommand(ServerLevel level, Vec3 origin, String command) {
        String[] parts = command.trim().split("\\s+");
        String name = parts[0].toLowerCase();
        try {
            switch (name) {
                case "fill" -> {
                    BlockPos a = pos(parts, 1, origin);
                    BlockPos b = pos(parts, 4, origin);
                    return this.recordBox(level, "/" + command, a, b) ? null : "region too big to undo";
                }
                case "setblock" -> {
                    this.recordBlock(level, "/" + command, pos(parts, 1, origin));
                    return null;
                }
                case "clone" -> {
                    BlockPos from = pos(parts, 1, origin);
                    BlockPos to = pos(parts, 4, origin);
                    BlockPos dest = pos(parts, 7, origin);
                    BlockPos size = new BlockPos(Math.abs(to.getX() - from.getX()), Math.abs(to.getY() - from.getY()), Math.abs(to.getZ() - from.getZ()));
                    return this.recordBox(level, "/" + command, dest, dest.offset(size)) ? null : "region too big to undo";
                }
                default -> {
                    return "not a block command";
                }
            }
        } catch (Exception e) {
            return "couldn't read the coordinates (" + e.getMessage() + ")";
        }
    }

    private static BlockPos pos(String[] parts, int index, Vec3 origin) {
        return BlockPos.containing(coord(parts[index], origin.x), coord(parts[index + 1], origin.y), coord(parts[index + 2], origin.z));
    }

    private static double coord(String text, double base) {
        if (text.startsWith("^")) {
            throw new IllegalArgumentException("^local coordinates");
        }
        if (text.startsWith("~")) {
            return base + (text.length() > 1 ? Double.parseDouble(text.substring(1)) : 0);
        }
        return Double.parseDouble(text);
    }


    // ==================================================
    //                     Marks
    // ==================================================
    public record Mark(String dimension, double x, double y, double z) {
        public Vec3 pos() {
            return new Vec3(this.x, this.y, this.z);
        }

        public String describe() {
            return String.format("%.0f %.0f %.0f (%s)", this.x, this.y, this.z, this.dimension.replace("minecraft:", ""));
        }
    }

    private final Map<String, Mark> marks = new LinkedHashMap<>();
    private Path marksFile;

    /** Loads the marks saved with this world (claudepuppet_marks.json in the world folder). **/
    public void loadMarks(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("claudepuppet_marks.json");
        if (file.equals(this.marksFile)) {
            return;
        }
        this.marksFile = file;
        this.marks.clear();
        try {
            if (Files.exists(file)) {
                JsonObject json = GSON.fromJson(Files.readString(file), JsonObject.class);
                for (String name : json.keySet()) {
                    JsonObject mark = json.getAsJsonObject(name);
                    this.marks.put(name, new Mark(mark.get("dimension").getAsString(), mark.get("x").getAsDouble(), mark.get("y").getAsDouble(), mark.get("z").getAsDouble()));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveMarks() {
        if (this.marksFile == null) {
            return;
        }
        JsonObject json = new JsonObject();
        this.marks.forEach((name, mark) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("dimension", mark.dimension());
            entry.addProperty("x", mark.x());
            entry.addProperty("y", mark.y());
            entry.addProperty("z", mark.z());
            json.add(name, entry);
        });
        try {
            Files.writeString(this.marksFile, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setMark(String name, ResourceKey<Level> dimension, Vec3 pos) {
        this.marks.put(name.toLowerCase(), new Mark(dimension.location().toString(), pos.x, pos.y, pos.z));
        this.saveMarks();
    }

    public boolean removeMark(String name) {
        boolean removed = this.marks.remove(name.toLowerCase()) != null;
        if (removed) {
            this.saveMarks();
        }
        return removed;
    }

    public Mark getMark(String name) {
        return this.marks.get(name.toLowerCase());
    }

    public Map<String, Mark> getMarks() {
        return this.marks;
    }

    public static ResourceKey<Level> dimensionKey(Mark mark) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(mark.dimension()));
    }
}
