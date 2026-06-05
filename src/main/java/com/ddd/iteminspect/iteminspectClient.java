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
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
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
    private static final int EXTERNAL_HANDOFF_PADDING_TICKS = 0;
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
        NeoForge.EVENT_BUS.addListener(iteminspectClient::onClientPlayerLoggingIn);
        NeoForge.EVENT_BUS.addListener(iteminspectClient::onClientPlayerLoggingOut);
    }

    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        LOGGER.info("HELLO FROM CLIENT SETUP");
        LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        resetClientAnimationState();
    }

    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(ViewmodelPose.INSTANCE);
    }

    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PLAY_VIEWMODEL_ANIMATION);
    }

    public static boolean shouldSuppressVanillaHandsForExternalHandoff() {
        return externalHandoffTicks > 0;
    }

    static void onClientPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        resetClientAnimationStateFromPlayer(event.getPlayer());
    }

    static void onClientPlayerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetClientAnimationState();
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
            boolean handledBothHandChange = false;
            if (mainHandChanged && offhandChanged) {
                boolean previousMainExternal = isExternalItem(lastSelectedStack);
                boolean previousOffhandExternal = isExternalItem(lastOffhandStack);
                if (dropVanillaFallbackTicks > 0) {
                    ViewmodelPose.INSTANCE.cancelAllAnimations();
                    clearExternalHandoff();
                    handledBothHandChange = true;
                } else if (externalHandoffTicks > 0 && !selectedExternal && !offhandExternal) {
                    updateQueuedExternalHandoffMainHand(selectedStack, allowsEmptyMainHand(selectedStack));
                    updateQueuedExternalHandoffOffhand(offhandStack);
                    handledBothHandChange = true;
                } else if (!selectedExternal && !offhandExternal && !previousMainExternal && !previousOffhandExternal) {
                    clearExternalHandoff();
                    ViewmodelPose.INSTANCE.onBothHandsChanged(
                            lastSelectedStack,
                            selectedStack,
                            allowsEmptyMainHand(lastSelectedStack),
                            allowsEmptyMainHand(selectedStack),
                            lastOffhandStack,
                            offhandStack
                    );
                    handledBothHandChange = true;
                }
            }
            if (mainHandChanged && !handledBothHandChange) {
                if (dropVanillaFallbackTicks > 0) {
                    ViewmodelPose.INSTANCE.cancelAllAnimations();
                    clearExternalHandoff();
                } else if (externalHandoffTicks > 0 && !selectedExternal) {
                    updateQueuedExternalHandoffMainHand(selectedStack, allowsEmptyMainHand(selectedStack));
                } else {
                    handleMainHandChanged(lastSelectedStack, selectedStack, offhandStack, lastBothHandsEmpty, bothHandsEmpty);
                }
            }
            if (offhandChanged && !handledBothHandChange) {
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
                ViewmodelPose.INSTANCE.cancelAnimationForAttack();
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
            resetClientAnimationState();
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
        boolean queueOffhand = !offhandStack.isEmpty() && !isExternalItem(offhandStack) && ViewmodelPose.INSTANCE.hasOffhandProfileFor(offhandStack);
        if (!queueMainHand && !queueOffhand) {
            clearExternalHandoff();
            return;
        }

        if (handoffTicks <= 0) {
            clearExternalHandoff();
            if (queueMainHand) {
                ViewmodelPose.INSTANCE.startMainHandExternalPullout(stack, allowEmptyHands);
            }
            if (queueOffhand) {
                ViewmodelPose.INSTANCE.startOffhandExternalPullout(offhandStack);
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
        queuedExternalHandoffOffhandStack = !stack.isEmpty() && !isExternalItem(stack) && ViewmodelPose.INSTANCE.hasOffhandProfileFor(stack)
                ? stack.copy()
                : ItemStack.EMPTY;
    }

    private static int externalHandoffTicksFor(ItemStack oldStack) {
        if (!isExternalItem(oldStack)) {
            return 0;
        }

        int putAwayTicks = readExternalPutawayTicks(oldStack);
        if (putAwayTicks <= 0) {
            LOGGER.info("TACZ handoff putaway delay for {} could not be read; using instant handoff", BuiltInRegistries.ITEM.getKey(oldStack.getItem()));
            return 0;
        }

        int paddedTicks = putAwayTicks + EXTERNAL_HANDOFF_PADDING_TICKS;
        LOGGER.info("TACZ handoff putaway delay for {} is {} ticks + {} padding ticks -> {} ticks",
                BuiltInRegistries.ITEM.getKey(oldStack.getItem()),
                putAwayTicks,
                EXTERNAL_HANDOFF_PADDING_TICKS,
                paddedTicks);
        return paddedTicks;
    }

    private static int readExternalPutawayTicks(ItemStack oldStack) {
        try {
            Object renderer = IClientItemExtensions.of(oldStack.getItem()).getCustomRenderer();
            int rendererTicks = readPutawayTicksFromTarget(renderer, oldStack);
            if (rendererTicks > 0) {
                return rendererTicks;
            }

            return readPutawayTicksFromTarget(oldStack.getItem(), oldStack);
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to inspect TACZ putaway delay for {}", BuiltInRegistries.ITEM.getKey(oldStack.getItem()), exception);
            return 0;
        }
    }

    private static int readPutawayTicksFromTarget(Object target, ItemStack stack) {
        if (target == null) {
            return 0;
        }

        String[] methodNames = {
                "getPutAwayTicks",
                "getPutawayTicks",
                "getPutAwayTick",
                "getPutawayTick",
                "getPutAwayTime",
                "getPutawayTime",
                "getPutAwayDuration",
                "getPutawayDuration",
                "getPutAwayMilliseconds",
                "getPutawayMilliseconds",
                "getPutAwayMs",
                "getPutawayMs"
        };

        for (String methodName : methodNames) {
            int ticks = readPutawayTicksFromMethod(target, methodName, stack);
            if (ticks > 0) {
                return ticks;
            }
        }

        return 0;
    }

    private static int readPutawayTicksFromMethod(Object target, String methodName, ItemStack stack) {
        Object value = invokePutawayMethod(target, methodName, stack, "put_away");
        if (!(value instanceof Number number)) {
            value = invokePutawayMethod(target, methodName, stack, "putAway");
        }
        if (!(value instanceof Number number)) {
            value = invokePutawayMethod(target, methodName, stack);
        }
        if (!(value instanceof Number number)) {
            value = invokePutawayMethod(target, methodName, "put_away");
        }
        if (!(value instanceof Number number)) {
            value = invokePutawayMethod(target, methodName);
        }
        if (!(value instanceof Number number)) {
            return 0;
        }

        double duration = number.doubleValue();
        if (duration <= 0.0D) {
            return 0;
        }

        String lowerName = methodName.toLowerCase();
        if (lowerName.contains("tick")) {
            return Math.max(1, (int)Math.ceil(duration));
        }

        return Math.max(1, (int)Math.ceil(duration / 50.0D));
    }

    private static Object invokePutawayMethod(Object target, String methodName, Object... args) {
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            Object arg = args[index];
            if (arg instanceof ItemStack) {
                parameterTypes[index] = ItemStack.class;
            } else if (arg instanceof String) {
                parameterTypes[index] = String.class;
            } else {
                parameterTypes[index] = arg.getClass();
            }
        }

        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
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
                ViewmodelPose.INSTANCE.startMainHandExternalPullout(selectedStack, queuedExternalHandoffAllowsEmptyHands);
            }
            if (!queuedExternalHandoffOffhandStack.isEmpty()) {
                ViewmodelPose.INSTANCE.startOffhandExternalPullout(queuedExternalHandoffOffhandStack);
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

    private static void resetClientAnimationState() {
        hasLastMainHandStack = false;
        hasLastOffhandStack = false;
        lastSelectedStack = ItemStack.EMPTY;
        lastOffhandStack = ItemStack.EMPTY;
        lastBothHandsEmpty = false;
        dropVanillaFallbackTicks = 0;
        clearExternalHandoff();
        ViewmodelPose.INSTANCE.cancelAllAnimations();
    }

    private static void resetClientAnimationStateFromPlayer(net.minecraft.client.player.LocalPlayer player) {
        dropVanillaFallbackTicks = 0;
        clearExternalHandoff();
        ViewmodelPose.INSTANCE.cancelAllAnimations();
        if (player == null) {
            hasLastMainHandStack = false;
            hasLastOffhandStack = false;
            lastSelectedStack = ItemStack.EMPTY;
            lastOffhandStack = ItemStack.EMPTY;
            lastBothHandsEmpty = false;
            return;
        }

        lastSelectedStack = player.getMainHandItem().copy();
        lastOffhandStack = player.getOffhandItem().copy();
        lastBothHandsEmpty = lastSelectedStack.isEmpty() && lastOffhandStack.isEmpty();
        hasLastMainHandStack = true;
        hasLastOffhandStack = true;
    }

    private static boolean isExternalItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "tacz".equals(itemId.getNamespace()) && !ViewmodelPose.INSTANCE.hasSpecificProfileFor(stack);
    }
}
