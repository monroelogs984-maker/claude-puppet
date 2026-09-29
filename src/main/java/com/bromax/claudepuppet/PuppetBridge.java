package com.bromax.claudepuppet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server side of Claude Puppet (runs inside the singleplayer world's integrated server).
 *
 * <p>{@code /claudepuppet} (no cheats needed, singleplayer owner or op only) tunes Claude in and spawns (or finds)
 * Claude's Puppet next to the player; {@code /claudepuppet off} tunes out and removes it; {@code /claudepuppet here}
 * brings it to you.
 *
 * <p>Commands Claude appends to {@code claudepuppet/in.txt}, one per line:
 * <pre>
 * say &lt;text&gt;                chat as &lt;Claude&gt;
 * cmd &lt;command&gt;             run any command as the server (op level), positioned at the puppet; output is logged
 *
 * walkto &lt;x&gt; &lt;y&gt; &lt;z&gt; [speed]  pathfind there (speed 1 = walk, 1.5 = run)
 * follow [name] [dist]      keep following a player (default: the owner) or entity
 * attack &lt;name|type&gt;       hunt the nearest match until it dies (shoots if holding a bow and carrying arrows)
 * guard [name]              follow a player (default owner) and fight any hostile mob that comes near them
 * gather &lt;what&gt; [count]     find, walk to, mine and collect blocks: wood | stone | a block id | #tag (default 16)
 * stop                      stop whatever it's doing
 * tp &lt;x&gt; &lt;y&gt; &lt;z&gt;  |  here   teleport the puppet (here = next to the owner)
 * look &lt;x&gt; &lt;y&gt; &lt;z&gt;  |  lookat &lt;name|type&gt;
 *
 * break &lt;x&gt; &lt;y&gt; &lt;z&gt;       mine a block (takes real time for the held tool; drops go to the inventory)
 * place &lt;x&gt; &lt;y&gt; &lt;z&gt; [block]   place a block from the inventory (or the held block); with a block id and none carried it's conjured
 * use                       right-click with the held item (through a fake player named Claude)
 * useon &lt;x&gt; &lt;y&gt; &lt;z&gt;       right-click a block with the held item
 * eat                       eat the held food (heals the puppet)
 * shoot &lt;name|type&gt;        one bow shot at a target (needs a bow in hand and arrows in the inventory)
 *
 * select &lt;item&gt;             move an item from the inventory into the hand (hand item goes to the inventory)
 * stash                     put the hand item into the inventory
 * take &lt;item&gt; [count]       conjure items straight into the inventory
 * drop &lt;item|all&gt; [count]   drop items on the ground
 * giveto [player] &lt;item&gt; [count]   toss items to a player (default owner)
 * hold &lt;item&gt; [off]  |  equip &lt;item&gt;   conjure an item into a hand / armor slot
 * jump  |  swing  |  vulnerable on|off
 * </pre>
 */
public class PuppetBridge {
    public static final PuppetBridge INSTANCE = new PuppetBridge();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final TextColor CLAUDE_COLOR = TextColor.fromRgb(0xD97757);
    private static final GameProfile FAKE_PROFILE = new GameProfile(UUID.fromString("c1a0de00-7075-4e74-8000-000000c1a0de"), "Claude");
    private static final double REACH = 4.5D;

    /** Takes a puppet's-eye snapshot on the (singleplayer) client; set by PuppetClient. No-op on a dedicated server. **/
    public interface SnapshotRequester {
        void request(int entityId, Path path, int width, java.util.function.Consumer<String> done);
    }
    public static SnapshotRequester SNAPSHOT = (entityId, path, width, done) -> done.accept("FAIL snapshot: needs a singleplayer client");

    /** Takes a flipbook (several frames over time, stitched into one grid image); set by PuppetClient. **/
    public interface FlipbookRequester {
        void request(int entityId, Path path, int frames, int width, long intervalMs, java.util.function.Consumer<String> done);
    }
    public static FlipbookRequester FLIPBOOK = (entityId, path, frames, width, intervalMs, done) -> done.accept("FAIL watch: needs a singleplayer client");
    private static final long WATCH_INTERVAL_MS = 1500L;
    private static final int WATCH_FRAME_WIDTH = 256;

    private MinecraftServer server;
    private boolean active = false;
    private Path dir;
    private long readOffset = 0;
    private UUID ownerId;
    private UUID puppetId;
    private int ticks = 0;
    private int respawnTimer = -1;

    // Puppet orders:
    private enum Mode { IDLE, WALK, FOLLOW, ATTACK, GUARD, BREAK, GATHER, POSSESS }

    /** Claude driving the puppet directly (null when not possessed). **/
    private PuppetPossession possession;
    private final List<String> stepNotes = new java.util.ArrayList<>();
    /** Tank mode survives the puppet's death: the respawned body gets it back. **/
    private boolean tankWanted = false;
    private Mode mode = Mode.IDLE;
    private Vec3 walkTarget;
    private double walkSpeed = 1.0D;
    private String targetName;
    private double followDistance = 3;
    private UUID attackTarget;
    private int attackCooldown = 0;
    private int stuckTicks = 0;

    // Mining (break command and gather job):
    private BlockPos breakPos;
    private float breakProgress = 0;
    private int breakApproachTicks = 0;
    private Predicate<BlockState> gatherFilter;
    private String gatherName;
    private int gatherWanted = 0;
    private int gatherCount = 0;
    private final Set<BlockPos> gatherBlacklist = new HashSet<>();

    private final Map<UUID, Long> lastHurtEvent = new HashMap<>();

    /** How much Claude listens: off, low (default), normal or high. Read by Claude's watcher from effort.txt. **/
    private String effort = "low";
    private static final List<String> EFFORT_LEVELS = List.of("off", "low", "normal", "high");
    /** Undo history for Claude's block changes, and the world's named marks. **/
    private final PuppetWorldTools tools = new PuppetWorldTools();

    /** The running /claudemonitor recap, if any. **/
    private PuppetMonitor monitor;
    /** Chat from the last minute (game time, line), kept so a monitor report can include what was said just before. **/
    private final java.util.ArrayDeque<Map.Entry<Long, String>> recentChat = new java.util.ArrayDeque<>();

    /** True while fighting back on its own; when that fight ends it quietly resumes following. **/
    private boolean selfDefense = false;


    // ==================================================
    //                  /claudepuppet
    // ==================================================
    public void onRegisterCommands(RegisterCommandsEvent event) {
        java.util.function.Predicate<CommandSourceStack> allowed = source -> source.isPlayer() && (source.getServer().isSingleplayerOwner(source.getPlayer().getGameProfile()) || source.hasPermission(2));
        event.getDispatcher().register(Commands.literal("claudeundo")
                .requires(allowed)
                .executes(context -> this.undo(context.getSource(), 1))
                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 20))
                        .executes(context -> this.undo(context.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "count")))));
        event.getDispatcher().register(Commands.literal("claudemark")
                .requires(allowed)
                .then(Commands.literal("list").executes(context -> this.listMarks(context.getSource())))
                .then(Commands.literal("remove").then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(this.tools.getMarks().keySet(), builder))
                        .executes(context -> {
                            this.tools.loadMarks(context.getSource().getServer());
                            String name = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name");
                            boolean removed = this.tools.removeMark(name);
                            context.getSource().sendSystemMessage(Component.literal(removed ? "Removed mark '" + name + "'." : "No mark called '" + name + "'.").withStyle(ChatFormatting.GRAY));
                            if (removed) {
                                this.event("MARK removed " + name);
                            }
                            return removed ? Command.SINGLE_SUCCESS : 0;
                        })))
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            this.tools.loadMarks(player.getServer());
                            String name = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name");
                            this.tools.setMark(name, player.level().dimension(), player.position());
                            context.getSource().sendSystemMessage(Component.literal("Marked '" + name.toLowerCase() + "' at " + this.tools.getMark(name).describe() + ".").withStyle(ChatFormatting.GRAY));
                            this.event("MARK " + name.toLowerCase() + " = " + this.tools.getMark(name).describe());
                            return Command.SINGLE_SUCCESS;
                        })));
        event.getDispatcher().register(Commands.literal("claudemonitor")
                .requires(allowed)
                .executes(context -> this.startMonitor(context.getSource().getPlayer(), 30, false))
                .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(5, 120))
                        .executes(context -> this.startMonitor(context.getSource().getPlayer(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "seconds"), false))
                        .then(Commands.literal("text").executes(context -> this.startMonitor(context.getSource().getPlayer(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "seconds"), false)))
                        .then(Commands.literal("view").executes(context -> this.startMonitor(context.getSource().getPlayer(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "seconds"), true)))));
        event.getDispatcher().register(Commands.literal("claudepuppet")
                .requires(source -> source.isPlayer() && (source.getServer().isSingleplayerOwner(source.getPlayer().getGameProfile()) || source.hasPermission(2)))
                .executes(context -> this.activate(context.getSource().getPlayer()))
                .then(Commands.literal("off").executes(context -> this.deactivate(context.getSource().getPlayer(), true)))
                .then(Commands.literal("snapshot")
                        .executes(context -> this.snapshot(192, true))
                        .then(Commands.literal("low").executes(context -> this.snapshot(192, true)))
                        .then(Commands.literal("high").executes(context -> this.snapshot(640, true)))
                        .then(Commands.literal("full").executes(context -> this.snapshot(0, true)))
                        .then(Commands.argument("width", com.mojang.brigadier.arguments.IntegerArgumentType.integer(32, 4096))
                                .executes(context -> this.snapshot(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "width"), true))))
                .then(Commands.literal("possess")
                        .executes(context -> this.startPossession(10))
                        .then(Commands.argument("steps", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 30))
                                .executes(context -> this.startPossession(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "steps")))))
                .then(Commands.literal("release").executes(context -> {
                    this.releasePossession("released by " + context.getSource().getTextName());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("tank")
                        .executes(context -> this.setTank(context.getSource(), !this.tankWanted))
                        .then(Commands.literal("on").executes(context -> this.setTank(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> this.setTank(context.getSource(), false))))
                .then(Commands.literal("watch")
                        .executes(context -> this.watch(20, true))
                        .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(2, 60))
                                .executes(context -> this.watch(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "seconds"), true))))
                .then(Commands.literal("effort")
                        .executes(context -> {
                            context.getSource().sendSystemMessage(Component.literal("Claude's effort: " + this.effort + " (off, low, normal, high)").withStyle(ChatFormatting.GRAY));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("level", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(EFFORT_LEVELS, builder))
                                .executes(context -> this.setEffort(context.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(context, "level")))))
                .then(Commands.literal("here").executes(context -> {
                    PuppetEntity puppet = this.getPuppet();
                    if (puppet == null) {
                        return this.activate(context.getSource().getPlayer());
                    }
                    this.bringTo(puppet, context.getSource().getPlayer());
                    return Command.SINGLE_SUCCESS;
                })));
    }

    private static final Map<String, String> EFFORT_INFO = Map.of(
            "off", "Claude won't respond; the puppet keeps doing its job and looking after itself.",
            "low", "Claude only responds when chat mentions \"claude\" or starts with \"!\".",
            "normal", "Claude responds to all chat, deaths and finished or failed jobs.",
            "high", "Claude follows everything: damage, fights, command output.");

    private int setEffort(CommandSourceStack source, String level) {
        level = level.toLowerCase();
        if (!EFFORT_LEVELS.contains(level)) {
            source.sendSystemMessage(Component.literal("Unknown effort level; use off, low, normal or high.").withStyle(ChatFormatting.RED));
            return 0;
        }
        this.effort = level;
        this.writeEffort();
        source.sendSystemMessage(Component.literal("Claude's effort: " + level + ". " + EFFORT_INFO.get(level)).withStyle(ChatFormatting.GRAY));
        this.event("EFFORT " + level);
        return Command.SINGLE_SUCCESS;
    }

    /** Saves a small image of what the puppet sees to claudepuppet/view.png (logged as SNAPSHOT, which wakes Claude). **/
    private int snapshot(int width, boolean requestedByPlayer) {
        PuppetEntity puppet = this.getPuppet();
        if (!this.active || puppet == null) {
            this.event("FAIL snapshot: no puppet");
            return 0;
        }
        SNAPSHOT.request(puppet.getId(), this.dir.resolve("view.png"), width, result -> this.event(result + (requestedByPlayer ? " (requested by the player)" : "")));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * A flipbook: one 256-wide frame of the puppet's view every 1.5 seconds for the given time, delivered as one grid
     * image (claudepuppet/watch.png), so Claude sees the sequence for the cost of a single look.
     */
    private int watch(int seconds, boolean requestedByPlayer) {
        PuppetEntity puppet = this.getPuppet();
        if (!this.active || puppet == null) {
            this.event("FAIL watch: no puppet");
            return 0;
        }
        int frames = (int) Math.max(1, Math.round(seconds * 1000.0 / WATCH_INTERVAL_MS));
        FLIPBOOK.request(puppet.getId(), this.dir.resolve("watch.png"), frames, WATCH_FRAME_WIDTH, WATCH_INTERVAL_MS, result -> this.event(result + (requestedByPlayer ? " (requested by the player)" : "")));
        this.announce("Watching for " + seconds + "s...");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /claudemonitor: records what happens around the player for a while and hands Claude one compact report at the end
     * (claudepuppet/monitor.txt, plus monitor_view.png with "view": frames from the player's own camera every 3s).
     * Works without the puppet; it only sets up the bridge folder if Claude isn't tuned in yet.
     */
    private int startMonitor(ServerPlayer player, int seconds, boolean view) {
        if (this.monitor != null) {
            player.sendSystemMessage(Component.literal("Claude is already monitoring.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        if (!this.active || this.dir == null) {
            if (!this.setupBridge(player)) {
                return 0;
            }
        }
        this.ownerId = player.getUUID();
        PuppetMonitor session = new PuppetMonitor(seconds, view, player.level().getGameTime());
        this.monitor = session;
        if (view) {
            int frames = Math.max(1, seconds / 3);
            FLIPBOOK.request(player.getId(), this.dir.resolve("monitor_view.png"), frames, WATCH_FRAME_WIDTH, 3000L, result -> {
                session.viewResult = result;
                session.viewDone = true;
            });
        }
        player.sendSystemMessage(Component.literal("[Claude] Watching what happens for " + seconds + "s" + (view ? ", with your view" : "") + "...").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        return Command.SINGLE_SUCCESS;
    }

    private void tickMonitor() {
        if (this.monitor == null) {
            return;
        }
        ServerPlayer owner = this.getOwner();
        boolean timeUp = this.monitor.tick(owner);
        if (!timeUp || !this.monitor.viewDone) {
            return;
        }
        long now = this.server.overworld().getGameTime();
        List<String> chatBefore = new java.util.ArrayList<>();
        long start = now - this.monitor.durationTicks;
        for (Map.Entry<Long, String> chat : this.recentChat) {
            if (chat.getKey() < start) {
                chatBefore.add(chat.getValue());
            }
        }
        String viewNote = null;
        if (this.monitor.view) {
            viewNote = this.monitor.viewResult != null && this.monitor.viewResult.startsWith("SNAPSHOT") ? this.monitor.viewResult.substring(9) : String.valueOf(this.monitor.viewResult);
        }
        String report = this.monitor.report(owner != null ? owner.getName().getString() : "?", this.monitor.durationTicks / 20, chatBefore, viewNote);
        this.monitor = null;
        Path path = this.dir.resolve("monitor.txt");
        try {
            Files.writeString(path, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            this.event("ERROR writing monitor report: " + e);
            return;
        }
        this.event("MONITOR " + path + (viewNote != null ? " + monitor_view.png" : ""));
    }

    private int undo(CommandSourceStack source, int count) {
        MinecraftServer server = source.getServer();
        int done = 0;
        for (int i = 0; i < count; i++) {
            String result = this.tools.undo(server);
            if (result == null) {
                break;
            }
            done++;
            source.sendSystemMessage(Component.literal("[Claude] " + result).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            this.event("UNDO " + result);
        }
        if (done == 0) {
            source.sendSystemMessage(Component.literal("Nothing of Claude's to undo.").withStyle(ChatFormatting.GRAY));
        }
        return done;
    }

    private int listMarks(CommandSourceStack source) {
        this.tools.loadMarks(source.getServer());
        if (this.tools.getMarks().isEmpty()) {
            source.sendSystemMessage(Component.literal("No marks yet. /claudemark <name> saves where you stand.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        source.sendSystemMessage(Component.literal("Marks:").withStyle(ChatFormatting.GRAY));
        this.tools.getMarks().forEach((name, mark) -> source.sendSystemMessage(Component.literal("  " + name + ": " + mark.describe()).withStyle(ChatFormatting.GRAY)));
        return this.tools.getMarks().size();
    }

    /** A coordinate triple from args, or a mark name (in the puppet's dimension). **/
    private Vec3 targetFrom(String[] args, int index) {
        if (args.length > index && !args[index].matches("-?[0-9.~]+")) {
            this.tools.loadMarks(this.server);
            PuppetWorldTools.Mark mark = this.tools.getMark(args[index]);
            if (mark == null) {
                throw new IllegalArgumentException("no mark called '" + args[index] + "'");
            }
            return mark.pos();
        }
        return new Vec3(Double.parseDouble(args[index]), Double.parseDouble(args[index + 1]), Double.parseDouble(args[index + 2]));
    }

    private int setTank(CommandSourceStack source, boolean on) {
        PuppetEntity puppet = this.getPuppet();
        if (puppet == null) {
            if (source != null) {
                source.sendSystemMessage(Component.literal("No puppet right now.").withStyle(ChatFormatting.GRAY));
            }
            return 0;
        }
        this.tankWanted = on;
        puppet.setTank(on);
        String message = on ? "Tank mode ON: 1000 HP, endless Regeneration V, no hit deals more than 10." : "Tank mode off: back to 150 HP.";
        this.announce(message);
        this.event("TANK " + (on ? "on" : "off"));
        return Command.SINGLE_SUCCESS;
    }

    /** Called by the damage event: tank mode caps each hit at 10 (sources that bypass invulnerability, like /kill, aren't capped). **/
    public void onDamagePre(LivingDamageEvent.Pre event) {
        if (event.getEntity() instanceof PuppetEntity puppet && puppet.isTank()
                && !event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)
                && event.getNewDamage() > PuppetEntity.TANK_DAMAGE_CAP) {
            event.setNewDamage(PuppetEntity.TANK_DAMAGE_CAP);
        }
    }

    // ==================================================
    //                   Possession
    // ==================================================
    private int startPossession(int steps) {
        PuppetEntity puppet = this.getPuppet();
        if (!this.active || puppet == null) {
            this.event("FAIL possess: no puppet");
            return 0;
        }
        this.setIdle();
        this.possession = new PuppetPossession(steps, puppet);
        this.possession.start(puppet);
        this.mode = Mode.POSSESS;
        this.stepNotes.clear();
        this.announce("Claude has taken control (" + steps + " steps).");
        this.reportStep(puppet); // Step 0: the starting view.
        return Command.SINGLE_SUCCESS;
    }

    private void releasePossession(String reason) {
        if (this.possession == null) {
            return;
        }
        PuppetEntity puppet = this.getPuppet();
        if (puppet != null) {
            this.possession.end(puppet);
        }
        this.possession = null;
        this.event("POSSESS ended: " + reason);
        this.announce("Claude released control.");
        this.followOwner();
    }

    /** Snapshot + one-line report after each step (wakes Claude). **/
    private void reportStep(PuppetEntity puppet) {
        PuppetPossession session = this.possession;
        session.awaitingSnapshot = true;
        String crosshair = session.describeCrosshair(puppet);
        List<String> hostiles = new java.util.ArrayList<>();
        for (Entity entity : puppet.level().getEntities(puppet, puppet.getBoundingBox().inflate(16), entity -> entity instanceof Enemy && entity.isAlive())) {
            hostiles.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + " " + Math.round(entity.distanceTo(puppet)) + "m");
        }
        String line = "STEP " + session.stepsUsed + "/" + session.totalSteps
                + " | crosshair: " + crosshair
                + " | @" + String.format("%.1f %.1f %.1f", puppet.getX(), puppet.getY(), puppet.getZ()) + " yaw " + Math.round(session.yaw) + " pitch " + Math.round(session.pitch)
                + " | hp " + Math.round(puppet.getHealth()) + (puppet.onGround() ? "" : " airborne") + (puppet.isInWater() ? " in water" : "")
                + " | hand " + itemId(puppet.getMainHandItem())
                + " | hostiles: " + (hostiles.isEmpty() ? "none within 16" : String.join(", ", hostiles))
                + (this.stepNotes.isEmpty() ? "" : " | notes: " + String.join("; ", this.stepNotes));
        this.stepNotes.clear();
        boolean last = session.stepsUsed >= session.totalSteps;
        SNAPSHOT.request(puppet.getId(), this.dir.resolve("possess_view.png"), 256, result -> {
            this.event(line + " | view: " + (result.startsWith("SNAPSHOT") ? "possess_view.png" : result));
            session.awaitingSnapshot = false;
            if (last && this.possession == session) {
                this.server.execute(() -> this.releasePossession("out of steps"));
            }
        });
    }

    private void tickPossession(PuppetEntity puppet) {
        PuppetPossession session = this.possession;
        String result = session.tick(puppet, this);
        if (result != null && !result.isEmpty()) {
            this.stepNotes.add(result);
        } else if ("".equals(result)) {
            session.stepsUsed++;
            this.reportStep(puppet);
        }
        if (!session.stepRunning && !session.awaitingSnapshot && ++session.idleTicks > PuppetPossession.IDLE_RELEASE_TICKS) {
            this.releasePossession("no input from Claude for 60s");
        }
    }

    public void startPossessBreak(BlockPos pos) {
        this.breakPos = pos;
        this.breakProgress = 0;
        this.breakApproachTicks = 0;
    }

    /** Mining in the crosshair while possessed (no walking to it). Returns true when done or impossible. **/
    public boolean tickPossessBreak(PuppetEntity puppet) {
        if (this.breakPos == null) {
            return true;
        }
        if (puppet.getEyePosition().distanceTo(Vec3.atCenterOf(this.breakPos)) > REACH + 0.5D) {
            this.stepNotes.add("hit: block out of reach");
            this.breakPos = null;
            return true;
        }
        boolean done = this.tickBreak(puppet);
        if (done) {
            this.breakPos = null;
        }
        return done || this.breakPos == null;
    }

    public void possessUse(PuppetEntity puppet, BlockPos pos) {
        this.useItem(puppet, pos);
    }

    public void possessPlace(PuppetEntity puppet, BlockPos pos, String blockId) {
        this.place(puppet, pos, blockId);
    }

    private void writeEffort() {
        if (this.dir == null) {
            return;
        }
        try {
            Files.writeString(this.dir.resolve("effort.txt"), this.effort + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int activate(ServerPlayer player) {
        if (!this.setupBridge(player)) {
            return 0;
        }
        return this.finishActivate(player);
    }

    /** Points the bridge at this world's folder and owner (no puppet). **/
    private boolean setupBridge(ServerPlayer player) {
        this.server = player.getServer();
        this.ownerId = player.getUUID();
        this.dir = this.server.getServerDirectory().resolve("claudepuppet");
        try {
            Files.createDirectories(this.dir);
            Path in = this.dir.resolve("in.txt");
            if (!Files.exists(in)) {
                Files.createFile(in);
            }
            this.readOffset = Files.size(in); // Only new commands.
            Path effortFile = this.dir.resolve("effort.txt");
            if (Files.exists(effortFile)) {
                String saved = Files.readString(effortFile).trim().toLowerCase();
                if (EFFORT_LEVELS.contains(saved)) {
                    this.effort = saved;
                }
            }
            this.writeEffort();
        } catch (IOException e) {
            player.sendSystemMessage(Component.literal("Claude Puppet couldn't create " + this.dir + ": " + e).withStyle(ChatFormatting.RED));
            return false;
        }
        this.tools.loadMarks(this.server);
        this.active = true;
        return true;
    }

    private int finishActivate(ServerPlayer player) {
        // Find an existing puppet near the player, or spawn one.
        PuppetEntity puppet = this.getPuppet();
        if (puppet == null) {
            List<PuppetEntity> nearby = player.serverLevel().getEntitiesOfClass(PuppetEntity.class, player.getBoundingBox().inflate(128));
            puppet = nearby.isEmpty() ? null : nearby.get(0);
            for (int i = 1; i < nearby.size(); i++) {
                nearby.get(i).discard();
            }
        }
        if (puppet == null) {
            puppet = this.spawnPuppet(player);
        }
        this.puppetId = puppet != null ? puppet.getUUID() : null;
        this.tankWanted = puppet != null && puppet.isTank();
        this.followOwner();
        this.respawnTimer = -1;
        this.active = true;

        player.sendSystemMessage(Component.literal("Claude is tuned in (effort: " + this.effort + ", change with /claudepuppet effort). ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("(bridge folder: " + this.dir + ")").withStyle(ChatFormatting.DARK_GRAY)));
        this.event("ACTIVATED by " + player.getName().getString() + " in " + player.level().dimension().location() + " at " + fmt(player.position()));
        return Command.SINGLE_SUCCESS;
    }

    private PuppetEntity spawnPuppet(ServerPlayer player) {
        PuppetEntity puppet = ClaudePuppet.PUPPET.get().create(player.serverLevel());
        if (puppet != null) {
            this.bringTo(puppet, player);
            player.serverLevel().addFreshEntity(puppet);
        }
        return puppet;
    }

    private int deactivate(ServerPlayer player, boolean removePuppet) {
        PuppetEntity puppet = this.getPuppet();
        if (removePuppet && puppet != null) {
            puppet.discard();
        }
        this.active = false;
        this.setIdle();
        this.event("DEACTIVATED" + (player != null ? " by " + player.getName().getString() : " (world closed)"));
        if (player != null) {
            player.sendSystemMessage(Component.literal("Claude has tuned out.").withStyle(ChatFormatting.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    public void onServerStopping(ServerStoppingEvent event) {
        if (this.active) {
            this.deactivate(null, false);
        }
    }

    private void bringTo(PuppetEntity puppet, ServerPlayer player) {
        Vec3 side = Vec3.directionFromRotation(0, player.getYRot() + 90).scale(1.5);
        if (puppet.level() != player.level()) {
            puppet.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(player.serverLevel(), player.position().add(side), Vec3.ZERO, player.getYRot(), 0, net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
        } else {
            puppet.moveTo(player.getX() + side.x, player.getY(), player.getZ() + side.z, player.getYRot(), 0);
        }
        puppet.getNavigation().stop();
    }


    // ==================================================
    //                 Chat and Events
    // ==================================================
    public void onChat(ServerChatEvent event) {
        long now = event.getPlayer().level().getGameTime();
        this.recentChat.addLast(Map.entry(now, "<" + event.getPlayer().getName().getString() + "> " + event.getRawText()));
        while (!this.recentChat.isEmpty() && now - this.recentChat.peekFirst().getKey() > 20 * 60 + (this.monitor != null ? this.monitor.durationTicks : 0)) {
            this.recentChat.pollFirst();
        }
        if (this.monitor != null) {
            this.monitor.note("chat <" + event.getPlayer().getName().getString() + "> " + event.getRawText());
        }
        if (this.active) {
            this.event("CHAT <" + event.getPlayer().getName().getString() + "> " + event.getRawText());
        }
    }

    /** A player or the puppet took damage (at most one event each per 5 seconds, so a fight doesn't flood the log). **/
    public void onPlayerHurt(LivingDamageEvent.Post event) {
        LivingEntity entity = event.getEntity();
        if (this.monitor != null && entity.getUUID().equals(this.ownerId) && event.getNewDamage() > 0) {
            Entity source = event.getSource().getEntity();
            this.monitor.onDamage(event.getNewDamage(), event.getSource().getMsgId() + (source != null ? " (" + BuiltInRegistries.ENTITY_TYPE.getKey(source.getType()).getPath() + ")" : ""));
        }
        if (!this.active || !(entity instanceof ServerPlayer || entity instanceof PuppetEntity) || event.getNewDamage() <= 0) {
            return;
        }
        // Self-defence: fight back against mobs (never players) when not already busy fighting.
        if (entity instanceof PuppetEntity && entity.getUUID().equals(this.puppetId) && entity.isAlive()
                && event.getSource().getEntity() instanceof LivingEntity attacker && !(attacker instanceof net.minecraft.world.entity.player.Player)
                && attacker.isAlive() && (this.mode == Mode.IDLE || this.mode == Mode.FOLLOW)) {
            this.setIdle();
            this.attackTarget = attacker.getUUID();
            this.mode = Mode.ATTACK;
            this.selfDefense = true;
        }
        long now = entity.level().getGameTime();
        Long last = this.lastHurtEvent.get(entity.getUUID());
        if (last != null && now - last < 100) {
            return;
        }
        this.lastHurtEvent.put(entity.getUUID(), now);
        Entity attacker = event.getSource().getEntity();
        String who = entity instanceof PuppetEntity ? "PUPPET" : entity.getName().getString();
        this.event("HURT " + who + " -" + Math.round(event.getNewDamage() * 10) / 10.0 + " from " + event.getSource().getMsgId()
                + (attacker != null ? " (" + BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()) + ")" : "") + ", health now " + Math.round(entity.getHealth() * 10) / 10.0);
    }

    public void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (this.monitor != null && event.getPlayer().getUUID().equals(this.ownerId)) {
            this.monitor.onBreak(BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).getPath());
        }
    }

    public void onBlockPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (this.monitor != null && event.getEntity() != null && event.getEntity().getUUID().equals(this.ownerId)) {
            this.monitor.onPlace(BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).getPath());
        }
    }

    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (this.monitor != null && event.getEntity().getUUID().equals(this.ownerId)) {
            this.monitor.note("went to " + event.getTo().location().getPath());
        }
        if (this.active) {
            this.event("DIMENSION " + event.getEntity().getName().getString() + " -> " + event.getTo().location());
        }
    }

    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (this.monitor != null && event.getAdvancement().value().display().isPresent()) {
            this.monitor.note("advancement: " + event.getAdvancement().value().display().get().getTitle().getString());
        }
        if (this.active && event.getAdvancement().value().display().isPresent()) {
            this.event("ADVANCEMENT " + event.getEntity().getName().getString() + ": " + event.getAdvancement().value().display().get().getTitle().getString());
        }
    }

    public void onLivingDeath(LivingDeathEvent event) {
        if (this.monitor != null) {
            Entity killer = event.getSource().getEntity();
            if (killer != null && killer.getUUID().equals(this.ownerId)) {
                this.monitor.onKill(event.getEntity());
            } else if (event.getEntity().getUUID().equals(this.ownerId)) {
                this.monitor.note("DIED: " + event.getSource().getLocalizedDeathMessage(event.getEntity()).getString());
            }
        }
        if (!this.active) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            this.event("DEATH " + event.getSource().getLocalizedDeathMessage(player).getString());
        } else if (event.getEntity() instanceof PuppetEntity puppet && puppet.getUUID().equals(this.puppetId) && this.respawnTimer <= 0) {
            this.event("PUPPET DIED: " + event.getSource().getLocalizedDeathMessage(puppet).getString() + " (respawning next to the owner in 10s)");
            this.setIdle();
            this.respawnTimer = 200;
        }
    }

    private void event(String line) {
        if (this.dir == null) {
            return;
        }
        try {
            Files.writeString(this.dir.resolve("events.log"), "[" + LocalTime.now().format(TIME) + "] " + line + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** A short automatic status line from the puppet itself (so Claude doesn't need to be woken to report it). **/
    private void announce(String text) {
        this.server.getPlayerList().broadcastSystemMessage(Component.literal("[Puppet] " + text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
    }

    /** A job ended: log it, tell the players, and go back to following the owner. **/
    private void finishTask(String eventLine, String announcement) {
        this.event(eventLine);
        if (announcement != null) {
            this.announce(announcement);
        }
        this.followOwner();
    }

    private void followOwner() {
        this.setIdle();
        this.targetName = null;
        this.followDistance = 3;
        this.mode = Mode.FOLLOW;
    }

    private void say(String text) {
        MutableComponent message = Component.literal("<").append(Component.literal("Claude").withStyle(style -> style.withColor(CLAUDE_COLOR))).append("> " + text);
        this.server.getPlayerList().broadcastSystemMessage(message, false);
    }


    // ==================================================
    //                      Tick
    // ==================================================
    public void onServerTick(ServerTickEvent.Post event) {
        if (!this.active || this.server == null) {
            return;
        }
        this.ticks++;

        // Respawn after death:
        if (this.respawnTimer > 0 && --this.respawnTimer == 0) {
            ServerPlayer owner = this.getOwner();
            if (owner != null) {
                UUID oldId = this.puppetId;
                PuppetEntity puppet = this.spawnPuppet(owner);
                this.puppetId = puppet != null ? puppet.getUUID() : null;
                this.removeStrayPuppets(oldId);
                this.followOwner();
                if (puppet != null && this.tankWanted) {
                    puppet.setTank(true);
                }
                this.event("PUPPET RESPAWNED next to " + owner.getName().getString());
            } else {
                this.respawnTimer = 100;
            }
        }

        if (this.ticks % 5 == 0) {
            this.readCommands();
        }
        try {
            this.tickMonitor();
        } catch (Exception e) {
            this.event("ERROR monitor stopped: " + e);
            this.monitor = null;
        }
        if (this.ticks % 100 == 0 && this.respawnTimer <= 0) {
            this.removeStrayPuppets(null);
        }
        // Never let a puppet job or another mod's item/block take the world down: log it and stand down instead.
        try {
            this.tickPuppet();
        } catch (Exception e) {
            this.event("ERROR puppet job crashed and was stopped: " + e);
            e.printStackTrace();
            this.followOwner();
        }
        if (this.ticks % 20 == 0) {
            try {
                this.writeState();
            } catch (Exception e) {
                this.event("ERROR writing state: " + e);
            }
        }
    }

    private void readCommands() {
        Path in = this.dir.resolve("in.txt");
        try {
            long size = Files.size(in);
            if (size < this.readOffset) {
                this.readOffset = 0;
            }
            if (size == this.readOffset) {
                return;
            }
            String text;
            try (RandomAccessFile file = new RandomAccessFile(in.toFile(), "r")) {
                file.seek(this.readOffset);
                byte[] bytes = new byte[(int) (size - this.readOffset)];
                file.readFully(bytes);
                text = new String(bytes, StandardCharsets.UTF_8);
            }
            int lastNewline = text.lastIndexOf('\n');
            if (lastNewline < 0) {
                return;
            }
            this.readOffset += text.substring(0, lastNewline + 1).getBytes(StandardCharsets.UTF_8).length;
            for (String line : text.substring(0, lastNewline).split("\n")) {
                line = line.trim();
                if (!line.isEmpty()) {
                    this.runCommand(line);
                }
            }
        } catch (IOException e) {
            this.event("ERROR reading in.txt: " + e);
        }
    }


    // ==================================================
    //                    Commands
    // ==================================================
    private void runCommand(String line) {
        String[] parts = line.split(" ", 2);
        String command = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].trim() : "";
        String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        PuppetEntity puppet = this.getPuppet();
        ServerPlayer owner = this.getOwner();
        try {
            switch (command) {
                case "say" -> this.say(rest);
                case "cmd" -> {
                    this.runServerCommand(rest.startsWith("/") ? rest.substring(1) : rest, puppet, owner);
                    return; // Output is logged as CMDOUT.
                }
                case "walkto" -> {
                    this.requirePuppet(puppet);
                    this.setIdle();
                    boolean byMark = !args[0].matches("-?[0-9.~]+");
                    this.walkTarget = this.targetFrom(args, 0);
                    this.walkSpeed = args.length > (byMark ? 1 : 3) ? Double.parseDouble(args[byMark ? 1 : 3]) : 1.0D;
                    this.mode = Mode.WALK;
                    puppet.getNavigation().moveTo(this.walkTarget.x, this.walkTarget.y, this.walkTarget.z, this.walkSpeed);
                }
                case "follow" -> {
                    this.requirePuppet(puppet);
                    this.setIdle();
                    this.targetName = args.length > 0 ? args[0] : null;
                    this.followDistance = args.length > 1 ? Double.parseDouble(args[1]) : 3;
                    this.mode = Mode.FOLLOW;
                }
                case "guard" -> {
                    this.requirePuppet(puppet);
                    this.setIdle();
                    this.targetName = args.length > 0 ? args[0] : null;
                    this.followDistance = 4;
                    this.mode = Mode.GUARD;
                }
                case "attack" -> {
                    this.requirePuppet(puppet);
                    Entity target = this.findEntity(puppet, args[0], 48);
                    if (target == null) {
                        this.event("FAIL attack: no '" + args[0] + "' within 48 blocks of the puppet");
                        return;
                    }
                    this.setIdle();
                    this.attackTarget = target.getUUID();
                    this.mode = Mode.ATTACK;
                    this.event("OK attack -> " + describe(target, puppet));
                    return;
                }
                case "gather" -> {
                    this.requirePuppet(puppet);
                    this.setIdle();
                    this.gatherFilter = this.blockFilter(args.length > 0 ? args[0] : "wood");
                    this.gatherName = args.length > 0 ? args[0] : "wood";
                    this.gatherWanted = args.length > 1 ? Integer.parseInt(args[1]) : 16;
                    this.gatherCount = 0;
                    this.gatherBlacklist.clear();
                    this.mode = Mode.GATHER;
                }
                case "stop" -> {
                    this.setIdle();
                    if (puppet != null) {
                        puppet.getNavigation().stop();
                    }
                }
                case "tp" -> {
                    this.requirePuppet(puppet);
                    Vec3 target = this.targetFrom(args, 0);
                    puppet.moveTo(target.x, target.y, target.z, puppet.getYRot(), 0);
                    puppet.getNavigation().stop();
                }
                case "here" -> {
                    this.requirePuppet(puppet);
                    if (owner == null) {
                        this.event("FAIL here: owner not online");
                        return;
                    }
                    this.bringTo(puppet, owner);
                }
                case "look" -> {
                    this.requirePuppet(puppet);
                    this.lookAt(puppet, new Vec3(Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2])));
                }
                case "lookat" -> {
                    this.requirePuppet(puppet);
                    Entity target = this.findEntity(puppet, args[0], 64);
                    if (target == null) {
                        this.event("FAIL lookat: no '" + args[0] + "' nearby");
                        return;
                    }
                    this.lookAt(puppet, target.getEyePosition());
                }

                // Blocks and items:
                case "break" -> {
                    this.requirePuppet(puppet);
                    this.setIdle();
                    this.breakPos = BlockPos.containing(Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2]));
                    this.mode = Mode.BREAK;
                }
                case "place" -> {
                    this.requirePuppet(puppet);
                    BlockPos pos = BlockPos.containing(Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2]));
                    this.place(puppet, pos, args.length > 3 ? args[3] : null);
                    return;
                }
                case "use" -> {
                    this.requirePuppet(puppet);
                    this.useItem(puppet, null);
                    return;
                }
                case "useon" -> {
                    this.requirePuppet(puppet);
                    this.useItem(puppet, BlockPos.containing(Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2])));
                    return;
                }
                case "eat" -> {
                    this.requirePuppet(puppet);
                    this.eat(puppet);
                    return;
                }
                case "shoot" -> {
                    this.requirePuppet(puppet);
                    Entity target = this.findEntity(puppet, args[0], 48);
                    if (target == null) {
                        this.event("FAIL shoot: no '" + args[0] + "' nearby");
                        return;
                    }
                    if (!this.shoot(puppet, target)) {
                        return;
                    }
                }

                // Inventory:
                case "select" -> {
                    this.requirePuppet(puppet);
                    Item item = this.item(args[0]);
                    ItemStack taken = puppet.takeFromInventory(item, item.getDefaultMaxStackSize());
                    if (taken.isEmpty()) {
                        this.event("FAIL select: no " + args[0] + " in the inventory");
                        return;
                    }
                    ItemStack previous = puppet.getMainHandItem();
                    puppet.setItemSlot(EquipmentSlot.MAINHAND, taken);
                    this.stashOrDrop(puppet, previous);
                }
                case "stash" -> {
                    this.requirePuppet(puppet);
                    ItemStack held = puppet.getMainHandItem();
                    puppet.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    this.stashOrDrop(puppet, held);
                }
                case "take" -> {
                    this.requirePuppet(puppet);
                    Item item = this.item(args[0]);
                    int count = args.length > 1 ? Integer.parseInt(args[1]) : 1;
                    while (count > 0) {
                        int amount = Math.min(count, item.getDefaultMaxStackSize());
                        this.stashOrDrop(puppet, new ItemStack(item, amount));
                        count -= amount;
                    }
                }
                case "drop" -> {
                    this.requirePuppet(puppet);
                    if ("all".equals(args[0])) {
                        for (ItemStack stack : puppet.getInventory().removeAllItems()) {
                            puppet.spawnAtLocation(stack, 1);
                        }
                    } else {
                        ItemStack taken = puppet.takeFromInventory(this.item(args[0]), args.length > 1 ? Integer.parseInt(args[1]) : 64);
                        if (taken.isEmpty()) {
                            this.event("FAIL drop: no " + args[0] + " in the inventory");
                            return;
                        }
                        puppet.spawnAtLocation(taken, 1);
                    }
                }
                case "giveto" -> {
                    this.requirePuppet(puppet);
                    boolean namedPlayer = args.length > 1 && !args[0].contains(":");
                    ServerPlayer receiver = namedPlayer ? this.server.getPlayerList().getPlayerByName(args[0]) : owner;
                    int itemArg = namedPlayer ? 1 : 0;
                    if (receiver == null) {
                        this.event("FAIL giveto: player not found");
                        return;
                    }
                    ItemStack taken = puppet.takeFromInventory(this.item(args[itemArg]), args.length > itemArg + 1 ? Integer.parseInt(args[itemArg + 1]) : 64);
                    if (taken.isEmpty()) {
                        this.event("FAIL giveto: no " + args[itemArg] + " in the inventory");
                        return;
                    }
                    this.toss(puppet, receiver, taken);
                }
                case "hold" -> {
                    this.requirePuppet(puppet);
                    ItemStack stack = this.itemStack(args[0]);
                    puppet.setItemSlot(args.length > 1 && args[1].startsWith("off") ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND, stack);
                }
                case "equip" -> {
                    this.requirePuppet(puppet);
                    ItemStack stack = this.itemStack(args[0]);
                    puppet.setItemSlot(puppet.getEquipmentSlotForItem(stack), stack);
                }
                case "possess" -> {
                    this.startPossession(args.length > 0 ? Math.max(1, Math.min(30, Integer.parseInt(args[0]))) : 10);
                    return;
                }
                case "release" -> {
                    this.releasePossession("released by Claude");
                    return;
                }
                case "step" -> {
                    if (this.possession == null || puppet == null) {
                        this.event("FAIL step: not possessing the puppet");
                        return;
                    }
                    if (this.possession.stepRunning || this.possession.awaitingSnapshot) {
                        this.event("FAIL step: previous step still running");
                        return;
                    }
                    this.possession.queueStep(rest);
                    return;
                }
                case "tank" -> {
                    this.setTank(null, args.length == 0 || "on".equalsIgnoreCase(args[0]));
                    return;
                }
                case "undo" -> {
                    int count = args.length > 0 ? Integer.parseInt(args[0]) : 1;
                    for (int i = 0; i < count; i++) {
                        String result = this.tools.undo(this.server);
                        if (result == null) {
                            this.event("FAIL undo: nothing left to undo");
                            break;
                        }
                        this.event("UNDO " + result);
                    }
                    return;
                }
                case "mark" -> {
                    this.tools.loadMarks(this.server);
                    Vec3 at = args.length >= 4 ? new Vec3(Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]))
                            : puppet != null ? puppet.position() : owner != null ? owner.position() : null;
                    Entity where = puppet != null ? puppet : owner;
                    if (at == null || where == null) {
                        this.event("FAIL mark: no position");
                        return;
                    }
                    this.tools.setMark(args[0], where.level().dimension(), at);
                    this.event("MARK " + args[0].toLowerCase() + " = " + this.tools.getMark(args[0]).describe());
                    return;
                }
                case "unmark" -> {
                    this.tools.loadMarks(this.server);
                    this.event(this.tools.removeMark(args[0]) ? "MARK removed " + args[0] : "FAIL unmark: no mark " + args[0]);
                    return;
                }
                case "watch" -> {
                    this.watch(args.length > 0 ? Integer.parseInt(args[0]) : 20, false);
                    return;
                }
                case "snapshot" -> {
                    String size = args.length > 0 ? args[0] : "low";
                    this.snapshot(switch (size) { case "low" -> 192; case "high" -> 640; case "full" -> 0; default -> Integer.parseInt(size); }, false);
                    return; // Result is logged as SNAPSHOT / FAIL.
                }
                case "jump" -> {
                    this.requirePuppet(puppet);
                    puppet.getJumpControl().jump();
                }
                case "swing" -> {
                    this.requirePuppet(puppet);
                    puppet.swing(InteractionHand.MAIN_HAND);
                }
                case "vulnerable" -> {
                    this.requirePuppet(puppet);
                    puppet.setInvulnerable(!"on".equalsIgnoreCase(args[0]));
                }
                default -> {
                    this.event("FAIL unknown command: " + line);
                    return;
                }
            }
            this.event("OK " + line);
        } catch (Exception e) {
            this.event("FAIL " + line + " (" + e + ")");
        }
    }

    private void requirePuppet(PuppetEntity puppet) {
        if (puppet == null) {
            throw new IllegalStateException(this.respawnTimer > 0 ? "the puppet is dead, respawning soon" : "no puppet (use /claudepuppet here)");
        }
    }

    private void setIdle() {
        if (this.possession != null) {
            PuppetEntity possessed = this.getPuppet();
            if (possessed != null) {
                this.possession.end(possessed);
            }
            this.possession = null;
            this.event("POSSESS ended: another order took over");
        }
        this.mode = Mode.IDLE;
        this.stuckTicks = 0;
        this.attackTarget = null;
        this.breakPos = null;
        this.breakProgress = 0;
        this.breakApproachTicks = 0;
        PuppetEntity puppet = this.getPuppet();
        if (puppet != null) {
            puppet.getNavigation().stop();
        }
    }

    private Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElseThrow(() -> new IllegalArgumentException("unknown item " + id));
    }

    private ItemStack itemStack(String id) {
        if ("air".equals(id) || "none".equals(id)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(this.item(id));
    }

    private void stashOrDrop(PuppetEntity puppet, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack leftover = puppet.addToInventory(stack);
        if (!leftover.isEmpty()) {
            puppet.spawnAtLocation(leftover, 1);
            this.event("INVENTORY FULL: dropped " + itemId(leftover));
        }
    }

    private void toss(PuppetEntity puppet, Entity receiver, ItemStack stack) {
        this.lookAt(puppet, receiver.getEyePosition());
        puppet.swing(InteractionHand.MAIN_HAND);
        ItemEntity itemEntity = new ItemEntity(puppet.level(), puppet.getX(), puppet.getEyeY() - 0.3, puppet.getZ(), stack);
        Vec3 toward = receiver.position().subtract(puppet.position());
        itemEntity.setDeltaMovement(toward.normalize().scale(Math.min(0.6, toward.length() * 0.12)).add(0, 0.25, 0));
        itemEntity.setPickUpDelay(10);
        itemEntity.setTarget(receiver.getUUID());
        puppet.level().addFreshEntity(itemEntity);
    }

    private void lookAt(PuppetEntity puppet, Vec3 pos) {
        puppet.lookAt(EntityAnchorArgument.Anchor.EYES, pos);
        puppet.getLookControl().setLookAt(pos.x, pos.y, pos.z, 90, 90);
    }

    /** Runs a command as the server at op level, positioned at the puppet (or the owner), logging all output. **/
    private void runServerCommand(String command, PuppetEntity puppet, ServerPlayer owner) {
        Entity anchor = puppet != null ? puppet : owner;
        ServerLevel level = anchor != null ? (ServerLevel) anchor.level() : this.server.overworld();
        Vec3 pos = anchor != null ? anchor.position() : Vec3.atCenterOf(level.getSharedSpawnPos());
        CommandSource output = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                PuppetBridge.this.event("CMDOUT " + component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = new CommandSourceStack(output, pos, Vec2.ZERO, level, 4, "Claude", Component.literal("Claude"), this.server, puppet);
        this.event("CMD /" + command);
        String first = command.trim().split("\\s+")[0].toLowerCase();
        if (first.equals("fill") || first.equals("setblock") || first.equals("clone")) {
            String problem = this.tools.recordCommand(level, pos, command);
            if (problem != null) {
                this.event("NOTE not undoable: " + problem);
            }
        } else if (first.equals("execute") && command.contains(" run ") && command.matches(".*\\brun (fill|setblock|clone)\\b.*")) {
            this.event("NOTE not undoable: block commands inside /execute aren't recorded");
        }
        this.server.getCommands().performPrefixedCommand(source, command);
    }


    // ==================================================
    //              Fake Player (item use)
    // ==================================================
    /** A fake player standing where the puppet is, looking where it looks, holding what it holds. **/
    private FakePlayer fakePlayer(PuppetEntity puppet) {
        FakePlayer fake = FakePlayerFactory.get((ServerLevel) puppet.level(), FAKE_PROFILE);
        fake.moveTo(puppet.getX(), puppet.getY(), puppet.getZ(), puppet.getYHeadRot(), puppet.getXRot());
        fake.setYHeadRot(puppet.getYHeadRot());
        fake.getInventory().clearContent();
        fake.setItemInHand(InteractionHand.MAIN_HAND, puppet.getMainHandItem().copy());
        fake.setItemInHand(InteractionHand.OFF_HAND, puppet.getOffhandItem().copy());
        return fake;
    }

    /** Copies the fake player's hands back to the puppet and moves anything it gained into the puppet's inventory. **/
    private void syncBack(PuppetEntity puppet, FakePlayer fake) {
        puppet.setItemSlot(EquipmentSlot.MAINHAND, fake.getMainHandItem().copy());
        puppet.setItemSlot(EquipmentSlot.OFFHAND, fake.getOffhandItem().copy());
        fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        fake.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        for (int i = 0; i < fake.getInventory().getContainerSize(); i++) {
            ItemStack stack = fake.getInventory().removeItemNoUpdate(i);
            this.stashOrDrop(puppet, stack);
        }
    }

    private void useItem(PuppetEntity puppet, BlockPos pos) {
        ItemStack held = puppet.getMainHandItem();
        if (pos != null) {
            this.lookAt(puppet, Vec3.atCenterOf(pos));
        }
        FakePlayer fake = this.fakePlayer(puppet);
        ServerLevel level = (ServerLevel) puppet.level();
        net.minecraft.world.InteractionResult result;
        if (pos != null) {
            Direction face = Direction.getNearest(puppet.getX() - (pos.getX() + 0.5), puppet.getEyeY() - (pos.getY() + 0.5), puppet.getZ() - (pos.getZ() + 0.5));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
            result = fake.gameMode.useItemOn(fake, level, fake.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        } else {
            result = fake.gameMode.useItem(fake, level, fake.getMainHandItem(), InteractionHand.MAIN_HAND);
            if (fake.isUsingItem()) {
                fake.releaseUsingItem(); // Bows and the like: fire immediately.
            }
        }
        puppet.swing(InteractionHand.MAIN_HAND);
        this.syncBack(puppet, fake);
        this.event("USE " + itemId(held) + (pos != null ? " on " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + " @ " + pos.toShortString() : "") + " -> " + result + ", now holding " + itemId(puppet.getMainHandItem()));
    }

    private void eat(PuppetEntity puppet) {
        ItemStack held = puppet.getMainHandItem();
        FoodProperties food = held.getFoodProperties(puppet);
        if (food == null) {
            this.event("FAIL eat: holding " + itemId(held) + ", which isn't food");
            return;
        }
        puppet.heal(food.nutrition() * 2.0F);
        puppet.level().playSound(null, puppet.getX(), puppet.getY(), puppet.getZ(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        held.shrink(1);
        this.event("ATE " + itemId(held.copyWithCount(1)) + ", health now " + Math.round(puppet.getHealth()));
    }

    private boolean shoot(PuppetEntity puppet, Entity target) {
        ItemStack bow = puppet.getMainHandItem();
        if (!(bow.getItem() instanceof BowItem)) {
            this.event("FAIL shoot: not holding a bow");
            return false;
        }
        ItemStack arrow = puppet.takeFromInventory(Items.ARROW, 1);
        if (arrow.isEmpty()) {
            this.event("FAIL shoot: no arrows in the inventory");
            return false;
        }
        this.lookAt(puppet, target.getEyePosition());
        AbstractArrow projectile = ProjectileUtil.getMobArrow(puppet, arrow, 1.0F, bow);
        double dx = target.getX() - puppet.getX();
        double dy = target.getY(0.3333D) - projectile.getY();
        double dz = target.getZ() - puppet.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        projectile.shoot(dx, dy + horizontal * 0.2D, dz, 1.6F, 2.0F);
        puppet.level().addFreshEntity(projectile);
        puppet.swing(InteractionHand.MAIN_HAND);
        puppet.level().playSound(null, puppet.getX(), puppet.getY(), puppet.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        return true;
    }

    private void place(PuppetEntity puppet, BlockPos pos, String blockId) {
        ServerLevel level = (ServerLevel) puppet.level();
        if (!level.getBlockState(pos).canBeReplaced()) {
            this.event("FAIL place: " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + " is already at " + pos.toShortString());
            return;
        }
        Block block = null;
        if (blockId != null) {
            block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(blockId)).orElseThrow(() -> new IllegalArgumentException("unknown block " + blockId));
            // Use one from the inventory if carried, otherwise conjure it.
            puppet.takeFromInventory(block.asItem(), 1);
        } else if (puppet.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
            block = blockItem.getBlock();
            puppet.getMainHandItem().shrink(1);
        }
        if (block == null) {
            this.event("FAIL place: no block given and not holding one");
            return;
        }
        this.lookAt(puppet, Vec3.atCenterOf(pos));
        puppet.swing(InteractionHand.MAIN_HAND);
        this.tools.recordBlock(level, "puppet placed " + BuiltInRegistries.BLOCK.getKey(block).getPath(), pos);
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        level.playSound(null, pos, block.defaultBlockState().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
        this.event("OK placed " + BuiltInRegistries.BLOCK.getKey(block) + " @ " + pos.toShortString());
    }


    // ==================================================
    //                     Mining
    // ==================================================
    private Predicate<BlockState> blockFilter(String what) {
        return switch (what) {
            case "wood", "log", "logs" -> state -> state.is(BlockTags.LOGS);
            case "stone" -> state -> state.is(BlockTags.BASE_STONE_OVERWORLD);
            case "ore", "ores" -> state -> state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
            case "dirt" -> state -> state.is(BlockTags.DIRT);
            case "sand" -> state -> state.is(BlockTags.SAND);
            default -> {
                if (what.startsWith("#")) {
                    TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(what.substring(1)));
                    yield state -> state.is(tag);
                }
                Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(what)).orElseThrow(() -> new IllegalArgumentException("unknown block " + what));
                yield state -> state.is(block);
            }
        };
    }

    /**
     * Advances mining of breakPos by one tick: walks into reach, then digs at the real speed for the held tool (via the
     * fake player), with the crack overlay. Returns true when the block is broken (drops go to the inventory).
     */
    private boolean tickBreak(PuppetEntity puppet) {
        ServerLevel level = (ServerLevel) puppet.level();
        BlockState state = level.getBlockState(this.breakPos);
        if (state.isAir()) {
            return true;
        }
        if (state.getDestroySpeed(level, this.breakPos) < 0) {
            this.event("FAIL break: " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " is unbreakable");
            this.gatherBlacklist.add(this.breakPos);
            this.breakPos = null;
            return false;
        }
        Vec3 center = Vec3.atCenterOf(this.breakPos);
        if (puppet.getEyePosition().distanceTo(center) > REACH) {
            if (++this.breakApproachTicks > 200) {
                this.event("STUCK break: can't reach " + this.breakPos.toShortString());
                this.gatherBlacklist.add(this.breakPos);
                level.destroyBlockProgress(puppet.getId(), this.breakPos, -1);
                this.breakPos = null;
                return false;
            }
            if (this.breakApproachTicks % 20 == 1 || puppet.getNavigation().isDone()) {
                puppet.getNavigation().moveTo(center.x, center.y, center.z, 1.2D);
            }
            return false;
        }
        puppet.getNavigation().stop();
        this.lookAt(puppet, center);
        FakePlayer fake = this.fakePlayer(puppet);
        this.breakProgress += state.getDestroyProgress(fake, level, this.breakPos);
        if (this.ticks % 5 == 0) {
            puppet.swing(InteractionHand.MAIN_HAND);
        }
        level.destroyBlockProgress(puppet.getId(), this.breakPos, Math.min(9, (int) (this.breakProgress * 10)));
        if (this.breakProgress < 1.0F) {
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return false;
        }

        // Broken: drops into the inventory, like a player walking over them.
        ItemStack tool = fake.getMainHandItem();
        BlockEntity blockEntity = level.getBlockEntity(this.breakPos);
        boolean canHarvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        if (canHarvest) {
            for (ItemStack drop : Block.getDrops(state, level, this.breakPos, blockEntity, puppet, tool)) {
                this.stashOrDrop(puppet, drop);
            }
        }
        tool.mineBlock(level, state, this.breakPos, fake);
        level.destroyBlockProgress(puppet.getId(), this.breakPos, -1);
        this.tools.recordBlock(level, "puppet broke " + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), this.breakPos);
        level.destroyBlock(this.breakPos, false, puppet);
        this.syncBack(puppet, fake);
        this.breakProgress = 0;
        this.breakApproachTicks = 0;
        return true;
    }

    /** Nearest matching block within 24 blocks of the puppet that touches air (so it can be reached), skipping the blacklist. **/
    private BlockPos findGatherTarget(PuppetEntity puppet) {
        ServerLevel level = (ServerLevel) puppet.level();
        BlockPos origin = puppet.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = 24;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -8; y <= 16; y++) {
                for (int z = -radius; z <= radius; z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    double distance = x * x + y * y * 2 + z * z;
                    if (distance >= bestDistance || !level.isLoaded(pos) || this.gatherBlacklist.contains(pos)) {
                        continue;
                    }
                    if (!this.gatherFilter.test(level.getBlockState(pos))) {
                        continue;
                    }
                    boolean exposed = false;
                    for (Direction direction : Direction.values()) {
                        if (level.getBlockState(pos.relative(direction)).isAir()) {
                            exposed = true;
                            break;
                        }
                    }
                    if (exposed) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }


    // ==================================================
    //                  Puppet Orders
    // ==================================================
    private void tickPuppet() {
        PuppetEntity puppet = this.getPuppet();
        if (puppet == null) {
            return;
        }
        // Eats from the backpack when below half health.
        if (this.ticks % 40 == 0 && puppet.getHealth() < puppet.getMaxHealth() / 2) {
            for (int i = 0; i < puppet.getInventory().getContainerSize(); i++) {
                ItemStack stack = puppet.getInventory().getItem(i);
                FoodProperties food = stack.getFoodProperties(puppet);
                if (food != null && food.nutrition() > 0) {
                    puppet.heal(food.nutrition() * 2.0F);
                    puppet.level().playSound(null, puppet.getX(), puppet.getY(), puppet.getZ(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
                    this.event("ATE " + itemId(stack.copyWithCount(1)) + " (auto), health now " + Math.round(puppet.getHealth()));
                    stack.shrink(1);
                    break;
                }
            }
        }
        if (this.mode == Mode.IDLE) {
            return;
        }
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }

        switch (this.mode) {
            case WALK -> {
                double distance = puppet.position().distanceTo(this.walkTarget);
                if (distance < 1.5D) {
                    this.event("DONE walkto, puppet at " + fmt(puppet.position()));
                    this.setIdle(); // Stays at the destination.
                } else if (puppet.getNavigation().isDone()) {
                    if (++this.stuckTicks > 40) {
                        this.finishTask("STUCK walkto: no path, puppet at " + fmt(puppet.position()) + ", " + Math.round(distance) + " blocks from target",
                                "Couldn't find a path there, coming back.");
                    } else if (this.stuckTicks % 10 == 0) {
                        puppet.getNavigation().moveTo(this.walkTarget.x, this.walkTarget.y, this.walkTarget.z, this.walkSpeed);
                    }
                }
            }
            case FOLLOW -> {
                Entity target = this.targetName == null ? this.getOwner() : this.findEntity(puppet, this.targetName, 96);
                this.followTick(puppet, target);
            }
            case GUARD -> {
                Entity guarded = this.targetName == null ? this.getOwner() : this.findEntity(puppet, this.targetName, 96);
                Entity threat = this.attackTarget != null ? ((ServerLevel) puppet.level()).getEntity(this.attackTarget) : null;
                if (threat == null || !threat.isAlive()) {
                    this.attackTarget = null;
                    if (guarded != null && this.ticks % 10 == 0) {
                        List<Entity> threats = guarded.level().getEntities(guarded, guarded.getBoundingBox().inflate(12),
                                entity -> entity instanceof Enemy && entity instanceof LivingEntity && entity.isAlive() && !(entity instanceof PuppetEntity));
                        threats.stream().min(Comparator.comparingDouble(entity -> entity.distanceTo(guarded))).ifPresent(found -> {
                            this.attackTarget = found.getUUID();
                            this.event("GUARD engaging " + describe(found, puppet));
                        });
                    }
                    if (this.attackTarget == null) {
                        this.followTick(puppet, guarded);
                    }
                } else {
                    this.fightTick(puppet, (LivingEntity) threat);
                }
            }
            case ATTACK -> {
                Entity target = this.attackTarget != null ? ((ServerLevel) puppet.level()).getEntity(this.attackTarget) : null;
                if (!(target instanceof LivingEntity living) || !living.isAlive()) {
                    boolean quiet = this.selfDefense;
                    this.selfDefense = false;
                    this.finishTask((quiet ? "DONE self-defence" : "DONE attack") + ": target " + (target == null ? "gone" : "dead"),
                            quiet ? null : "Target down.");
                    return;
                }
                this.fightTick(puppet, living);
            }
            case POSSESS -> {
                if (this.possession != null) {
                    this.tickPossession(puppet);
                }
            }
            case BREAK -> {
                if (this.breakPos == null) {
                    this.mode = Mode.IDLE;
                } else if (this.tickBreak(puppet)) {
                    this.finishTask("DONE break, holding " + itemId(puppet.getMainHandItem()), "Block mined.");
                }
            }
            case GATHER -> {
                if (this.breakPos == null) {
                    if (this.gatherCount >= this.gatherWanted) {
                        this.finishTask("DONE gather " + this.gatherName + ": " + this.gatherCount + " blocks, inventory: " + this.inventorySummary(puppet),
                                "Gathered " + this.gatherCount + " " + this.gatherName + ".");
                        return;
                    }
                    if (this.ticks % 10 != 0) {
                        return;
                    }
                    this.breakPos = this.findGatherTarget(puppet);
                    this.breakProgress = 0;
                    this.breakApproachTicks = 0;
                    if (this.breakPos == null) {
                        this.finishTask("DONE gather " + this.gatherName + ": ran out of reachable blocks within 24 after " + this.gatherCount + ", inventory: " + this.inventorySummary(puppet),
                                "Ran out of " + this.gatherName + " nearby after " + this.gatherCount + ".");
                    }
                } else if (this.tickBreak(puppet)) {
                    this.gatherCount++;
                    this.breakPos = null;
                }
            }
            default -> {
            }
        }
    }

    private void followTick(PuppetEntity puppet, Entity target) {
        if (target == null || target.level() != puppet.level()) {
            return;
        }
        double distance = puppet.distanceTo(target);
        if (distance > 48) {
            puppet.moveTo(target.getX(), target.getY(), target.getZ(), puppet.getYRot(), 0); // Too far behind: catch up.
        } else if (distance > this.followDistance) {
            if (this.ticks % 10 == 0 || puppet.getNavigation().isDone()) {
                puppet.getNavigation().moveTo(target, distance > 8 ? 1.5D : 1.1D);
            }
        } else {
            puppet.getNavigation().stop();
            puppet.getLookControl().setLookAt(target);
        }
    }

    /** Melee, or bow shots from range when holding a bow with arrows. **/
    private void fightTick(PuppetEntity puppet, LivingEntity target) {
        puppet.getLookControl().setLookAt(target);
        double distance = puppet.distanceTo(target);
        boolean archer = puppet.getMainHandItem().getItem() instanceof BowItem && puppet.getInventory().hasAnyOf(Set.of(Items.ARROW));
        if (archer && distance < 20 && puppet.hasLineOfSight(target)) {
            puppet.getNavigation().stop();
            if (this.attackCooldown <= 0) {
                this.shoot(puppet, target);
                this.attackCooldown = 25;
            }
            return;
        }
        if (distance > 2.5D) {
            if (this.ticks % 10 == 0 || puppet.getNavigation().isDone()) {
                puppet.getNavigation().moveTo(target, 1.4D);
            }
        } else if (this.attackCooldown <= 0) {
            puppet.swing(InteractionHand.MAIN_HAND);
            puppet.doHurtTarget(target);
            this.attackCooldown = 12;
        }
    }


    // ==================================================
    //                     Lookups
    // ==================================================
    private PuppetEntity getPuppet() {
        if (this.puppetId == null || this.server == null) {
            return null;
        }
        for (ServerLevel level : this.server.getAllLevels()) {
            if (level.getEntity(this.puppetId) instanceof PuppetEntity puppet && puppet.isAlive()) {
                return puppet;
            }
        }
        return null;
    }

    /**
     * Removes every loaded puppet except the current one. A puppet whose death didn't fully complete (a mod cancelled
     * or repeated the death) could otherwise linger next to its respawned replacement.
     */
    private void removeStrayPuppets(UUID reportId) {
        if (this.puppetId == null) {
            return;
        }
        for (ServerLevel level : this.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof PuppetEntity stray && !stray.getUUID().equals(this.puppetId) && !stray.isRemoved()) {
                    stray.discard();
                    this.event("PUPPET removed a stray old body at " + fmt(stray.position()));
                }
            }
        }
    }

    private ServerPlayer getOwner() {
        return this.ownerId == null || this.server == null ? null : this.server.getPlayerList().getPlayer(this.ownerId);
    }

    /** Nearest living entity (players first) whose name or type id matches the text. **/
    private Entity findEntity(Entity from, String text, double range) {
        String lower = text.toLowerCase();
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(text) && player.level() == from.level()) {
                return player;
            }
        }
        List<Entity> matches = from.level().getEntities(from, from.getBoundingBox().inflate(range), entity -> {
            if (!(entity instanceof LivingEntity) || !entity.isAlive()) {
                return false;
            }
            String name = entity.getName().getString().toLowerCase();
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            return name.equals(lower) || name.contains(lower) || type.equals(lower) || type.endsWith(":" + lower);
        });
        return matches.stream().min(Comparator.comparingDouble(entity -> entity.distanceTo(from))).orElse(null);
    }


    // ==================================================
    //                      State
    // ==================================================
    private String inventorySummary(PuppetEntity puppet) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < puppet.getInventory().getContainerSize(); i++) {
            ItemStack stack = puppet.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder();
        counts.forEach((id, count) -> builder.append(builder.isEmpty() ? "" : ", ").append(id).append(" x").append(count));
        return builder.toString();
    }

    private void writeState() {
        JsonObject state = new JsonObject();
        state.addProperty("time", LocalTime.now().format(TIME));
        ServerPlayer owner = this.getOwner();
        PuppetEntity puppet = this.getPuppet();
        ServerLevel level = owner != null ? owner.serverLevel() : this.server.overworld();
        state.addProperty("dayTime", level.getDayTime() % 24000);
        state.addProperty("weather", level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
        state.addProperty("difficulty", level.getDifficulty().getKey());

        JsonArray players = new JsonArray();
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", player.getName().getString());
            entry.addProperty("dimension", player.level().dimension().location().toString());
            entry.addProperty("pos", fmt(player.position()));
            entry.addProperty("facing", player.getDirection().getName() + " (yaw " + Math.round(player.getYRot()) + ", pitch " + Math.round(player.getXRot()) + ")");
            entry.addProperty("health", player.getHealth());
            entry.addProperty("food", player.getFoodData().getFoodLevel());
            entry.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
            entry.addProperty("held", itemId(player.getMainHandItem()));
            HitResult hit = player.pick(20, 1, false);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                entry.addProperty("lookingAt", BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(blockHit.getBlockPos()).getBlock()) + " @ " + blockHit.getBlockPos().toShortString());
            }
            entry.addProperty("biome", player.level().getBiome(player.blockPosition()).unwrapKey().map(key -> key.location().toString()).orElse("?"));
            players.add(entry);
        }
        state.add("players", players);

        if (puppet != null) {
            JsonObject puppetState = new JsonObject();
            puppetState.addProperty("pos", fmt(puppet.position()));
            puppetState.addProperty("dimension", puppet.level().dimension().location().toString());
            puppetState.addProperty("health", Math.round(puppet.getHealth()) + "/" + Math.round(puppet.getMaxHealth()));
            String modeText = this.mode.name().toLowerCase();
            switch (this.mode) {
                case WALK -> modeText += " " + fmt(this.walkTarget);
                case FOLLOW, GUARD -> modeText += " " + (this.targetName == null ? "owner" : this.targetName);
                case GATHER -> modeText += " " + this.gatherName + " " + this.gatherCount + "/" + this.gatherWanted;
                case BREAK -> modeText += this.breakPos != null ? " " + this.breakPos.toShortString() : "";
                default -> {
                }
            }
            puppetState.addProperty("mode", modeText);
            puppetState.addProperty("held", itemId(puppet.getMainHandItem()));
            puppetState.addProperty("offhand", itemId(puppet.getOffhandItem()));
            puppetState.addProperty("armor", itemId(puppet.getItemBySlot(EquipmentSlot.HEAD)) + ", " + itemId(puppet.getItemBySlot(EquipmentSlot.CHEST)) + ", "
                    + itemId(puppet.getItemBySlot(EquipmentSlot.LEGS)) + ", " + itemId(puppet.getItemBySlot(EquipmentSlot.FEET)));
            puppetState.addProperty("inventory", this.inventorySummary(puppet));
            puppetState.addProperty("invulnerable", puppet.isInvulnerable());
            if (owner != null && owner.level() == puppet.level()) {
                puppetState.addProperty("distanceToOwner", Math.round(puppet.distanceTo(owner) * 10) / 10.0);
            }
            state.add("puppet", puppetState);
        } else if (this.respawnTimer > 0) {
            state.addProperty("puppet", "dead, respawning in " + (this.respawnTimer / 20) + "s");
        }

        Entity center = owner != null ? owner : puppet;
        if (center != null) {
            JsonArray entities = new JsonArray();
            List<Entity> nearby = center.level().getEntities(center, center.getBoundingBox().inflate(32), entity -> entity instanceof LivingEntity && !(entity instanceof ServerPlayer) && !(entity instanceof PuppetEntity));
            nearby.sort(Comparator.comparingDouble(entity -> entity.distanceTo(center)));
            for (Entity entity : nearby.subList(0, Math.min(25, nearby.size()))) {
                entities.add(describe(entity, center));
            }
            state.add("nearbyEntities", entities);
        }

        // One-line summary, cheap for Claude to read.
        StringBuilder status = new StringBuilder();
        if (puppet != null) {
            status.append("puppet ").append(Math.round(puppet.getHealth())).append("hp").append(puppet.isTank() ? "(tank)" : "").append(" ").append(this.mode.name().toLowerCase())
                    .append(this.possession != null ? " " + this.possession.stepsUsed + "/" + this.possession.totalSteps : "")
                    .append(" @").append(String.format("%.0f %.0f %.0f", puppet.getX(), puppet.getY(), puppet.getZ()))
                    .append(" hand:").append(itemId(puppet.getMainHandItem())).append(" bag:").append(this.inventorySummary(puppet));
        } else {
            status.append("puppet ").append(this.respawnTimer > 0 ? "dead" : "missing");
        }
        if (owner != null) {
            status.append(" | ").append(owner.getName().getString()).append(" ").append(Math.round(owner.getHealth())).append("hp @")
                    .append(String.format("%.0f %.0f %.0f", owner.getX(), owner.getY(), owner.getZ())).append(" ").append(owner.level().dimension().location().getPath())
                    .append(" hand:").append(itemId(owner.getMainHandItem()));
        }
        if (center != null) {
            Map<String, Integer> nearbyCounts = new LinkedHashMap<>();
            for (Entity entity : center.level().getEntities(center, center.getBoundingBox().inflate(24), entity -> entity instanceof LivingEntity && !(entity instanceof ServerPlayer) && !(entity instanceof PuppetEntity))) {
                nearbyCounts.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), 1, Integer::sum);
            }
            status.append(" | near:");
            nearbyCounts.forEach((type, count) -> status.append(" ").append(type).append(count > 1 ? "x" + count : ""));
        }
        if (!this.tools.getMarks().isEmpty()) {
            status.append(" | marks:");
            this.tools.getMarks().forEach((name, mark) -> status.append(" ").append(name).append("=").append(String.format("%.0f,%.0f,%.0f", mark.x(), mark.y(), mark.z())));
        }
        status.append(" | undo:").append(this.tools.undoSize());
        status.append(" | t").append(level.getDayTime() % 24000).append(" ").append(level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
        try {
            Files.writeString(this.dir.resolve("status.txt"), status + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            Path tmp = this.dir.resolve("state.json.tmp");
            Files.writeString(tmp, GSON.toJson(state), StandardCharsets.UTF_8);
            Files.move(tmp, this.dir.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static JsonObject describe(Entity entity, Entity from) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        if (entity.hasCustomName()) {
            entry.addProperty("name", entity.getName().getString());
        }
        entry.addProperty("pos", fmt(entity.position()));
        entry.addProperty("distance", Math.round(entity.distanceTo(from) * 10) / 10.0);
        if (entity instanceof LivingEntity living) {
            entry.addProperty("health", Math.round(living.getHealth() * 10) / 10.0 + "/" + Math.round(living.getMaxHealth()));
        }
        return entry;
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
    }

    private static String fmt(Vec3 pos) {
        return String.format("%.1f %.1f %.1f", pos.x, pos.y, pos.z);
    }
}
