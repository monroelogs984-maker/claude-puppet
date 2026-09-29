package com.bromax.claudepuppet;

import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client side: renders Claude's Puppet with the player model and the robot skin (make_skin.py). **/
public class PuppetClient {
    private static final ResourceLocation SKIN = ResourceLocation.fromNamespaceAndPath(ClaudePuppet.MODID, "textures/entity/robot.png");

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ClaudePuppet.PUPPET.get(), PuppetRenderer::new);
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
