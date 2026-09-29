package com.bromax.claudepuppet;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The "super puppet": a real server-side player body for Claude, for testing things mods only do for players (mob
 * targeting, player-proximity spawns, player attack/use hooks...).
 *
 * Built on NeoForge's FakePlayer so it never touches networking: its connection silently discards everything and it
 * gets no-op advancements. It is added to the world only (not the server's player list or tab list), is ticked by
 * {@link PuppetBridge}, and only exists in singleplayer worlds that aren't open to LAN.
 *
 * Differences from a plain FakePlayer: it can be hurt and die (FakePlayer is invulnerable and immortal), and after
 * construction {@link #isFakePlayer()} is false so other mods treat it as an ordinary player.
 */
public class SuperPuppet extends FakePlayer {
    public static final GameProfile PROFILE = new GameProfile(UUID.fromString("c1a0de00-5a9e-4d00-8000-00005a9e0001"), "Claude_Puppet");

    private final boolean constructed;
    private final BiConsumer<SuperPuppet, DamageSource> onDeath;

    public SuperPuppet(ServerLevel level, BiConsumer<SuperPuppet, DamageSource> onDeath) {
        super(level, PROFILE);
        this.onDeath = onDeath;
        this.constructed = true;
    }

    /**
     * True only while the ServerPlayer constructor runs (so NeoForge hands it the no-op fake advancements), false after
     * that so mods don't skip it the way they skip machine fake players.
     */
    @Override
    public boolean isFakePlayer() {
        return !this.constructed;
    }

    /** Mirrors Entity/Player invulnerability rules (FakePlayer makes everything invulnerable). **/
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (this.isRemoved()) {
            return true;
        }
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        if (this.getAbilities().invulnerable || this.isInvulnerable()) {
            return true;
        }
        GameRules rules = this.serverLevel().getGameRules();
        if (source.is(DamageTypeTags.IS_DROWNING)) {
            return !rules.getBoolean(GameRules.RULE_DROWNING_DAMAGE);
        }
        if (source.is(DamageTypeTags.IS_FALL)) {
            return !rules.getBoolean(GameRules.RULE_FALL_DAMAGE);
        }
        if (source.is(DamageTypeTags.IS_FIRE)) {
            return !rules.getBoolean(GameRules.RULE_FIRE_DAMAGE);
        }
        if (source.is(DamageTypeTags.IS_FREEZING)) {
            return !rules.getBoolean(GameRules.RULE_FREEZE_DAMAGE);
        }
        return false;
    }

    /** Drops everything like a player and tells the bridge (which removes the body and respawns it later). **/
    @Override
    public void die(DamageSource source) {
        if (this.isRemoved() || this.dead) {
            return;
        }
        this.dead = true;
        if (!this.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            this.getInventory().dropAll();
        }
        this.onDeath.accept(this, source);
    }
}
