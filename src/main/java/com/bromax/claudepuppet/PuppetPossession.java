package com.bromax.claudepuppet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Possession: Claude drives the puppet directly, one step at a time. A step is a batch of inputs separated by ";":
 * <pre>
 * forward|back &lt;blocks&gt;     walk relative to where it faces (stops early if blocked)
 * left|right &lt;blocks&gt;       strafe
 * turn &lt;degrees&gt;            + turns right, - turns left
 * look &lt;degrees&gt;            + looks down, - looks up (absolute pitch: "look 0" = straight ahead)
 * jump  |  sneak on|off  |  sprint on|off  |  wait &lt;ticks&gt;
 * hit                       attack what's in the crosshair; if it's a block, mine it (real dig time)
 * use                       right-click with the held item at what's in the crosshair (or the air)
 * place &lt;block id&gt;         place a block against the face in the crosshair
 * </pre>
 * When a step's inputs are done the bridge takes a first-person snapshot and reports what's in the crosshair.
 */
public class PuppetPossession {
    public static final int IDLE_RELEASE_TICKS = 60 * 20;
    private static final double WALK_SPEED = 0.4D;
    private static final double SPRINT_SPEED = 0.52D;

    public final int totalSteps;
    public int stepsUsed = 0;
    public int idleTicks = 0;
    public boolean stepRunning = false;
    public boolean awaitingSnapshot = false;
    public float yaw;
    public float pitch;
    private boolean sprint = false;
    private final Deque<String[]> actions = new ArrayDeque<>();

    // Current action state:
    private String[] current;
    private Vec3 moveStart;
    private int actionTicks = 0;
    private int stuckTicks = 0;
    private Vec3 lastPos;
    public BlockPos breakPos;

    public PuppetPossession(int totalSteps, PuppetEntity puppet) {
        this.totalSteps = totalSteps;
        this.yaw = puppet.getYHeadRot();
        this.pitch = puppet.getXRot();
    }

    public void start(PuppetEntity puppet) {
        puppet.setPossessed(true);
        puppet.getNavigation().stop();
        puppet.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(WALK_SPEED);
    }

    public void end(PuppetEntity puppet) {
        puppet.setPossessed(false);
        puppet.setShiftKeyDown(false);
        puppet.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.3D);
    }

    /** Queues a step ("forward 3; turn 90; jump"). **/
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

    /**
     * Runs one tick of the current step. Returns null while still busy, "" when the step is finished, or a message for
     * an input that failed (the step carries on with the next input).
     */
    public String tick(PuppetEntity puppet, PuppetBridge bridge) {
        this.holdLook(puppet);
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
            this.moveStart = puppet.position();
            this.lastPos = puppet.position();
            String problem = this.begin(puppet, bridge);
            if (problem != null) {
                this.current = null;
                return problem;
            }
        }
        this.actionTicks++;
        String problem = this.continueAction(puppet, bridge);
        return problem;
    }

    /** Instant inputs happen here; returns an error message or null. **/
    private String begin(PuppetEntity puppet, PuppetBridge bridge) {
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
                    puppet.setShiftKeyDown("on".equals(this.arg(1, "on")));
                    this.current = null;
                }
                case "sprint" -> {
                    this.sprint = "on".equals(this.arg(1, "on"));
                    puppet.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(this.sprint ? SPRINT_SPEED : WALK_SPEED);
                    this.current = null;
                }
                case "hit" -> {
                    HitResult hit = this.crosshair(puppet, 4.5D);
                    if (hit instanceof EntityHitResult entityHit) {
                        puppet.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        puppet.doHurtTarget(entityHit.getEntity());
                        this.current = null;
                    } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                        this.breakPos = blockHit.getBlockPos();
                        bridge.startPossessBreak(this.breakPos);
                    } else {
                        puppet.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        this.current = null;
                        return "hit: nothing in reach";
                    }
                }
                case "use" -> {
                    HitResult hit = this.crosshair(puppet, 4.5D);
                    bridge.possessUse(puppet, hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit.getBlockPos() : null);
                    this.current = null;
                }
                case "place" -> {
                    HitResult hit = this.crosshair(puppet, 4.5D);
                    if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                        this.current = null;
                        return "place: no block in reach to place against";
                    }
                    bridge.possessPlace(puppet, blockHit.getBlockPos().relative(blockHit.getDirection()), this.current.length > 1 ? this.current[1] : null);
                    this.current = null;
                }
                case "jump" -> {
                    puppet.getJumpControl().jump();
                }
                case "forward", "back", "left", "right", "wait" -> {
                    Float.parseFloat(this.arg(1, "1"));
                }
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

    /** Ongoing inputs; returns an error message or null (clears current when done). **/
    private String continueAction(PuppetEntity puppet, PuppetBridge bridge) {
        if (this.current == null) {
            return null;
        }
        String verb = this.current[0];
        switch (verb) {
            case "forward", "back", "left", "right" -> {
                double wanted = Double.parseDouble(this.arg(1, "1"));
                double moved = horizontal(puppet.position().subtract(this.moveStart));
                if (moved >= wanted) {
                    this.current = null;
                    return null;
                }
                float forward = "forward".equals(verb) ? 1 : "back".equals(verb) ? -1 : 0;
                float strafe = "left".equals(verb) ? 1 : "right".equals(verb) ? -1 : 0;
                puppet.getMoveControl().strafe(forward, strafe);
                if (horizontal(puppet.position().subtract(this.lastPos)) < 0.01D) {
                    if (++this.stuckTicks > 20) {
                        this.current = null;
                        return verb + ": blocked after " + String.format(Locale.ROOT, "%.1f", moved) + " blocks";
                    }
                } else {
                    this.stuckTicks = 0;
                }
                this.lastPos = puppet.position();
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
                if (this.breakPos == null || bridge.tickPossessBreak(puppet)) {
                    this.breakPos = null;
                    this.current = null;
                }
            }
            default -> this.current = null;
        }
        return null;
    }

    /** Keeps the head and body pointing where Claude set them. **/
    private void holdLook(PuppetEntity puppet) {
        puppet.setYRot(this.yaw);
        puppet.setYHeadRot(this.yaw);
        puppet.setYBodyRot(this.yaw);
        puppet.setXRot(this.pitch);
        Vec3 target = puppet.getEyePosition().add(Vec3.directionFromRotation(this.pitch, this.yaw).scale(10));
        puppet.getLookControl().setLookAt(target.x, target.y, target.z, 360, 360);
    }

    /** What's in the puppet's crosshair: the nearest entity or block along its look direction. **/
    public HitResult crosshair(PuppetEntity puppet, double reach) {
        Vec3 eye = puppet.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(this.pitch, this.yaw);
        Vec3 end = eye.add(look.scale(reach));
        BlockHitResult blockHit = puppet.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, puppet));
        double blockDistance = blockHit.getType() == HitResult.Type.MISS ? reach : blockHit.getLocation().distanceTo(eye);
        AABB box = puppet.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0D);
        EntityHitResult entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(puppet, eye, eye.add(look.scale(blockDistance)), box,
                entity -> entity instanceof LivingEntity && entity.isAlive() && entity != puppet, blockDistance * blockDistance);
        return entityHit != null ? entityHit : blockHit;
    }

    /** One line describing the crosshair, for the step report. **/
    public String describeCrosshair(PuppetEntity puppet) {
        HitResult hit = this.crosshair(puppet, 16.0D);
        Vec3 eye = puppet.getEyePosition();
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + String.format(Locale.ROOT, " %.1fm", entity.distanceTo(puppet))
                    + (entity instanceof LivingEntity living ? " (" + Math.round(living.getHealth()) + "hp)" : "");
        }
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return BuiltInRegistries.BLOCK.getKey(puppet.level().getBlockState(blockHit.getBlockPos()).getBlock()).getPath()
                    + String.format(Locale.ROOT, " %.1fm (%s face @ %s)", hit.getLocation().distanceTo(eye), blockHit.getDirection().getName(), blockHit.getBlockPos().toShortString());
        }
        return "nothing within 16";
    }

    private String arg(int index, String fallback) {
        return this.current.length > index ? this.current[index] : fallback;
    }

    private static double horizontal(Vec3 vec) {
        return Math.sqrt(vec.x * vec.x + vec.z * vec.z);
    }
}
