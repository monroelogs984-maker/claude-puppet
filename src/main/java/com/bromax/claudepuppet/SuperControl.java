package com.bromax.claudepuppet;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Drives the {@link SuperPuppet}: spawning/removing it (singleplayer, not LAN), ticking it (FakePlayers aren't ticked
 * by a connection), steering it, fighting with real player attacks, using/placing/breaking through its own game mode
 * handler, and keeping it asleep when the owner sleeps so it never blocks skipping the night.
 *
 * Commands (Claude writes "super &lt;command&gt;" to in.txt): spawn, dismiss, walkto &lt;x y z|mark&gt;, follow [name] [dist],
 * attack &lt;name|type&gt;, stop, tp &lt;x y z|mark&gt;, here, look &lt;x y z&gt;, lookat &lt;name|type&gt;, use, release, useon &lt;x y z&gt;,
 * break &lt;x y z&gt;, place &lt;x y z&gt; &lt;item&gt;, give &lt;item&gt; [count], select &lt;item&gt;, drop &lt;item|all&gt; [count], jump.
 */
public class SuperControl {
    private enum Mode { IDLE, WALK, FOLLOW, ATTACK, BREAK }

    private final PuppetBridge bridge;
    private final Consumer<String> event;
    private SuperPuppet body;
    private Mode mode = Mode.IDLE;
    private Vec3 walkTarget;
    private String followName;
    private double followDistance = 3;
    private Entity attackTarget;
    private BlockPos breakPos;
    private float breakProgress;
    private int stuckTicks = 0;
    private Vec3 lastPos;
    private int respawnTimer = -1;
    private boolean wanted = false;

    public SuperControl(PuppetBridge bridge, Consumer<String> event) {
        this.bridge = bridge;
        this.event = event;
    }

    public SuperPuppet getBody() {
        return this.body != null && !this.body.isRemoved() ? this.body : null;
    }

    private static boolean allowed(MinecraftServer server) {
        return server.isSingleplayer() && !server.isPublished();
    }

    // ==================================================
    //                  Spawn / Remove
    // ==================================================
    public String spawn(ServerPlayer owner) {
        MinecraftServer server = owner.getServer();
        if (!allowed(server)) {
            return "FAIL super: singleplayer worlds only (not dedicated servers or LAN)";
        }
        if (this.getBody() != null) {
            this.bringTo(owner);
            return "OK super already here";
        }
        ServerLevel level = owner.serverLevel();
        SuperPuppet body = new SuperPuppet(level, this::onDeath);
        Vec3 side = Vec3.directionFromRotation(0, owner.getYRot() - 90).scale(1.5);
        body.moveTo(owner.getX() + side.x, owner.getY(), owner.getZ() + side.z, owner.getYRot(), 0);
        body.setHealth(body.getMaxHealth());
        // Tell the (local) clients this player exists, unlisted (so it isn't in the tab list), then add it to the world.
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), List.of(body)));
        try {
            level.addNewPlayer(body);
        } catch (Exception e) {
            // Another mod's "player joined" hook couldn't cope with a connection-less player: back out cleanly.
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(SuperPuppet.PROFILE.getId())));
            if (!body.isRemoved()) {
                level.removePlayerImmediately(body, Entity.RemovalReason.DISCARDED);
            }
            StackTraceElement culprit = null;
            for (StackTraceElement element : e.getStackTrace()) {
                if (!element.getClassName().startsWith("net.minecraft") && !element.getClassName().startsWith("net.neoforged")
                        && !element.getClassName().startsWith("java.") && !element.getClassName().startsWith("com.bromax")) {
                    culprit = element;
                    break;
                }
            }
            e.printStackTrace();
            return "FAIL super: a mod crashed on the fake player joining (" + e.getClass().getSimpleName()
                    + (culprit != null ? " in " + culprit.getClassName() + "." + culprit.getMethodName() : "") + ")";
        }
        this.body = body;
        this.wanted = true;
        this.respawnTimer = -1;
        this.setIdle();
        this.mode = Mode.FOLLOW;
        this.followName = null;
        return "OK super spawned";
    }

    public void remove(MinecraftServer server, boolean dropItems) {
        SuperPuppet body = this.body;
        this.body = null;
        this.setIdle();
        if (body == null) {
            return;
        }
        if (dropItems && !body.isRemoved()) {
            body.getInventory().dropAll();
        }
        if (!body.isRemoved()) {
            body.serverLevel().removePlayerImmediately(body, Entity.RemovalReason.DISCARDED);
        }
        if (server != null) {
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(SuperPuppet.PROFILE.getId())));
        }
    }

    public void dismiss(MinecraftServer server) {
        this.wanted = false;
        this.respawnTimer = -1;
        this.remove(server, true);
    }

    /** A player (re)joined: tell their client about the super puppet so it can be drawn. **/
    public void onPlayerJoin(ServerPlayer player) {
        SuperPuppet body = this.getBody();
        if (body != null && player != body) {
            player.connection.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), List.of(body)));
        }
    }

    private void onDeath(SuperPuppet body, DamageSource source) {
        this.event.accept("SUPER DIED: " + source.getLocalizedDeathMessage(body).getString() + " (respawning next to the owner in 10s)");
        body.server.getPlayerList().broadcastSystemMessage(source.getLocalizedDeathMessage(body), false);
        this.respawnTimer = 200;
        this.remove(body.server, false);
    }

    private void bringTo(ServerPlayer owner) {
        SuperPuppet body = this.getBody();
        if (body == null) {
            return;
        }
        Vec3 side = Vec3.directionFromRotation(0, owner.getYRot() - 90).scale(1.5);
        if (body.level() != owner.level()) {
            this.remove(owner.getServer(), false);
            this.spawn(owner);
            return;
        }
        body.teleportTo(owner.getX() + side.x, owner.getY(), owner.getZ() + side.z);
    }


    // ==================================================
    //                      Tick
    // ==================================================
    public void tick(MinecraftServer server, ServerPlayer owner) {
        if (this.respawnTimer > 0 && --this.respawnTimer == 0) {
            if (owner != null && this.wanted) {
                this.event.accept("SUPER RESPAWNED: " + this.spawn(owner));
            } else {
                this.respawnTimer = 100;
            }
        }
        SuperPuppet body = this.getBody();
        if (body == null) {
            return;
        }
        if (!allowed(server)) {
            this.event.accept("SUPER removed: the world was opened to LAN");
            this.dismiss(server);
            return;
        }

        // Sleep with the owner, so a non-sleeping body never blocks skipping the night.
        if (owner != null && owner.level() == body.level()) {
            if (owner.isSleeping() && !body.isSleeping() && owner.getSleepingPos().isPresent()) {
                body.startSleeping(owner.getSleepingPos().get());
            } else if (!owner.isSleeping() && body.isSleeping()) {
                body.stopSleeping();
            }
        }

        body.zza = 0;
        body.xxa = 0;
        body.setJumping(false);
        body.setSprinting(false);
        if (!body.isSleeping()) {
            this.tickOrders(body, owner);
        }
        body.doTick(); // Physics, item use, food, effects... (a real connection would call this)
    }

    private void tickOrders(SuperPuppet body, ServerPlayer owner) {
        switch (this.mode) {
            case WALK -> {
                if (this.steer(body, this.walkTarget, 1.2D)) {
                    this.event.accept("SUPER DONE walkto, at " + fmt(body.position()));
                    this.setIdle();
                }
            }
            case FOLLOW -> {
                Entity target = this.followName == null ? owner : this.findEntity(body, this.followName, 96);
                if (target != null && target.level() == body.level()) {
                    if (body.distanceTo(target) > 32) {
                        body.teleportTo(target.getX(), target.getY(), target.getZ());
                    } else {
                        this.steer(body, target.position(), this.followDistance);
                    }
                }
            }
            case ATTACK -> {
                if (!(this.attackTarget instanceof LivingEntity living) || !living.isAlive() || living.level() != body.level()) {
                    this.event.accept("SUPER DONE attack: target " + (this.attackTarget == null || this.attackTarget.isRemoved() ? "gone" : "dead"));
                    this.setIdle();
                    this.mode = Mode.FOLLOW;
                    return;
                }
                body.lookAt(EntityAnchorArgument.Anchor.EYES, living.getEyePosition());
                double reach = body.entityInteractionRange();
                if (body.distanceTo(living) > reach - 0.5D) {
                    this.steer(body, living.position(), reach - 1.0D);
                } else if (body.getAttackStrengthScale(0.5F) >= 0.95F) {
                    body.attack(living); // A real player attack: enchantments, crits, sweeps and mods' player-attack hooks.
                    body.swing(InteractionHand.MAIN_HAND, true);
                }
            }
            case BREAK -> {
                ServerLevel level = body.serverLevel();
                BlockState state = level.getBlockState(this.breakPos);
                if (state.isAir()) {
                    this.event.accept("SUPER DONE break");
                    this.setIdle();
                    return;
                }
                Vec3 center = Vec3.atCenterOf(this.breakPos);
                if (body.getEyePosition().distanceTo(center) > body.blockInteractionRange()) {
                    this.steer(body, center, 3.5D);
                    return;
                }
                body.lookAt(EntityAnchorArgument.Anchor.EYES, center);
                this.breakProgress += state.getDestroyProgress(body, level, this.breakPos);
                if (body.tickCount % 5 == 0) {
                    body.swing(InteractionHand.MAIN_HAND, true);
                }
                level.destroyBlockProgress(body.getId(), this.breakPos, Math.min(9, (int) (this.breakProgress * 10)));
                if (this.breakProgress >= 1.0F) {
                    level.destroyBlockProgress(body.getId(), this.breakPos, -1);
                    body.gameMode.destroyBlock(this.breakPos); // Real player break: drops, tool damage, break events.
                    this.event.accept("SUPER DONE break " + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
                    this.setIdle();
                }
            }
            default -> {
            }
        }
    }

    /** Walks toward a point with the player's own inputs; returns true when within the distance. **/
    private boolean steer(SuperPuppet body, Vec3 target, double stopDistance) {
        double dx = target.x - body.getX();
        double dz = target.z - body.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= stopDistance) {
            return true;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        body.zza = 1.0F;
        body.setSprinting(distance > 8);
        if ((body.horizontalCollision && body.onGround()) || body.isInWater()) {
            body.setJumping(true);
        }
        if (this.lastPos != null && body.position().distanceToSqr(this.lastPos) < 0.0004D) {
            if (++this.stuckTicks > 100 && this.mode == Mode.WALK) {
                this.event.accept("SUPER STUCK walkto at " + fmt(body.position()) + ", " + Math.round(distance) + " blocks short");
                this.setIdle();
            }
        } else {
            this.stuckTicks = 0;
        }
        this.lastPos = body.position();
        return false;
    }

    private void setIdle() {
        this.mode = Mode.IDLE;
        this.attackTarget = null;
        this.breakPos = null;
        this.breakProgress = 0;
        this.stuckTicks = 0;
        this.lastPos = null;
    }


    // ==================================================
    //                    Commands
    // ==================================================
    /** Runs "super ..." commands; returns the line for events.log. **/
    public String command(MinecraftServer server, ServerPlayer owner, String[] args, java.util.function.BiFunction<String[], Integer, Vec3> targetFrom) {
        String verb = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if ("spawn".equals(verb)) {
            return owner == null ? "FAIL super spawn: owner offline" : this.spawn(owner);
        }
        if ("dismiss".equals(verb)) {
            this.dismiss(server);
            return "OK super dismissed";
        }
        SuperPuppet body = this.getBody();
        if (body == null) {
            return "FAIL super " + verb + ": no super puppet (super spawn)";
        }
        try {
            switch (verb) {
                case "walkto" -> {
                    this.setIdle();
                    this.walkTarget = targetFrom.apply(args, 1);
                    this.mode = Mode.WALK;
                }
                case "follow" -> {
                    this.setIdle();
                    this.followName = args.length > 1 ? args[1] : null;
                    this.followDistance = args.length > 2 ? Double.parseDouble(args[2]) : 3;
                    this.mode = Mode.FOLLOW;
                }
                case "attack" -> {
                    Entity target = this.findEntity(body, args[1], 48);
                    if (target == null) {
                        return "FAIL super attack: no '" + args[1] + "' within 48";
                    }
                    this.setIdle();
                    this.attackTarget = target;
                    this.mode = Mode.ATTACK;
                }
                case "stop" -> this.setIdle();
                case "tp" -> {
                    Vec3 target = targetFrom.apply(args, 1);
                    body.teleportTo(target.x, target.y, target.z);
                }
                case "here" -> {
                    if (owner == null) {
                        return "FAIL super here: owner offline";
                    }
                    this.bringTo(owner);
                }
                case "look" -> body.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3])));
                case "lookat" -> {
                    Entity target = this.findEntity(body, args[1], 64);
                    if (target == null) {
                        return "FAIL super lookat: no '" + args[1] + "'";
                    }
                    body.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
                }
                case "use" -> {
                    var result = body.gameMode.useItem(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND);
                    return "SUPER USE " + itemId(body.getMainHandItem()) + " -> " + result + (body.isUsingItem() ? " (holding use; 'super release' to let go)" : "");
                }
                case "release" -> {
                    body.releaseUsingItem();
                }
                case "useon" -> {
                    BlockPos pos = BlockPos.containing(Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                    return "SUPER USE on " + pos.toShortString() + " -> " + this.useOn(body, pos);
                }
                case "break" -> {
                    this.setIdle();
                    this.breakPos = BlockPos.containing(Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                    this.mode = Mode.BREAK;
                }
                case "place" -> {
                    BlockPos pos = BlockPos.containing(Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                    Item item = item(args[4]);
                    if (!this.selectItem(body, item)) {
                        body.getInventory().add(new ItemStack(item));
                        this.selectItem(body, item);
                    }
                    // Place against the neighbour facing the body, like a player clicking that face.
                    Direction face = Direction.getNearest(body.getX() - (pos.getX() + 0.5), body.getEyeY() - (pos.getY() + 0.5), body.getZ() - (pos.getZ() + 0.5));
                    BlockPos against = pos.relative(face.getOpposite());
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(against).relative(face, 0.5), face, against, false);
                    body.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
                    var result = body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                    body.swing(InteractionHand.MAIN_HAND, true);
                    return "SUPER PLACE " + args[4] + " @ " + pos.toShortString() + " -> " + result;
                }
                case "give" -> {
                    Item item = item(args[1]);
                    int count = args.length > 2 ? Integer.parseInt(args[2]) : 1;
                    while (count > 0) {
                        int amount = Math.min(count, item.getDefaultMaxStackSize());
                        if (!body.getInventory().add(new ItemStack(item, amount))) {
                            return "FAIL super give: inventory full";
                        }
                        count -= amount;
                    }
                }
                case "select" -> {
                    if (!this.selectItem(body, item(args[1]))) {
                        return "FAIL super select: no " + args[1] + " in its inventory";
                    }
                }
                case "drop" -> {
                    if ("all".equals(args[1])) {
                        body.getInventory().dropAll();
                    } else {
                        Item item = item(args[1]);
                        int count = args.length > 2 ? Integer.parseInt(args[2]) : 64;
                        ItemStack removed = new ItemStack(item, 0);
                        Inventory inventory = body.getInventory();
                        for (int i = 0; i < inventory.getContainerSize() && count > 0; i++) {
                            ItemStack stack = inventory.getItem(i);
                            if (stack.is(item)) {
                                ItemStack taken = inventory.removeItem(i, count);
                                count -= taken.getCount();
                                body.drop(taken, false, true);
                            }
                        }
                    }
                }
                case "jump" -> {
                    if (body.onGround()) {
                        body.jumpFromGround();
                    }
                }
                default -> {
                    return "FAIL unknown super command: " + verb;
                }
            }
        } catch (Exception e) {
            return "FAIL super " + verb + " (" + e + ")";
        }
        return "OK super " + String.join(" ", args);
    }

    private net.minecraft.world.InteractionResult useOn(SuperPuppet body, BlockPos pos) {
        Direction face = Direction.getNearest(body.getX() - (pos.getX() + 0.5), body.getEyeY() - (pos.getY() + 0.5), body.getZ() - (pos.getZ() + 0.5));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
        body.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        var result = body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        body.swing(InteractionHand.MAIN_HAND, true);
        return result;
    }

    /** Puts the item in the selected hotbar slot (swapping from anywhere in the inventory). **/
    private boolean selectItem(SuperPuppet body, Item item) {
        Inventory inventory = body.getInventory();
        if (inventory.getSelected().is(item)) {
            return true;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                ItemStack found = inventory.getItem(i);
                inventory.setItem(i, inventory.getSelected());
                inventory.setItem(inventory.selected, found);
                return true;
            }
        }
        return false;
    }

    private Entity findEntity(Entity from, String text, double range) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (ServerPlayer player : from.getServer().getPlayerList().getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(text) && player.level() == from.level()) {
                return player;
            }
        }
        List<Entity> matches = from.level().getEntities(from, from.getBoundingBox().inflate(range), entity -> {
            if (!(entity instanceof LivingEntity) || !entity.isAlive()) {
                return false;
            }
            String name = entity.getName().getString().toLowerCase(Locale.ROOT);
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            return name.equals(lower) || name.contains(lower) || type.equals(lower) || type.endsWith(":" + lower);
        });
        return matches.stream().min(Comparator.comparingDouble(entity -> entity.distanceTo(from))).orElse(null);
    }

    /** One-line status for status.txt. **/
    public String status() {
        SuperPuppet body = this.getBody();
        if (body == null) {
            return this.respawnTimer > 0 ? "super: dead, respawning" : null;
        }
        return "super " + Math.round(body.getHealth()) + "hp food " + body.getFoodData().getFoodLevel() + " " + this.mode.name().toLowerCase(Locale.ROOT)
                + " @" + String.format(Locale.ROOT, "%.0f %.0f %.0f", body.getX(), body.getY(), body.getZ()) + " hand:" + itemId(body.getMainHandItem())
                + (body.isSleeping() ? " sleeping" : "");
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElseThrow(() -> new IllegalArgumentException("unknown item " + id));
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
    }

    private static String fmt(Vec3 pos) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", pos.x, pos.y, pos.z);
    }
}
