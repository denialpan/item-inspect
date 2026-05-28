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
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = "iteminspect", dist = Dist.CLIENT)
public class iteminspectClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DROP_VANILLA_FALLBACK_TICKS = 4;
    private static boolean hasLastMainHandStack;
    private static ItemStack lastSelectedStack = ItemStack.EMPTY;
    private static boolean lastBothHandsEmpty;
    private static ItemStack queuedExternalHandoffStack = ItemStack.EMPTY;
    private static boolean queuedExternalHandoffHasMainHand;
    private static ItemStack queuedExternalHandoffOffhandStack = ItemStack.EMPTY;
    private static boolean queuedExternalHandoffAllowsEmptyHands;
    private static int externalHandoffTicks;
    private static int dropVanillaFallbackTicks;
    private static ItemStack lastOffhandStack = ItemStack.EMPTY;
    private static boolean hasLastOffhandStack;
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
            ItemStack offhandStack = minecraft.player.getOffhandItem();
            boolean bothHandsEmpty = selectedStack.isEmpty() && offhandStack.isEmpty();
            selectedStackForTick = selectedStack;
            boolean selectedExternal = isExternalItem(selectedStack);
            boolean offhandExternal = isExternalItem(offhandStack);
            if (minecraft.options.keyDrop.isDown()) {
                dropVanillaFallbackTicks = DROP_VANILLA_FALLBACK_TICKS;
                ViewmodelPose.INSTANCE.cancelAllAnimations();
                clearExternalHandoff();
            }

            boolean mainHandChanged = hasLastMainHandStack && !sameMainHandItem(lastSelectedStack, selectedStack);
            boolean offhandChanged = hasLastOffhandStack && !sameMainHandItem(lastOffhandStack, offhandStack);
            if (!mainHandChanged && !offhandChanged && (selectedExternal || offhandExternal)) {
                ViewmodelPose.INSTANCE.cancelAllAnimations();
                if (selectedExternal && !offhandExternal) {
                    ViewmodelPose.INSTANCE.rememberSettledOffhand(offhandStack);
                }
                clearExternalHandoff();
            }
            if (mainHandChanged) {
                if (dropVanillaFallbackTicks > 0) {
                    ViewmodelPose.INSTANCE.cancelAllAnimations();
                    clearExternalHandoff();
                } else if (externalHandoffTicks > 0 && !selectedExternal) {
                    updateQueuedExternalHandoffMainHand(selectedStack, allowsEmptyMainHand(selectedStack));
                } else {
                    handleMainHandChanged(lastSelectedStack, selectedStack, offhandStack, lastBothHandsEmpty, bothHandsEmpty);
                }
            }
            if (offhandChanged) {
                if (dropVanillaFallbackTicks > 0) {
                    ViewmodelPose.INSTANCE.cancelAllAnimations();
                    clearExternalHandoff();
                } else if (externalHandoffTicks > 0 && !offhandExternal) {
                    updateQueuedExternalHandoffOffhand(offhandStack);
                } else {
                    handleOffhandChanged(lastOffhandStack, offhandStack);
                }
            } else if (!mainHandChanged && lastBothHandsEmpty && !bothHandsEmpty) {
                ViewmodelPose.INSTANCE.cancelAllAnimations();
                clearExternalHandoff();
            }
            hasLastMainHandStack = true;
            hasLastOffhandStack = true;
            lastSelectedStack = selectedStack.copy();
            lastOffhandStack = offhandStack.copy();
            lastBothHandsEmpty = bothHandsEmpty;
            tickExternalHandoff(selectedStack, offhandStack);
            if (minecraft.options.keyAttack.isDown()) {
                ViewmodelPose.INSTANCE.cancelAnimation();
            }
            if (dropVanillaFallbackTicks > 0) {
                dropVanillaFallbackTicks--;
            }
        } else {
            hasLastMainHandStack = false;
            hasLastOffhandStack = false;
            lastSelectedStack = ItemStack.EMPTY;
            lastOffhandStack = ItemStack.EMPTY;
            lastBothHandsEmpty = false;
            selectedStackForTick = ItemStack.EMPTY;
            dropVanillaFallbackTicks = 0;
            clearExternalHandoff();
            ViewmodelPose.INSTANCE.cancelAllAnimations();
        }

        while (PLAY_VIEWMODEL_ANIMATION.consumeClick()) {
            ItemStack selectedStack = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
            ItemStack offhandStack = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getOffhandItem();
            if (!isExternalItem(selectedStack) && !isExternalItem(offhandStack) && externalHandoffTicks <= 0) {
                boolean bothHandsEmpty = minecraft.player != null && selectedStack.isEmpty() && minecraft.player.getOffhandItem().isEmpty();
                ViewmodelPose.INSTANCE.startInspect(selectedStack, offhandStack, bothHandsEmpty);
            }
        }
        ViewmodelPose.INSTANCE.tickAnimation(selectedStackForTick);
    }

    private static void handleMainHandChanged(ItemStack oldStack, ItemStack newStack, ItemStack offhandStack, boolean oldBothHandsEmpty, boolean newBothHandsEmpty) {
        boolean oldExternal = isExternalItem(oldStack);
        boolean newExternal = isExternalItem(newStack);
        if (newExternal) {
            ViewmodelPose.INSTANCE.cancelAllAnimations();
            if (!isExternalItem(offhandStack)) {
                ViewmodelPose.INSTANCE.rememberSettledOffhand(offhandStack);
            }
            clearExternalHandoff();
            return;
        }

        if (oldExternal) {
            ViewmodelPose.INSTANCE.cancelAllAnimations();
            queueExternalHandoff(newStack, offhandStack, allowsEmptyMainHand(newStack), externalHandoffTicksFor(oldStack));
            return;
        }

        clearExternalHandoff();
        ViewmodelPose.INSTANCE.onHotbarChanged(oldStack, newStack, allowsEmptyMainHand(oldStack), allowsEmptyMainHand(newStack));
    }

    private static void handleOffhandChanged(ItemStack oldStack, ItemStack newStack) {
        if (isExternalItem(oldStack) || isExternalItem(newStack)) {
            ViewmodelPose.INSTANCE.cancelAllAnimations();
            clearExternalHandoff();
            return;
        }

        ViewmodelPose.INSTANCE.onOffhandChanged(oldStack, newStack);
    }

    private static boolean sameMainHandItem(ItemStack oldStack, ItemStack newStack) {
        if (oldStack.isEmpty() || newStack.isEmpty()) {
            return oldStack.isEmpty() == newStack.isEmpty();
        }

        return ItemStack.isSameItemSameComponents(oldStack, newStack);
    }

    private static boolean allowsEmptyMainHand(ItemStack stack) {
        return stack.isEmpty();
    }

    private static void queueExternalHandoff(ItemStack stack, ItemStack offhandStack, boolean allowEmptyHands, int handoffTicks) {
        boolean queueMainHand = ViewmodelPose.INSTANCE.hasProfileFor(stack, allowEmptyHands);
        boolean queueOffhand = !offhandStack.isEmpty() && !isExternalItem(offhandStack) && ViewmodelPose.INSTANCE.hasProfileFor(offhandStack, false);
        if (!queueMainHand && !queueOffhand) {
            clearExternalHandoff();
            return;
        }

        if (handoffTicks <= 0) {
            clearExternalHandoff();
            if (queueMainHand) {
                ViewmodelPose.INSTANCE.startMainHandPullout(stack, allowEmptyHands);
            }
            if (queueOffhand) {
                ViewmodelPose.INSTANCE.startOffhandPullout(offhandStack);
            }
            return;
        }

        queuedExternalHandoffStack = queueMainHand ? stack.copy() : ItemStack.EMPTY;
        queuedExternalHandoffHasMainHand = queueMainHand;
        queuedExternalHandoffOffhandStack = queueOffhand ? offhandStack.copy() : ItemStack.EMPTY;
        queuedExternalHandoffAllowsEmptyHands = allowEmptyHands;
        externalHandoffTicks = handoffTicks;
    }

    private static void updateQueuedExternalHandoffMainHand(ItemStack stack, boolean allowEmptyHands) {
        boolean queueMainHand = ViewmodelPose.INSTANCE.hasProfileFor(stack, allowEmptyHands);
        queuedExternalHandoffStack = queueMainHand ? stack.copy() : ItemStack.EMPTY;
        queuedExternalHandoffHasMainHand = queueMainHand;
        queuedExternalHandoffAllowsEmptyHands = allowEmptyHands;
    }

    private static void updateQueuedExternalHandoffOffhand(ItemStack stack) {
        queuedExternalHandoffOffhandStack = !stack.isEmpty() && !isExternalItem(stack) && ViewmodelPose.INSTANCE.hasProfileFor(stack, false)
                ? stack.copy()
                : ItemStack.EMPTY;
    }

    private static int externalHandoffTicksFor(ItemStack oldStack) {
        if (!isExternalItem(oldStack)) {
            return 0;
        }

        try {
            Object renderer = IClientItemExtensions.of(oldStack.getItem()).getCustomRenderer();
            if (renderer == null) {
                return 0;
            }

            Method getPutAwayTime = renderer.getClass().getMethod("getPutAwayTime", ItemStack.class);
            Object putAwayTime = getPutAwayTime.invoke(renderer, oldStack);
            if (!(putAwayTime instanceof Number number)) {
                return 0;
            }

            long putAwayMilliseconds = number.longValue();
            if (putAwayMilliseconds <= 0L) {
                LOGGER.info("TACZ handoff putaway delay for {} was {} ms; using instant handoff", BuiltInRegistries.ITEM.getKey(oldStack.getItem()), putAwayMilliseconds);
                return 0;
            }
            int putAwayTicks = Math.max(1, (int)Math.ceil(putAwayMilliseconds / 50.0D));
            LOGGER.info("TACZ handoff putaway delay for {} is {} ms -> {} ticks", BuiltInRegistries.ITEM.getKey(oldStack.getItem()), putAwayMilliseconds, putAwayTicks);
            return putAwayTicks;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.info("TACZ handoff putaway delay for {} could not be read; using instant handoff", BuiltInRegistries.ITEM.getKey(oldStack.getItem()), exception);
            return 0;
        }
    }

    private static void tickExternalHandoff(ItemStack selectedStack, ItemStack offhandStack) {
        if (externalHandoffTicks <= 0) {
            return;
        }

        boolean mainHandMismatch = queuedExternalHandoffHasMainHand && !ItemStack.matches(queuedExternalHandoffStack, selectedStack);
        boolean offhandMismatch = !queuedExternalHandoffOffhandStack.isEmpty() && !ItemStack.matches(queuedExternalHandoffOffhandStack, offhandStack);
        if (isExternalItem(selectedStack) || mainHandMismatch || offhandMismatch
                || (queuedExternalHandoffHasMainHand && queuedExternalHandoffAllowsEmptyHands != allowsEmptyMainHand(selectedStack))) {
            clearExternalHandoff();
            return;
        }

        externalHandoffTicks--;
        if (externalHandoffTicks == 0) {
            if (queuedExternalHandoffHasMainHand) {
                ViewmodelPose.INSTANCE.startMainHandPullout(selectedStack, queuedExternalHandoffAllowsEmptyHands);
            }
            if (!queuedExternalHandoffOffhandStack.isEmpty()) {
                ViewmodelPose.INSTANCE.startOffhandPullout(queuedExternalHandoffOffhandStack);
            }
            queuedExternalHandoffStack = ItemStack.EMPTY;
            queuedExternalHandoffHasMainHand = false;
            queuedExternalHandoffOffhandStack = ItemStack.EMPTY;
            queuedExternalHandoffAllowsEmptyHands = false;
        }
    }

    private static void clearExternalHandoff() {
        queuedExternalHandoffStack = ItemStack.EMPTY;
        queuedExternalHandoffHasMainHand = false;
        queuedExternalHandoffOffhandStack = ItemStack.EMPTY;
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
