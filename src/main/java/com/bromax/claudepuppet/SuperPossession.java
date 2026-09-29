package com.bromax.claudepuppet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Possession of the super puppet: Claude drives the real player body one step at a time, with the same inputs as
 * {@link PuppetPossession} (forward/back/left/right N, turn, look, jump, sneak, sprint, wait, hit, use, place) plus
 * {@code release} (let go of a held use, e.g. fire a bow) and {@code select <item>} (hotbar). Everything goes through
 * the player's own code paths: attacks are {@code player.attack}, uses and placing go through its game mode, and
 * {@code hit} on a block mines it at real speed for the held tool.
 */
public class SuperPossession {
    public static final int IDLE_RELEASE_TICKS = 60 * 20;

    public final int totalSteps;
    public int stepsUsed = 0;
    public int idleTicks = 0;
    public boolean stepRunning = false;
    public boolean awaitingSnapshot = false;
    public float yaw;
    public float pitch;
    private boolean sprint = false;
    private final Deque<String[]> actions = new ArrayDeque<>();

    private String[] current;
    private Vec3 moveStart;
    private Vec3 lastPos;
    private int actionTicks = 0;
    private int stuckTicks = 0;
    private BlockPos breakPos;
    private float breakProgress;

    public SuperPossession(int totalSteps, SuperPuppet body) {
        this.totalSteps = totalSteps;
        this.yaw = body.getYRot();
        this.pitch = body.getXRot();
    }

    public void queueStep(String inputs) {
        for (String input : inputs.split(";")) {
            String trimmed = input.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                this.actions.add(trimmed.split("\\s+"));
            }
        }
        this.stepRunning = true;
        this.idleTicks = 0;
    }

    /** One tick; null while busy, "" when the step is done, otherwise a note about an input that failed. **/
    public String tick(SuperPuppet body) {
        body.setYRot(this.yaw);
        body.setYHeadRot(this.yaw);
        body.setXRot(this.pitch);
        body.setSprinting(this.sprint && this.current != null && isMove(this.current[0]));
        if (!this.stepRunning) {
            return null;
        }
        if (this.current == null) {
            this.current = this.actions.poll();
            if (this.current == null) {
                this.stepRunning = false;
                return "";
            }
            this.actionTicks = 0;
            this.stuckTicks = 0;
            this.moveStart = body.position();
            this.lastPos = body.position();
            String problem = this.begin(body);
            if (problem != null) {
                this.current = null;
                return problem;
            }
        }
        this.actionTicks++;
        return this.continueAction(body);
    }

    private static boolean isMove(String verb) {
        return "forward".equals(verb) || "back".equals(verb) || "left".equals(verb) || "right".equals(verb);
    }

    private String begin(SuperPuppet body) {
        String verb = this.current[0];
        try {
            switch (verb) {
                case "turn" -> {
                    this.yaw += Float.parseFloat(this.current[1]);
                    this.current = null;
                }
                case "look" -> {
                    this.pitch = Math.max(-90, Math.min(90, Float.parseFloat(this.current[1])));
                    this.current = null;
                }
                case "sneak" -> {
                    body.setShiftKeyDown("on".equals(this.arg(1, "on")));
                    this.current = null;
                }
                case "sprint" -> {
                    this.sprint = "on".equals(this.arg(1, "on"));
                    this.current = null;
                }
                case "select" -> {
                    Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(this.current[1])).orElseThrow(() -> new IllegalArgumentException("unknown item"));
                    this.current = null;
                    if (!select(body, item)) {
                        return "select: no " + BuiltInRegistries.ITEM.getKey(item).getPath() + " in the inventory";
                    }
                }
                case "hit" -> {
                    HitResult hit = this.crosshair(body, body.blockInteractionRange());
                    if (hit instanceof EntityHitResult entityHit) {
                        body.attack(entityHit.getEntity());
                        body.swing(InteractionHand.MAIN_HAND, true);
                        this.current = null;
                    } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                        this.breakPos = blockHit.getBlockPos();
                        this.breakProgress = 0;
                    } else {
                        body.swing(InteractionHand.MAIN_HAND, true);
                        this.current = null;
                        return "hit: nothing in reach";
                    }
                }
                case "use" -> {
                    HitResult hit = this.crosshair(body, body.blockInteractionRange());
                    ServerLevel level = body.serverLevel();
                    if (hit instanceof EntityHitResult entityHit) {
                        body.interactOn(entityHit.getEntity(), InteractionHand.MAIN_HAND);
                    } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                        var result = body.gameMode.useItemOn(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND, blockHit);
                        if (!result.consumesAction()) {
                            body.gameMode.useItem(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND);
                        }
                    } else {
                        body.gameMode.useItem(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND);
                    }
                    body.swing(InteractionHand.MAIN_HAND, true);
                    this.current = null;
                }
                case "release" -> {
                    body.releaseUsingItem();
                    this.current = null;
                }
                case "place" -> {
                    HitResult hit = this.crosshair(body, body.blockInteractionRange());
                    String blockArg = this.arg(1, null);
                    this.current = null;
                    if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                        return "place: no block in reach to place against";
                    }
                    if (blockArg != null) {
                        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(blockArg)).orElseThrow(() -> new IllegalArgumentException("unknown item " + blockArg));
                        if (!select(body, item)) {
                            body.getInventory().add(new ItemStack(item));
                            select(body, item);
                        }
                    }
                    var result = body.gameMode.useItemOn(body, body.serverLevel(), body.getMainHandItem(), InteractionHand.MAIN_HAND, blockHit);
                    body.swing(InteractionHand.MAIN_HAND, true);
                    if (!result.consumesAction()) {
                        return "place: " + result;
                    }
                }
                case "jump" -> {
                    if (body.onGround()) {
                        body.jumpFromGround();
                    }
                }
                case "forward", "back", "left", "right", "wait" -> Float.parseFloat(this.arg(1, "1"));
                default -> {
                    this.current = null;
                    return "unknown input '" + verb + "'";
                }
            }
        } catch (Exception e) {
            this.current = null;
            return verb + ": " + e.getMessage();
        }
        return null;
    }

    private String continueAction(SuperPuppet body) {
        if (this.current == null) {
            return null;
        }
        String verb = this.current[0];
        switch (verb) {
            case "forward", "back", "left", "right" -> {
                double wanted = Double.parseDouble(this.arg(1, "1"));
                double moved = horizontal(body.position().subtract(this.moveStart));
                if (moved >= wanted) {
                    this.current = null;
                    return null;
                }
                body.zza = "forward".equals(verb) ? 1 : "back".equals(verb) ? -1 : 0;
                body.xxa = "left".equals(verb) ? 1 : "right".equals(verb) ? -1 : 0;
                if (body.horizontalCollision && body.onGround() && "forward".equals(verb)) {
                    body.setJumping(true); // Step up single blocks like auto-jump.
                }
                if (horizontal(body.position().subtract(this.lastPos)) < 0.01D) {
                    if (++this.stuckTicks > 20) {
                        this.current = null;
                        return verb + ": blocked after " + String.format(Locale.ROOT, "%.1f", moved) + " blocks";
                    }
                } else {
                    this.stuckTicks = 0;
                }
                this.lastPos = body.position();
                if (this.actionTicks > 20 * 15) {
                    this.current = null;
                    return verb + ": gave up after 15s";
                }
            }
            case "wait" -> {
                if (this.actionTicks >= Integer.parseInt(this.arg(1, "20"))) {
                    this.current = null;
                }
            }
            case "jump" -> {
                if (this.actionTicks >= 10) {
                    this.current = null;
                }
            }
            case "hit" -> {
                ServerLevel level = body.serverLevel();
                BlockState state = level.getBlockState(this.breakPos);
                if (state.isAir()) {
                    this.current = null;
                    this.breakPos = null;
                    return null;
                }
                if (state.getDestroySpeed(level, this.breakPos) < 0) {
                    this.current = null;
                    this.breakPos = null;
                    return "hit: unbreakable block";
                }
                this.breakProgress += state.getDestroyProgress(body, level, this.breakPos);
                if (this.actionTicks % 5 == 0) {
                    body.swing(InteractionHand.MAIN_HAND, true);
                }
                level.destroyBlockProgress(body.getId(), this.breakPos, Math.min(9, (int) (this.breakProgress * 10)));
                if (this.breakProgress >= 1.0F) {
                    level.destroyBlockProgress(body.getId(), this.breakPos, -1);
                    body.gameMode.destroyBlock(this.breakPos);
                    this.current = null;
                    this.breakPos = null;
                } else if (this.actionTicks > 20 * 30) {
                    level.destroyBlockProgress(body.getId(), this.breakPos, -1);
                    this.current = null;
                    this.breakPos = null;
                    return "hit: still mining after 30s, gave up";
                }
            }
            default -> this.current = null;
        }
        return null;
    }

    /** What's in the body's crosshair (nearest entity, else block) along its look direction. **/
    public HitResult crosshair(SuperPuppet body, double reach) {
        Vec3 eye = body.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(this.pitch, this.yaw);
        Vec3 end = eye.add(look.scale(reach));
        BlockHitResult blockHit = body.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, body));
        double blockDistance = blockHit.getType() == HitResult.Type.MISS ? reach : blockHit.getLocation().distanceTo(eye);
        AABB box = body.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0D);
        EntityHitResult entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(body, eye, eye.add(look.scale(blockDistance)), box,
                entity -> entity instanceof LivingEntity && entity.isAlive() && entity != body && !entity.isSpectator(), blockDistance * blockDistance);
        return entityHit != null ? entityHit : blockHit;
    }

    public String describeCrosshair(SuperPuppet body) {
        HitResult hit = this.crosshair(body, 16.0D);
        Vec3 eye = body.getEyePosition();
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + String.format(Locale.ROOT, " %.1fm", entity.distanceTo(body))
                    + (entity instanceof LivingEntity living ? " (" + Math.round(living.getHealth()) + "hp)" : "");
        }
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return BuiltInRegistries.BLOCK.getKey(body.level().getBlockState(blockHit.getBlockPos()).getBlock()).getPath()
                    + String.format(Locale.ROOT, " %.1fm (%s face @ %s)", hit.getLocation().distanceTo(eye), blockHit.getDirection().getName(), blockHit.getBlockPos().toShortString());
        }
        return "nothing within 16";
    }

    /** Moves the item into the selected hotbar slot. **/
    public static boolean select(SuperPuppet body, Item item) {
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

    private String arg(int index, String fallback) {
        return this.current.length > index ? this.current[index] : fallback;
    }

    private static double horizontal(Vec3 vec) {
        return Math.sqrt(vec.x * vec.x + vec.z * vec.z);
    }
}
