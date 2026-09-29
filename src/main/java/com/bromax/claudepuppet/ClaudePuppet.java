package com.bromax.claudepuppet;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Claude Puppet: a singleplayer bridge that lets Claude (the AI assistant, working through files on disk) take part in
 * a world. Run {@code /claudepuppet} in a singleplayer world (no cheats needed) to tune Claude in: Claude can then read
 * chat, talk as {@code <Claude>}, run commands and control a mock player mob, "Claude's Puppet".
 *
 * Everything goes through {@code <game folder>/claudepuppet/}: Claude appends commands to {@code in.txt} and reads
 * {@code state.json} (rewritten every second) and {@code events.log}. See {@link PuppetBridge} for the command list.
 */
@Mod(ClaudePuppet.MODID)
public class ClaudePuppet {
    public static final String MODID = "claudepuppet";

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<PuppetEntity>> PUPPET = ENTITY_TYPES.register("puppet",
            () -> EntityType.Builder.of(PuppetEntity::new, MobCategory.MISC).sized(0.6F, 1.8F).eyeHeight(1.62F).clientTrackingRange(10).build("puppet"));

    public ClaudePuppet(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener((EntityAttributeCreationEvent event) -> event.put(PUPPET.get(), PuppetEntity.createAttributes().build()));

        PuppetBridge bridge = PuppetBridge.INSTANCE;
        NeoForge.EVENT_BUS.addListener(bridge::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(bridge::onServerTick);
        NeoForge.EVENT_BUS.addListener(bridge::onChat);
        NeoForge.EVENT_BUS.addListener(bridge::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(bridge::onPlayerHurt);
        NeoForge.EVENT_BUS.addListener(bridge::onChangedDimension);
        NeoForge.EVENT_BUS.addListener(bridge::onAdvancement);
        NeoForge.EVENT_BUS.addListener(bridge::onServerStopping);

        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(PuppetClient::registerRenderers);
            PuppetClient.init();
        }
    }
}
