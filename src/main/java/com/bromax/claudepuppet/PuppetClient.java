package com.bromax.claudepuppet;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Client side: renders Claude's Puppet with the player model and the robot skin (make_skin.py), and takes the
 * puppet's-eye snapshots: for a couple of frames the camera is moved to the puppet (first person, HUD hidden), then the
 * frame is shrunk to a small PNG for Claude and the camera goes back.
 */
public class PuppetClient {
    private static final ResourceLocation SKIN = ResourceLocation.fromNamespaceAndPath(ClaudePuppet.MODID, "textures/entity/robot.png");

    // Snapshot in progress (client thread only):
    private static int snapshotEntityId = -1;
    private static Path snapshotPath;
    private static int snapshotWidth;
    private static Consumer<String> snapshotDone;
    private static int snapshotFrames = 0;
    private static Entity previousCamera;
    private static CameraType previousCameraType;
    private static boolean previousHideGui;

    // Flipbook in progress: one frame every intervalMs, stitched into a grid when done.
    private static int sequenceEntityId = -1;
    private static Path sequencePath;
    private static int sequenceTotal;
    private static int sequenceWidth;
    private static long sequenceIntervalMs;
    private static long sequenceNextAt;
    private static Consumer<String> sequenceDone;
    private static final java.util.List<NativeImage> sequenceFrames = new java.util.ArrayList<>();
    private static boolean captureForSequence = false;

    // Super puppet skin: a PlayerRenderer with the robot texture, drawn in place of the normal one for its UUID.
    private static net.minecraft.client.renderer.entity.player.PlayerRenderer superRenderer;
    private static boolean renderingSuper = false;

    public static void captureContext(EntityRenderersEvent.AddLayers event) {
        superRenderer = new net.minecraft.client.renderer.entity.player.PlayerRenderer(event.getContext(), false) {
            @Override
            public ResourceLocation getTextureLocation(net.minecraft.client.player.AbstractClientPlayer player) {
                return SKIN;
            }
        };
    }

    private static void onRenderPlayer(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre event) {
        if (renderingSuper || superRenderer == null || !event.getEntity().getUUID().equals(SuperPuppet.PROFILE.getId())) {
            return;
        }
        event.setCanceled(true);
        renderingSuper = true;
        try {
            var player = (net.minecraft.client.player.AbstractClientPlayer) event.getEntity();
            float yaw = net.minecraft.util.Mth.lerp(event.getPartialTick(), player.yRotO, player.getYRot());
            superRenderer.render(player, yaw, event.getPartialTick(), event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
        } finally {
            renderingSuper = false;
        }
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PuppetClient::onRenderPlayer);
        PuppetBridge.SNAPSHOT = PuppetClient::requestSnapshot;
        PuppetBridge.FLIPBOOK = PuppetClient::requestFlipbook;
        NeoForge.EVENT_BUS.addListener(PuppetClient::onRenderFramePre);
        NeoForge.EVENT_BUS.addListener(PuppetClient::onRenderFramePost);
    }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ClaudePuppet.PUPPET.get(), PuppetRenderer::new);
    }

    /** Called from the integrated server thread; hands the work to the client thread. **/
    public static void requestSnapshot(int entityId, Path path, int width, Consumer<String> done) {
        Minecraft.getInstance().execute(() -> {
            if (snapshotEntityId >= 0) {
                done.accept("FAIL snapshot: one is already being taken");
                return;
            }
            snapshotEntityId = entityId;
            snapshotPath = path;
            snapshotWidth = width <= 0 ? 0 : Math.max(32, Math.min(4096, width)); // 0 = full window resolution
            snapshotDone = done;
            snapshotFrames = 0;
        });
    }

    /** Called from the integrated server thread: frames every intervalMs, stitched into one grid image at path. **/
    public static void requestFlipbook(int entityId, Path path, int frames, int width, long intervalMs, Consumer<String> done) {
        Minecraft.getInstance().execute(() -> {
            if (sequenceEntityId >= 0) {
                done.accept("FAIL watch: already watching");
                return;
            }
            sequenceEntityId = entityId;
            sequencePath = path;
            sequenceTotal = Math.max(1, Math.min(40, frames));
            sequenceWidth = Math.max(32, Math.min(512, width));
            sequenceIntervalMs = intervalMs;
            sequenceNextAt = 0;
            sequenceDone = done;
            sequenceFrames.forEach(NativeImage::close);
            sequenceFrames.clear();
        });
    }

    private static void onRenderFramePre(RenderFrameEvent.Pre event) {
        // Start the next flipbook frame when it's due and no single snapshot is running.
        if (sequenceEntityId >= 0 && snapshotEntityId < 0 && System.currentTimeMillis() >= sequenceNextAt) {
            snapshotEntityId = sequenceEntityId;
            snapshotWidth = sequenceWidth;
            snapshotFrames = 0;
            captureForSequence = true;
            snapshotDone = result -> {
                if (result.startsWith("FAIL")) {
                    finishSequence(result);
                }
            };
        }
        if (snapshotEntityId < 0 || snapshotFrames > 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Entity puppet = mc.level != null ? mc.level.getEntity(snapshotEntityId) : null;
        if (puppet == null) {
            finish("FAIL snapshot: the puppet isn't loaded on your client (too far away?)");
            return;
        }
        previousCamera = mc.getCameraEntity();
        previousCameraType = mc.options.getCameraType();
        previousHideGui = mc.options.hideGui;
        mc.setCameraEntity(puppet);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.hideGui = true;
        snapshotFrames = 1;
    }

    private static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (snapshotEntityId < 0 || snapshotFrames == 0) {
            return;
        }
        // Let one full frame render from the puppet's eyes before grabbing the second.
        if (snapshotFrames++ < 2) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String result;
        if (captureForSequence) {
            captureForSequence = false;
            try (NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int height = Math.max(1, Math.round(snapshotWidth * (float) full.getHeight() / full.getWidth()));
                NativeImage small = new NativeImage(snapshotWidth, height, false);
                full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
                sequenceFrames.add(small);
            } catch (Exception e) {
                restoreCamera(mc);
                finish("FAIL watch: " + e);
                return;
            }
            restoreCamera(mc);
            finish("frame");
            sequenceNextAt = System.currentTimeMillis() + sequenceIntervalMs;
            if (sequenceFrames.size() >= sequenceTotal) {
                finishSequence(null);
            }
            return;
        }
        try (NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            if (snapshotWidth <= 0 || snapshotWidth >= full.getWidth()) {
                full.writeToFile(snapshotPath);
                result = "SNAPSHOT " + snapshotPath + " (" + full.getWidth() + "x" + full.getHeight() + ", full)";
            } else {
                int height = Math.max(1, Math.round(snapshotWidth * (float) full.getHeight() / full.getWidth()));
                try (NativeImage small = new NativeImage(snapshotWidth, height, false)) {
                    full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
                    small.writeToFile(snapshotPath);
                }
                result = "SNAPSHOT " + snapshotPath + " (" + snapshotWidth + "x" + height + ")";
            }
        } catch (Exception e) {
            result = "FAIL snapshot: " + e;
        }
        restoreCamera(mc);
        finish(result);
    }

    /** Stitches the flipbook frames into a grid (5 per row, left to right, top to bottom) with 2px gaps. **/
    private static void finishSequence(String failure) {
        Consumer<String> done = sequenceDone;
        String result = failure;
        if (result == null && !sequenceFrames.isEmpty()) {
            int frameW = sequenceFrames.get(0).getWidth();
            int frameH = sequenceFrames.get(0).getHeight();
            int cols = Math.min(5, sequenceFrames.size());
            int rows = (sequenceFrames.size() + cols - 1) / cols;
            int gap = 2;
            try (NativeImage grid = new NativeImage(cols * frameW + (cols - 1) * gap, rows * frameH + (rows - 1) * gap, true)) {
                for (int i = 0; i < sequenceFrames.size(); i++) {
                    NativeImage frame = sequenceFrames.get(i);
                    int ox = (i % cols) * (frameW + gap);
                    int oy = (i / cols) * (frameH + gap);
                    for (int y = 0; y < frameH; y++) {
                        for (int x = 0; x < frameW; x++) {
                            grid.setPixelRGBA(ox + x, oy + y, frame.getPixelRGBA(x, y));
                        }
                    }
                }
                grid.writeToFile(sequencePath);
                result = "SNAPSHOT " + sequencePath + " (flipbook: " + sequenceFrames.size() + " frames of " + frameW + "x" + frameH + ", " + (sequenceIntervalMs / 1000.0) + "s apart, left to right then top to bottom)";
            } catch (Exception e) {
                result = "FAIL watch: " + e;
            }
        }
        sequenceFrames.forEach(NativeImage::close);
        sequenceFrames.clear();
        sequenceEntityId = -1;
        if (done != null) {
            done.accept(result);
        }
    }

    private static void restoreCamera(Minecraft mc) {
        mc.setCameraEntity(previousCamera != null ? previousCamera : mc.player);
        if (previousCameraType != null) {
            mc.options.setCameraType(previousCameraType);
        }
        mc.options.hideGui = previousHideGui;
    }

    private static void finish(String result) {
        Consumer<String> done = snapshotDone;
        snapshotEntityId = -1;
        snapshotFrames = 0;
        snapshotDone = null;
        if (done != null) {
            done.accept(result);
        }
    }

    public static class PuppetRenderer extends HumanoidMobRenderer<PuppetEntity, PlayerModel<PuppetEntity>> {
        public PuppetRenderer(EntityRendererProvider.Context context) {
            super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
            this.addLayer(new HumanoidArmorLayer<>(this,
                    new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                    context.getModelManager()));
        }

        @Override
        public ResourceLocation getTextureLocation(PuppetEntity entity) {
            return SKIN;
        }
    }
}
