package com.bromax.claudepuppet;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * The super puppet's "connection": never connected to anything. Like NeoForge's FakePlayer handler it discards every
 * packet, but it also answers channel queries with "no" (mods ask before sending their packets, and the FakePlayer
 * handler crashes on that, e.g. Primal Magick's knowledge sync), and it applies teleports locally (the FakePlayer handler
 * ignores them, so teleporting the body wouldn't move it).
 */
public class SuperNetHandler extends ServerGamePacketListenerImpl {
    /** A connection object with no channel behind it; nothing is ever sent through it. **/
    private static final class NullConnection extends Connection {
        NullConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void setListenerForServerboundHandshake(PacketListener listener) {
        }
    }

    public SuperNetHandler(MinecraftServer server, ServerPlayer player) {
        super(server, new NullConnection(), player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
    }

    // No mod channels: mods that check before sending simply skip this player.
    @Override
    public boolean hasChannel(ResourceLocation payloadId) {
        return false;
    }

    // Nothing is ever sent.
    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
    }

    @Override
    public void sendPlayerChatMessage(PlayerChatMessage message, ChatType.Bound boundChatType) {
    }

    @Override
    public void sendDisguisedChatMessage(Component content, ChatType.Bound boundChatType) {
    }

    @Override
    public void addPendingMessage(PlayerChatMessage message) {
    }

    // No client to keep in step with.
    @Override
    public void tick() {
    }

    @Override
    public void resetPosition() {
    }

    @Override
    public void ackBlockChangesUpTo(int sequence) {
    }

    @Override
    public void disconnect(Component message) {
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
    }

    // Teleports move the body directly (normally the client confirms them).
    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        this.player.absMoveTo(x, y, z, yaw, pitch);
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relativeSet) {
        double newX = relativeSet.contains(RelativeMovement.X) ? this.player.getX() + x : x;
        double newY = relativeSet.contains(RelativeMovement.Y) ? this.player.getY() + y : y;
        double newZ = relativeSet.contains(RelativeMovement.Z) ? this.player.getZ() + z : z;
        float newYaw = relativeSet.contains(RelativeMovement.Y_ROT) ? this.player.getYRot() + yaw : yaw;
        float newPitch = relativeSet.contains(RelativeMovement.X_ROT) ? this.player.getXRot() + pitch : pitch;
        this.player.absMoveTo(newX, newY, newZ, newYaw, newPitch);
    }
}
