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

    public static void init() {
        PuppetBridge.SNAPSHOT = PuppetClient::requestSnapshot;
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

    private static void onRenderFramePre(RenderFrameEvent.Pre event) {
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
