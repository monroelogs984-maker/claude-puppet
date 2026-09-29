package com.bromax.claudepuppet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * "Claude's Puppet": a mock player. A plain mob with the player model, controlled by {@link PuppetBridge} (which gives
 * it orders through the normal mob navigation). Its own AI only swims and looks at nearby players when idle.
 *
 * 150 health, regenerates 1 heart every 2 seconds when it hasn't been hurt for 10 seconds, has a 36 slot inventory it
 * picks items up into (saved with the world), and drops everything when it dies (the bridge respawns it).
 */
public class PuppetEntity extends PathfinderMob implements InventoryCarrier {
    public static final int INVENTORY_SIZE = 36;
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

    /** Tank mode: 1000 health, endless Regeneration V, and no hit deals more than 10 (except /kill-type damage). **/
    public static final double TANK_HEALTH = 1000.0D;
    public static final float TANK_DAMAGE_CAP = 10.0F;
    public static final double NORMAL_HEALTH = 150.0D;
    private boolean tank = false;

    /** While Claude possesses it: its own look-around AI is off and Claude's inputs drive it. **/
    private boolean possessed = false;

    public PuppetEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCustomName(Component.literal("Claude's Puppet").withStyle(style -> style.withColor(TextColor.fromRgb(0xD97757))));
        this.setCustomNameVisible(true);
        this.setCanPickUpLoot(true);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setDropChance(slot, 2.0F); // Always drop worn/held gear, like a player.
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 150.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.FOLLOW_RANGE, 96.0D)
                .add(Attributes.STEP_HEIGHT, 1.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F) {
            @Override
            public boolean canUse() {
                return !PuppetEntity.this.possessed && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !PuppetEntity.this.possessed && super.canContinueToUse();
            }
        });
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                return !PuppetEntity.this.possessed && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !PuppetEntity.this.possessed && super.canContinueToUse();
            }
        });
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // Regeneration out of combat:
        if (!this.level().isClientSide && this.isAlive() && this.tickCount % 40 == 0 && this.getHealth() < this.getMaxHealth()
                && this.tickCount - this.getLastHurtByMobTimestamp() > 200 && this.hurtTime == 0) {
            this.heal(2.0F);
        }
    }

    public boolean isPossessed() {
        return this.possessed;
    }

    public void setPossessed(boolean possessed) {
        this.possessed = possessed;
    }

    public boolean isTank() {
        return this.tank;
    }

    public void setTank(boolean tank) {
        this.tank = tank;
        this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(tank ? TANK_HEALTH : NORMAL_HEALTH);
        if (tank) {
            this.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION,
                    net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION, 4, false, false, true));
            this.setHealth(this.getMaxHealth());
        } else {
            this.removeEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
            this.setHealth(Math.min(this.getHealth(), this.getMaxHealth()));
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return this.isInvulnerable() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }


    // ==================================================
    //                    Inventory
    // ==================================================
    @Override
    public SimpleContainer getInventory() {
        return this.inventory;
    }

    @Override
    public boolean wantsToPickUp(ItemStack stack) {
        return this.inventory.canAddItem(stack);
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        InventoryCarrier.pickUpItem(this, this, itemEntity);
    }

    /** Adds a stack to the inventory; returns what didn't fit. **/
    public ItemStack addToInventory(ItemStack stack) {
        return this.inventory.addItem(stack);
    }

    /** Removes up to count of the item from the inventory, returns what was removed. **/
    public ItemStack takeFromInventory(net.minecraft.world.item.Item item, int count) {
        return this.inventory.removeItemType(item, count);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        for (ItemStack stack : this.inventory.removeAllItems()) {
            this.spawnAtLocation(stack);
        }
    }

    /** Bumped when saved puppets need upgrading (attribute base values are saved with the entity). **/
    private static final int PUPPET_VERSION = 2;

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        this.writeInventoryToTag(tag, this.registryAccess());
        tag.putInt("PuppetVersion", PUPPET_VERSION);
        tag.putBoolean("PuppetTank", this.tank);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.readInventoryFromTag(tag, this.registryAccess());
        this.tank = tag.getBoolean("PuppetTank");
        if (tag.getInt("PuppetVersion") < 2) {
            // v1 puppets: 20 health, 4 base damage, invulnerable.
            this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(150.0D);
            this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(2.0D);
            this.setHealth(this.getMaxHealth());
            this.setInvulnerable(false);
            this.setCanPickUpLoot(true);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                this.setDropChance(slot, 2.0F);
            }
        }
    }

    public Container getContainer() {
        return this.inventory;
    }
}
