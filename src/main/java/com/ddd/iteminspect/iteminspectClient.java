package com.ddd.iteminspect;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = iteminspect.MODID, dist = Dist.CLIENT)
public class iteminspectClient {
    private static int lastSelectedHotbarSlot = -1;
    private static final KeyMapping PLAY_VIEWMODEL_ANIMATION = new KeyMapping(
            "key.iteminspect.play_viewmodel_animation",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.iteminspect"
    );

    public iteminspectClient(IEventBus modEventBus, ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(iteminspectClient::onClientSetup);
        modEventBus.addListener(iteminspectClient::onRegisterReloadListeners);
        modEventBus.addListener(iteminspectClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.register(ViewmodelRenderer.class);
        NeoForge.EVENT_BUS.addListener(iteminspectClient::onClientTick);
    }

    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        iteminspect.LOGGER.info("HELLO FROM CLIENT SETUP");
        iteminspect.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(ViewmodelPose.INSTANCE);
    }

    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PLAY_VIEWMODEL_ANIMATION);
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            int selectedHotbarSlot = minecraft.player.getInventory().selected;
            if (lastSelectedHotbarSlot != -1 && selectedHotbarSlot != lastSelectedHotbarSlot) {
                ViewmodelPose.INSTANCE.cancelAnimation();
            }
            lastSelectedHotbarSlot = selectedHotbarSlot;
        }

        if (minecraft.options.keyAttack.isDown() || minecraft.options.keyUse.isDown()) {
            ViewmodelPose.INSTANCE.cancelAnimation();
            return;
        }

        while (PLAY_VIEWMODEL_ANIMATION.consumeClick()) {
            ViewmodelPose.INSTANCE.restartAnimation();
        }
        ViewmodelPose.INSTANCE.tickAnimation();
    }
}
