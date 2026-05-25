package com.ddd.iteminspect;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
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
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = "iteminspect", dist = Dist.CLIENT)
public class iteminspectClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int EXTERNAL_HANDOFF_TICKS = 8;
    private static final int DROP_VANILLA_FALLBACK_TICKS = 4;
    private static boolean hasLastMainHandStack;
    private static ItemStack lastSelectedStack = ItemStack.EMPTY;
    private static boolean lastBothHandsEmpty;
    private static ItemStack queuedExternalHandoffStack = ItemStack.EMPTY;
    private static boolean queuedExternalHandoffAllowsEmptyHands;
    private static int externalHandoffTicks;
    private static int dropVanillaFallbackTicks;
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
        LOGGER.info("HELLO FROM CLIENT SETUP");
        LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(ViewmodelPose.INSTANCE);
    }

    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PLAY_VIEWMODEL_ANIMATION);
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack selectedStackForTick = ItemStack.EMPTY;
        if (minecraft.player != null) {
            ItemStack selectedStack = minecraft.player.getMainHandItem();
            boolean bothHandsEmpty = selectedStack.isEmpty() && minecraft.player.getOffhandItem().isEmpty();
            selectedStackForTick = selectedStack;
            if (minecraft.options.keyDrop.isDown()) {
                dropVanillaFallbackTicks = DROP_VANILLA_FALLBACK_TICKS;
                ViewmodelPose.INSTANCE.cancelAllAnimations();
                clearExternalHandoff();
            }

            if (hasLastMainHandStack && !sameMainHandItem(lastSelectedStack, selectedStack)) {
                if (dropVanillaFallbackTicks > 0) {
                    ViewmodelPose.INSTANCE.cancelAllAnimations();
                    clearExternalHandoff();
                } else {
                    handleMainHandChanged(lastSelectedStack, selectedStack, lastBothHandsEmpty, bothHandsEmpty);
                }
            } else if (lastBothHandsEmpty && !bothHandsEmpty) {
                ViewmodelPose.INSTANCE.cancelAllAnimations();
                clearExternalHandoff();
            }
            hasLastMainHandStack = true;
            lastSelectedStack = selectedStack.copy();
            lastBothHandsEmpty = bothHandsEmpty;
            tickExternalHandoff(selectedStack, bothHandsEmpty);
            if (minecraft.options.keyAttack.isDown()) {
                ViewmodelPose.INSTANCE.cancelAnimation();
            }
            if (dropVanillaFallbackTicks > 0) {
                dropVanillaFallbackTicks--;
            }
        } else {
            hasLastMainHandStack = false;
            lastSelectedStack = ItemStack.EMPTY;
            lastBothHandsEmpty = false;
            selectedStackForTick = ItemStack.EMPTY;
            dropVanillaFallbackTicks = 0;
            clearExternalHandoff();
            ViewmodelPose.INSTANCE.cancelAllAnimations();
        }

        while (PLAY_VIEWMODEL_ANIMATION.consumeClick()) {
            ItemStack selectedStack = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
            if (!isExternalItem(selectedStack) && externalHandoffTicks <= 0) {
                boolean bothHandsEmpty = minecraft.player != null && selectedStack.isEmpty() && minecraft.player.getOffhandItem().isEmpty();
                ViewmodelPose.INSTANCE.startInspect(selectedStack, bothHandsEmpty);
            }
        }
        ViewmodelPose.INSTANCE.tickAnimation(selectedStackForTick);
    }

    private static void handleMainHandChanged(ItemStack oldStack, ItemStack newStack, boolean oldBothHandsEmpty, boolean newBothHandsEmpty) {
        boolean oldExternal = isExternalItem(oldStack);
        boolean newExternal = isExternalItem(newStack);
        if (newExternal) {
            ViewmodelPose.INSTANCE.cancelAllAnimations();
            clearExternalHandoff();
            return;
        }

        if (oldExternal) {
            ViewmodelPose.INSTANCE.cancelAllAnimations();
            queueExternalHandoff(newStack, newBothHandsEmpty);
            return;
        }

        clearExternalHandoff();
        ViewmodelPose.INSTANCE.onHotbarChanged(oldStack, newStack, oldBothHandsEmpty, newBothHandsEmpty);
    }

    private static boolean sameMainHandItem(ItemStack oldStack, ItemStack newStack) {
        if (oldStack.isEmpty() || newStack.isEmpty()) {
            return oldStack.isEmpty() == newStack.isEmpty();
        }

        return ItemStack.isSameItemSameComponents(oldStack, newStack);
    }

    private static void queueExternalHandoff(ItemStack stack, boolean allowEmptyHands) {
        if (!ViewmodelPose.INSTANCE.hasProfileFor(stack, allowEmptyHands)) {
            clearExternalHandoff();
            return;
        }

        queuedExternalHandoffStack = stack.copy();
        queuedExternalHandoffAllowsEmptyHands = allowEmptyHands;
        externalHandoffTicks = EXTERNAL_HANDOFF_TICKS;
    }

    private static void tickExternalHandoff(ItemStack selectedStack, boolean bothHandsEmpty) {
        if (externalHandoffTicks <= 0) {
            return;
        }

        if (isExternalItem(selectedStack) || queuedExternalHandoffAllowsEmptyHands != bothHandsEmpty || !ItemStack.matches(queuedExternalHandoffStack, selectedStack)) {
            clearExternalHandoff();
            return;
        }

        externalHandoffTicks--;
        if (externalHandoffTicks == 0) {
            ViewmodelPose.INSTANCE.startPullout(selectedStack, bothHandsEmpty);
            queuedExternalHandoffStack = ItemStack.EMPTY;
            queuedExternalHandoffAllowsEmptyHands = false;
        }
    }

    private static void clearExternalHandoff() {
        queuedExternalHandoffStack = ItemStack.EMPTY;
        queuedExternalHandoffAllowsEmptyHands = false;
        externalHandoffTicks = 0;
    }

    private static boolean isExternalItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "tacz".equals(itemId.getNamespace()) && !ViewmodelPose.INSTANCE.hasSpecificProfileFor(stack);
    }
}
