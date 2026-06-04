package com.ddd.iteminspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

public final class ViewmodelPose implements ResourceManagerReloadListener {
    public static final ViewmodelPose INSTANCE = new ViewmodelPose();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MODID = "iteminspect";
    private static final ResourceLocation PROFILE_INDEX_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/profiles.json");
    private static final int ANIMATION_FPS = 60;
    private static final int RESTART_BLEND_TICKS = 4;
    private static final int EQUIP_BLEND_WINDOW_TICKS = 6;

    private Transform viewmodelCamera = Transform.identity();
    private Transform itemRoot = Transform.identity();
    private Transform itemOffhandRoot = Transform.identity();
    private Transform blockRoot = Transform.identity();
    private Transform blockOffhandRoot = Transform.identity();
    private Transform viewmodelArmR = Transform.identity();
    private Transform viewmodelArmL = Transform.identity();
    private final ProfileRegistry profileRegistry = new ProfileRegistry();
    private final CameraLayer cameraLayer = new CameraLayer();
    private final InspectPlayback inspectPlayback = new InspectPlayback();
    private final HandLayer mainHandLayer = new HandLayer(HandLayerSide.MAIN);
    private final HandLayer offhandLayer = new HandLayer(HandLayerSide.OFFHAND);
    private ProfileReference activeProfileId;
    private State state = State.IDLE;
    private ItemStack visualStack = ItemStack.EMPTY;
    private ItemStack visualOffhandStack = ItemStack.EMPTY;
    private final EnumMap<Bone, Transform> pendingInspectRestartBlend = new EnumMap<>(Bone.class);
    private int equipBlendWindowTick;
    private int offhandEquipBlendWindowTick;
    private boolean renderEmptyOffhandArm;
    private boolean loaded;

    private ViewmodelPose() {
    }

    public Transform viewmodelCamera(float partialTick) {
        return this.cameraLayer.currentTransform(this.viewmodelCamera, partialTick);
    }

    public Transform itemRoot() {
        return this.itemRoot(0.0F);
    }

    public Transform itemRoot(float partialTick) {
        return this.currentTransform(Bone.ITEM_ROOT, this.itemRoot, partialTick);
    }

    public Transform blockRoot() {
        return this.blockRoot(0.0F);
    }

    public Transform blockRoot(float partialTick) {
        return this.currentTransform(Bone.BLOCK_ROOT, this.blockRoot, partialTick);
    }

    public Transform viewmodelArmR() {
        return this.viewmodelArmR(0.0F);
    }

    public Transform viewmodelArmR(float partialTick) {
        return this.currentTransform(Bone.VIEWMODEL_ARM_R, this.viewmodelArmR, partialTick);
    }

    public Transform leftItemRoot(float partialTick) {
        return this.currentTransform(Bone.ITEM_OFFHAND_ROOT, this.itemOffhandRoot, partialTick);
    }

    public Transform leftBlockRoot(float partialTick) {
        return this.currentTransform(Bone.BLOCK_OFFHAND_ROOT, this.blockOffhandRoot, partialTick);
    }

    public Transform leftViewmodelArmR(float partialTick) {
        return this.viewmodelArmL(partialTick);
    }

    public Transform viewmodelArmL() {
        return this.viewmodelArmL(0.0F);
    }

    public Transform viewmodelArmL(float partialTick) {
        return this.currentTransform(Bone.VIEWMODEL_ARM_L, this.viewmodelArmL, partialTick);
    }

    public boolean isLoaded() {
        return this.loaded;
    }

    public boolean isPlaying() {
        return this.inspectPlayback.isActive() || this.cameraLayer.isActive() || this.mainHandLayer.isActive() || this.offhandLayer.isActive();
    }

    public boolean isCameraActive() {
        return this.cameraLayer.isActive();
    }

    public boolean shouldSuppressVanillaMainHandEquip() {
        return this.inspectPlayback.isActive() || this.mainHandLayer.isActive() || this.equipBlendWindowTick > 0;
    }

    public boolean shouldSuppressVanillaOffhandEquip() {
        return this.inspectPlayback.isActive() || this.offhandLayer.isActive() || this.offhandEquipBlendWindowTick > 0;
    }

    public boolean shouldRenderInspectHandsTogether() {
        return this.mainHandLayer.isInspectActive() || this.offhandLayer.isInspectActive();
    }

    public boolean shouldRenderEmptyOffhandArm() {
        return this.shouldRenderInspectHandsTogether() && this.renderEmptyOffhandArm;
    }

    public boolean isMainHandLayerActive() {
        return this.mainHandLayer.isActive();
    }

    public boolean isOffhandLayerActive() {
        return this.offhandLayer.isActive();
    }

    public boolean hasProfileFor(ItemStack stack) {
        return this.hasProfileFor(stack, false);
    }

    public boolean hasProfileFor(ItemStack stack, boolean allowEmptyHands) {
        return this.hasProfileFor(stack, allowEmptyHands, HandLayerSide.MAIN);
    }

    public boolean hasOffhandProfileFor(ItemStack stack) {
        return this.hasProfileFor(stack, false, HandLayerSide.OFFHAND);
    }

    public boolean hasSpecificProfileFor(ItemStack stack) {
        return !stack.isEmpty() && this.profileRegistry.resolveSpecific(stack, HandLayerSide.MAIN) != null;
    }

    private boolean hasProfileFor(ItemStack stack, boolean allowEmptyHands, HandLayerSide side) {
        return this.profileRegistry.resolve(stack, allowEmptyHands, side) != null;
    }

    public ItemStack visualStackOr(ItemStack fallback) {
        if (this.mainHandLayer.isActive()) {
            return this.mainHandLayer.visualStackOr(fallback);
        }
        return this.visualStack.isEmpty() ? fallback : this.visualStack;
    }

    public ItemStack visualOffhandStackOr(ItemStack fallback) {
        if (this.offhandLayer.isActive()) {
            return this.offhandLayer.visualStackOr(fallback);
        }
        return this.visualOffhandStack.isEmpty() ? fallback : this.visualOffhandStack;
    }

    public void startInspect(ItemStack stack) {
        this.startInspect(stack, false);
    }

    public void startInspect(ItemStack stack, boolean allowEmptyHands) {
        this.startInspect(stack, ItemStack.EMPTY, allowEmptyHands);
    }

    public void startInspect(ItemStack mainHandStack, ItemStack offhandStack, boolean allowEmptyHands) {
        if (this.equipBlendWindowTick > 0) {
            return;
        }
        boolean bothHandsEmpty = mainHandStack.isEmpty() && offhandStack.isEmpty();
        EnumMap<Bone, Transform> restartBlend = this.isPlaying() ? this.captureCurrentTransforms() : new EnumMap<>(Bone.class);
        this.applyPendingInspectRestartBlend(restartBlend);
        this.mainHandLayer.cancel();
        this.offhandLayer.cancel();
        if (!this.activateInspectProfile(mainHandStack, offhandStack, allowEmptyHands)) {
            return;
        }
        AnimationProfile profile = this.profileRegistry.profile(this.activeProfileId);
        Animation inspectAnimation = profile == null ? Animation.empty() : profile.animations().getOrDefault(Clip.INSPECT, Animation.empty());
        if (!inspectAnimation.isEmpty()) {
            this.renderEmptyOffhandArm = bothHandsEmpty;
            this.visualStack = mainHandStack.copy();
            this.visualOffhandStack = offhandStack.copy();
            this.state = State.INSPECT;
            this.mainHandLayer.startInspect(profile, inspectAnimation, mainHandStack, this.filterControlledTransforms(restartBlend, HandLayerSide.MAIN));
            this.offhandLayer.startInspect(profile, inspectAnimation, offhandStack, this.filterControlledTransforms(restartBlend, HandLayerSide.OFFHAND));
            this.cameraLayer.play(inspectAnimation, this.viewmodelCamera, restartBlend);
            this.inspectPlayback.play(inspectAnimation);
            this.pendingInspectRestartBlend.clear();
        }
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack) {
        this.onHotbarChanged(oldStack, newStack, false, false);
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
        this.onMainHandChanged(oldStack, newStack, oldAllowsEmptyHands, newAllowsEmptyHands);
    }

    public void onMainHandChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
        boolean inspectActive = this.shouldRenderInspectHandsTogether();
        ItemStack oldVisualStack = inspectActive || this.mainHandLayer.isActive()
                ? this.visualStackOr(oldStack).copy()
                : oldStack.copy();
        if (inspectActive) {
            this.mainHandLayer.primeRestartBlendFrom(this.captureControlledTransforms(HandLayerSide.MAIN));
            this.mainHandLayer.primeVisualStack(oldVisualStack);
            this.offhandLayer.startBlendToDefault(
                    this.visualOffhandStackOr(ItemStack.EMPTY),
                    this.captureControlledTransforms(HandLayerSide.OFFHAND)
            );
            this.stopInspectPlayback();
            this.mainHandLayer.onInspectInterrupted(oldVisualStack, newStack, oldAllowsEmptyHands, newAllowsEmptyHands);
            return;
        }
        this.cancelAnimation(false);
        this.mainHandLayer.onHandChanged(oldVisualStack, newStack, oldAllowsEmptyHands, newAllowsEmptyHands);
    }

    public void onOffhandChanged(ItemStack oldStack, ItemStack newStack) {
        boolean inspectActive = this.shouldRenderInspectHandsTogether();
        ItemStack oldVisualStack = inspectActive || this.offhandLayer.isActive()
                ? this.visualOffhandStackOr(oldStack).copy()
                : oldStack.copy();
        if (inspectActive) {
            this.mainHandLayer.startBlendToDefault(
                    this.visualStackOr(ItemStack.EMPTY),
                    this.captureControlledTransforms(HandLayerSide.MAIN)
            );
            this.offhandLayer.primeRestartBlendFrom(this.captureControlledTransforms(HandLayerSide.OFFHAND));
            this.offhandLayer.primeVisualStack(oldVisualStack);
            this.stopInspectPlayback();
            this.offhandLayer.onInspectInterrupted(oldVisualStack, newStack, false, false);
            return;
        }
        this.cancelAnimation(false);
        this.offhandLayer.onHandChanged(oldVisualStack, newStack, false, false);
    }

    public void onBothHandsChanged(ItemStack oldMainStack, ItemStack newMainStack, boolean oldMainAllowsEmptyHands, boolean newMainAllowsEmptyHands, ItemStack oldOffhandStack, ItemStack newOffhandStack) {
        boolean inspectActive = this.shouldRenderInspectHandsTogether();
        if (!inspectActive) {
            this.onMainHandChanged(oldMainStack, newMainStack, oldMainAllowsEmptyHands, newMainAllowsEmptyHands);
            this.onOffhandChanged(oldOffhandStack, newOffhandStack);
            return;
        }

        ItemStack oldMainVisualStack = this.visualStackOr(oldMainStack).copy();
        ItemStack oldOffhandVisualStack = this.visualOffhandStackOr(oldOffhandStack).copy();
        EnumMap<Bone, Transform> mainFrom = this.captureControlledTransforms(HandLayerSide.MAIN);
        EnumMap<Bone, Transform> offhandFrom = this.captureControlledTransforms(HandLayerSide.OFFHAND);

        this.mainHandLayer.primeVisualStack(oldMainVisualStack);
        this.offhandLayer.primeVisualStack(oldOffhandVisualStack);
        this.stopInspectPlayback();
        this.mainHandLayer.onInspectInterrupted(oldMainVisualStack, newMainStack, oldMainAllowsEmptyHands, newMainAllowsEmptyHands, mainFrom);
        this.offhandLayer.onInspectInterrupted(oldOffhandVisualStack, newOffhandStack, false, false, offhandFrom);
    }

    public void rememberSettledOffhand(ItemStack stack) {
        this.offhandLayer.rememberSettledStack(stack, false);
    }

    public void startMainHandPullout(ItemStack stack, boolean allowEmptyHands) {
        this.mainHandLayer.startPullout(stack, allowEmptyHands);
    }

    public void startOffhandPullout(ItemStack stack) {
        this.offhandLayer.startPullout(stack, false);
    }

    public void startMainHandExternalPullout(ItemStack stack, boolean allowEmptyHands) {
        this.mainHandLayer.cancel();
        this.mainHandLayer.startPullout(stack, allowEmptyHands, new EnumMap<>(Bone.class));
    }

    public void startOffhandExternalPullout(ItemStack stack) {
        this.offhandLayer.cancel();
        this.offhandLayer.startPullout(stack, false, new EnumMap<>(Bone.class));
    }

    private void cancelInspectHandLayers(boolean renderHandsDuringBlend) {
        if (renderHandsDuringBlend) {
            this.mainHandLayer.startBlendToDefault(
                    this.visualStackOr(ItemStack.EMPTY),
                    this.captureControlledTransforms(HandLayerSide.MAIN)
            );
            this.offhandLayer.startBlendToDefault(
                    this.visualOffhandStackOr(ItemStack.EMPTY),
                    this.captureControlledTransforms(HandLayerSide.OFFHAND)
            );
            return;
        }

        this.rememberPendingInspectRestartBlend();
        this.mainHandLayer.cancel();
        this.offhandLayer.cancel();
    }

    private void stopInspectPlayback() {
        if (this.cameraLayer.isActive()) {
            this.cameraLayer.cancelToDefault();
        }
        this.inspectPlayback.stop();
        this.state = State.IDLE;
        this.renderEmptyOffhandArm = false;
    }

    public void markEquipBlendWindow() {
        this.equipBlendWindowTick = EQUIP_BLEND_WINDOW_TICKS;
    }

    public void cancelAnimation() {
        this.cancelAnimation(true);
    }

    public void cancelAnimationForAttack() {
        this.cancelAnimation(false);
    }

    private void cancelAnimation(boolean renderHandsDuringBlend) {
        if (this.cameraLayer.isActive()) {
            this.cameraLayer.cancelToDefault();
        }
        if (this.shouldRenderInspectHandsTogether()) {
            this.cancelInspectHandLayers(renderHandsDuringBlend);
        }
        this.inspectPlayback.stop();
        this.renderEmptyOffhandArm = false;
        this.state = State.IDLE;
    }

    public void cancelAllAnimations() {
        this.equipBlendWindowTick = 0;
        this.offhandEquipBlendWindowTick = 0;
        this.state = State.IDLE;
        this.renderEmptyOffhandArm = false;
        this.activeProfileId = null;
        this.visualStack = ItemStack.EMPTY;
        this.visualOffhandStack = ItemStack.EMPTY;
        this.mainHandLayer.cancel();
        this.offhandLayer.cancel();
        this.cameraLayer.cancelAll();
        this.inspectPlayback.stop();
        this.pendingInspectRestartBlend.clear();
    }

    public void tickAnimation(ItemStack selectedStack) {
        this.cameraLayer.tick();
        this.inspectPlayback.tick();
        if (this.equipBlendWindowTick > 0) {
            this.equipBlendWindowTick--;
        }
        if (this.offhandEquipBlendWindowTick > 0) {
            this.offhandEquipBlendWindowTick--;
        }

        this.mainHandLayer.tick(selectedStack);
        this.offhandLayer.tick(ItemStack.EMPTY);
        if (this.state == State.INSPECT && !this.inspectPlayback.isActive() && !this.shouldRenderInspectHandsTogether()) {
            this.state = State.IDLE;
            this.renderEmptyOffhandArm = false;
        }
        if (!this.cameraLayer.isActive() && !this.inspectPlayback.isActive() && !this.shouldRenderInspectHandsTogether()) {
            this.pendingInspectRestartBlend.clear();
        }
    }

    public void tickAnimation() {
        this.tickAnimation(ItemStack.EMPTY);
    }

    private boolean activateProfile(ItemStack stack, boolean allowEmptyHands) {
        ProfileReference profileId = this.profileRegistry.resolve(stack, allowEmptyHands, HandLayerSide.MAIN);
        return this.activateProfile(profileId);
    }

    private boolean activateInspectProfile(ItemStack mainHandStack, ItemStack offhandStack, boolean allowEmptyHands) {
        ProfileReference profileId = this.profileRegistry.resolveBothHands(mainHandStack, offhandStack);
        if (profileId == null) {
            profileId = mainHandStack.isEmpty()
                    ? this.profileRegistry.resolve(offhandStack, allowEmptyHands, HandLayerSide.OFFHAND)
                    : this.profileRegistry.resolve(mainHandStack, allowEmptyHands, HandLayerSide.MAIN);
        }
        return this.activateProfile(profileId);
    }

    private boolean activateProfile(ProfileReference profileId) {
        if (profileId == null) {
            return false;
        }
        if (profileId.equals(this.activeProfileId)) {
            return true;
        }

        AnimationProfile profile = this.profileRegistry.profile(profileId);
        if (profile == null) {
            LOGGER.warn("Missing resolved viewmodel profile {}", profileId);
            return false;
        }

        this.activeProfileId = profileId;
        this.viewmodelCamera = profile.viewmodelCamera();
        this.itemRoot = profile.itemRoot();
        this.itemOffhandRoot = profile.itemOffhandRoot();
        this.blockRoot = profile.blockRoot();
        this.blockOffhandRoot = profile.blockOffhandRoot();
        this.viewmodelArmR = profile.viewmodelArmR();
        this.viewmodelArmL = profile.viewmodelArmL();
        return true;
    }

    private Transform currentTransform(Bone bone, Transform fallback, float partialTick) {
        Transform layered = this.layeredTransform(bone, fallback, partialTick);
        if (layered != null) {
            return layered;
        }
        return fallback;
    }

    private Transform layeredTransform(Bone bone, Transform fallback, float partialTick) {
        if (this.mainHandLayer.controls(bone) && this.mainHandLayer.isActive()) {
            return this.mainHandLayer.currentTransform(bone, fallback, partialTick);
        }
        if (this.offhandLayer.controls(bone) && this.offhandLayer.isActive()) {
            return this.offhandLayer.currentTransform(bone, fallback, partialTick);
        }
        return null;
    }

    private Transform bindFallback(Bone bone) {
        return switch (bone) {
            case VIEWMODEL_CAMERA -> this.viewmodelCamera;
            case ITEM_ROOT -> this.itemRoot;
            case ITEM_OFFHAND_ROOT -> this.itemOffhandRoot;
            case BLOCK_ROOT -> this.blockRoot;
            case BLOCK_OFFHAND_ROOT -> this.blockOffhandRoot;
            case VIEWMODEL_ARM_R -> this.viewmodelArmR;
            case VIEWMODEL_ARM_L -> this.viewmodelArmL;
        };
    }

    private EnumMap<Bone, Transform> captureControlledTransforms(HandLayerSide side) {
        EnumMap<Bone, Transform> transforms = new EnumMap<>(Bone.class);
        for (Bone bone : Bone.values()) {
            if (side.controls(bone)) {
                transforms.put(bone, this.currentTransform(bone, this.bindFallback(bone), 0.0F));
            }
        }
        return transforms;
    }

    private EnumMap<Bone, Transform> filterControlledTransforms(EnumMap<Bone, Transform> source, HandLayerSide side) {
        EnumMap<Bone, Transform> transforms = new EnumMap<>(Bone.class);
        for (Map.Entry<Bone, Transform> entry : source.entrySet()) {
            if (side.controls(entry.getKey())) {
                transforms.put(entry.getKey(), entry.getValue());
            }
        }
        return transforms;
    }

    private EnumMap<Bone, Transform> captureCurrentTransforms() {
        EnumMap<Bone, Transform> transforms = new EnumMap<>(Bone.class);
        for (Bone bone : Bone.values()) {
            transforms.put(bone, this.currentBlendSourceTransform(bone));
        }
        return transforms;
    }

    private void rememberPendingInspectRestartBlend() {
        this.pendingInspectRestartBlend.clear();
        this.pendingInspectRestartBlend.putAll(this.captureControlledTransforms(HandLayerSide.MAIN));
        this.pendingInspectRestartBlend.putAll(this.captureControlledTransforms(HandLayerSide.OFFHAND));
    }

    private void applyPendingInspectRestartBlend(EnumMap<Bone, Transform> restartBlend) {
        if (this.pendingInspectRestartBlend.isEmpty()) {
            return;
        }

        for (Map.Entry<Bone, Transform> entry : this.pendingInspectRestartBlend.entrySet()) {
            Bone bone = entry.getKey();
            if (this.mainHandLayer.controls(bone) && !this.mainHandLayer.isActive()) {
                restartBlend.put(bone, entry.getValue());
            } else if (this.offhandLayer.controls(bone) && !this.offhandLayer.isActive()) {
                restartBlend.put(bone, entry.getValue());
            }
        }
    }

    private Transform currentBlendSourceTransform(Bone bone) {
        if (bone == Bone.VIEWMODEL_CAMERA) {
            return this.isCameraActive()
                    ? this.cameraLayer.currentTransform(0.0F)
                    : Transform.identity();
        }
        return this.currentTransform(bone, this.bindFallback(bone), 0.0F);
    }

    private static float smoothStep(float value) {
        return value * value * (3.0F - 2.0F * value);
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        try {
            this.clear();
            JsonObject profileIndex = this.readFirstExisting(resourceManager, PROFILE_INDEX_LOCATION);
            if (profileIndex == null) {
                LOGGER.warn("Missing viewmodel profile index {}", PROFILE_INDEX_LOCATION);
                return;
            }

            this.loadProfileIndex(resourceManager, profileIndex);
            this.loaded = true;
            LOGGER.info("Loaded {} viewmodel profiles, {} item rules, {} tag rules",
                    this.profileRegistry.profileCount(),
                    this.profileRegistry.primaryItemRuleCount(),
                    this.profileRegistry.primaryTagRuleCount());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to load viewmodel animations", exception);
            this.clear();
        }
    }

    private void loadProfileIndex(ResourceManager resourceManager, JsonObject root) {
        if (root.has("profiles")) {
            JsonObject aliases = GsonHelper.getAsJsonObject(root, "profiles");
            for (Map.Entry<String, JsonElement> entry : aliases.entrySet()) {
                this.profileRegistry.addAlias(entry.getKey(), ResourceLocation.parse(entry.getValue().getAsString()));
            }
        }
        if (root.has("animations")) {
            JsonObject animations = GsonHelper.getAsJsonObject(root, "animations");
            for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/inline/" + entry.getKey());
                this.profileRegistry.addAlias(entry.getKey(), location);
                this.profileRegistry.addInlineProfile(location, this.inlineProfileRoot(entry.getValue()));
            }
        }

        JsonObject equipRoot = root.has("equip") ? GsonHelper.getAsJsonObject(root, "equip") : null;
        JsonObject inspectRoot = root.has("inspect") ? GsonHelper.getAsJsonObject(root, "inspect") : null;
        JsonObject primaryRoot = equipRoot != null && equipRoot.has("main_hand") ? GsonHelper.getAsJsonObject(equipRoot, "main_hand")
                : equipRoot != null && equipRoot.has("main") ? GsonHelper.getAsJsonObject(equipRoot, "main")
                : equipRoot != null && equipRoot.has("primary") ? GsonHelper.getAsJsonObject(equipRoot, "primary") : null;
        JsonObject secondaryRoot = equipRoot != null && equipRoot.has("offhand") ? GsonHelper.getAsJsonObject(equipRoot, "offhand")
                : equipRoot != null && equipRoot.has("secondary") ? GsonHelper.getAsJsonObject(equipRoot, "secondary") : null;
        JsonObject bothRoot = inspectRoot != null ? inspectRoot : root.has("both") ? GsonHelper.getAsJsonObject(root, "both") : null;

        JsonObject primaryFallbackRoot = primaryRoot == null ? root : primaryRoot;
        String fallbackKey = primaryFallbackRoot.has("default") ? "default" : "fallback";
        if (hasProfileReference(primaryFallbackRoot, fallbackKey)) {
            ProfileReference profileId = this.readProfileReference(primaryFallbackRoot, fallbackKey, ProfileContext.PRIMARY);
            this.profileRegistry.setFallback(HandLayerSide.MAIN, profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        } else if (hasProfileReference(equipRoot, "default")) {
            ProfileReference profileId = this.readProfileReference(equipRoot, "default", ProfileContext.PRIMARY);
            this.profileRegistry.setFallback(HandLayerSide.MAIN, profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        }

        if (hasProfileReference(secondaryRoot, "default")) {
            ProfileReference profileId = this.readProfileReference(secondaryRoot, "default", ProfileContext.SECONDARY);
            this.profileRegistry.setFallback(HandLayerSide.OFFHAND, profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        } else if (hasProfileReference(equipRoot, "default")) {
            ProfileReference profileId = this.readProfileReference(equipRoot, "default", ProfileContext.SECONDARY);
            this.profileRegistry.setFallback(HandLayerSide.OFFHAND, profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        } else if (hasProfileReference(root, "secondary_default")) {
            ProfileReference profileId = this.readProfileReference(root, "secondary_default", ProfileContext.SECONDARY);
            this.profileRegistry.setFallback(HandLayerSide.OFFHAND, profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        } else if (hasProfileReference(root, "offhand_default")) {
            ProfileReference profileId = this.readProfileReference(root, "offhand_default", ProfileContext.SECONDARY);
            this.profileRegistry.setFallback(HandLayerSide.OFFHAND, profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        }

        if (hasProfileReference(bothRoot, "default")) {
            ProfileReference profileId = this.readProfileReference(bothRoot, "default", ProfileContext.BOTH);
            this.profileRegistry.setBothHandsFallback(profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        } else if (hasProfileReference(root, "both_default")) {
            ProfileReference profileId = this.readProfileReference(root, "both_default", ProfileContext.BOTH);
            this.profileRegistry.setBothHandsFallback(profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        } else if (hasProfileReference(root, "both_hands_default")) {
            ProfileReference profileId = this.readProfileReference(root, "both_hands_default", ProfileContext.BOTH);
            this.profileRegistry.setBothHandsFallback(profileId);
            this.ensureProfileLoaded(resourceManager, profileId);
        }

        this.loadProfileRules(resourceManager, primaryFallbackRoot, HandLayerSide.MAIN);
        if (root.has("main_hand")) {
            this.loadProfileRules(resourceManager, GsonHelper.getAsJsonObject(root, "main_hand"), HandLayerSide.MAIN);
        }
        if (secondaryRoot != null) {
            this.loadProfileRules(resourceManager, secondaryRoot, HandLayerSide.OFFHAND);
        }
        if (root.has("offhand")) {
            this.loadProfileRules(resourceManager, GsonHelper.getAsJsonObject(root, "offhand"), HandLayerSide.OFFHAND);
        }
        JsonArray bothHands = null;
        if (bothRoot != null && bothRoot.has("rules")) {
            bothHands = GsonHelper.getAsJsonArray(bothRoot, "rules");
        } else if (bothRoot != null && bothRoot.has("hands")) {
            bothHands = GsonHelper.getAsJsonArray(bothRoot, "hands");
        } else if (root.has("both_hands")) {
            bothHands = GsonHelper.getAsJsonArray(root, "both_hands");
        }
        if (bothHands != null) {
            for (JsonElement element : bothHands) {
                JsonObject ruleJson = element.getAsJsonObject();
                ProfileReference profileId = this.readProfileReference(ruleJson, ruleJson.has("animation") ? "animation" : "profile", ProfileContext.BOTH);
                this.profileRegistry.addBothHandsRule(new BothHandsProfileRule(
                        HandMatcher.read(readHandMatcher(ruleJson, "main_hand", "main", "primary")),
                        HandMatcher.read(readHandMatcher(ruleJson, "secondary", "offhand")),
                        profileId
                ));
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }
    }

    private static JsonObject readHandMatcher(JsonObject root, String... keys) {
        for (String key : keys) {
            if (root.has(key)) {
                return GsonHelper.getAsJsonObject(root, key);
            }
        }
        throw new IllegalArgumentException("Inspect rule is missing a hand matcher");
    }

    private static boolean hasProfileReference(JsonObject root, String key) {
        return root != null
                && root.has(key)
                && !root.get(key).isJsonNull()
                && !GsonHelper.getAsString(root, key).isBlank();
    }

    private JsonObject inlineProfileRoot(JsonElement element) {
        JsonObject root = new JsonObject();
        JsonObject clips = new JsonObject();
        if (element.isJsonPrimitive()) {
            clips.addProperty(Clip.INSPECT.configKey(), element.getAsString());
        } else {
            JsonObject clipRoot = element.getAsJsonObject();
            for (Clip clip : Clip.values()) {
                String key = clip.configKey();
                if (clipRoot.has(key) && !clipRoot.get(key).isJsonNull()) {
                    clips.addProperty(key, GsonHelper.getAsString(clipRoot, key));
                }
            }
        }
        root.add("clips", clips);
        return root;
    }

    private void loadProfileRules(ResourceManager resourceManager, JsonObject root, HandLayerSide side) {
        ProfileContext context = side == HandLayerSide.OFFHAND ? ProfileContext.SECONDARY : ProfileContext.PRIMARY;
        if (root.has("items")) {
            JsonObject items = GsonHelper.getAsJsonObject(root, "items");
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                ResourceLocation itemId = ResourceLocation.parse(entry.getKey());
                ProfileReference profileId = this.parseProfileReference(entry.getValue().getAsString(), context);
                this.profileRegistry.addItemRule(side, itemId, profileId);
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }

        if (root.has("tags")) {
            JsonArray tags = GsonHelper.getAsJsonArray(root, "tags");
            for (JsonElement element : tags) {
                JsonObject tagRule = element.getAsJsonObject();
                ResourceLocation tagId = ResourceLocation.parse(GsonHelper.getAsString(tagRule, "id"));
                ProfileReference profileId = this.readProfileReference(tagRule, "profile", context);
                this.profileRegistry.addTagRule(side, new TagProfileRule(TagKey.create(Registries.ITEM, tagId), profileId));
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }
        if (root.has("rules")) {
            JsonArray rules = GsonHelper.getAsJsonArray(root, "rules");
            for (JsonElement element : rules) {
                JsonObject ruleJson = element.getAsJsonObject();
                ProfileReference profileId = this.readProfileReference(ruleJson, ruleJson.has("animation") ? "animation" : "profile", context);
                this.profileRegistry.addMatcherRule(side, new MatcherProfileRule(HandMatcher.read(ruleJson), profileId));
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }
    }

    private ProfileReference readProfileReference(JsonObject root, String key, ProfileContext context) {
        return this.parseProfileReference(GsonHelper.getAsString(root, key), context);
    }

    private ProfileReference parseProfileReference(String value, ProfileContext context) {
        ResourceLocation location = this.profileRegistry.alias(value);
        if (location == null) {
            location = ResourceLocation.parse(value);
        }
        return new ProfileReference(location, context);
    }

    private void ensureProfileLoaded(ResourceManager resourceManager, ProfileReference profileId) {
        if (this.profileRegistry.hasProfile(profileId)) {
            return;
        }

        JsonObject profileRoot = this.profileRegistry.inlineProfile(profileId.location());
        if (profileRoot == null) {
            profileRoot = this.readRequired(resourceManager, profileId.location());
        }
        this.profileRegistry.addProfile(profileId, this.readProfile(resourceManager, profileId, profileRoot));
    }

    private AnimationProfile readProfile(ResourceManager resourceManager, ProfileReference profileId, JsonObject root) {
        JsonObject clips = this.readContextClips(root, profileId.context());
        ResourceLocation bindLocation = this.firstClipLocation(clips);
        JsonObject bindRoot = this.readRequired(resourceManager, bindLocation);
        ProfileBindPose bindPose = this.readBindPose(bindRoot);

        EnumMap<Clip, Animation> profileAnimations = new EnumMap<>(Clip.class);
        this.loadProfileClip(resourceManager, profileAnimations, bindPose, clips, Clip.INSPECT);
        this.loadProfileClip(resourceManager, profileAnimations, bindPose, clips, Clip.PULLOUT);
        this.loadProfileClip(resourceManager, profileAnimations, bindPose, clips, Clip.PUTAWAY);
        ProfileBindPose effectiveBindPose = bindPose.withFirstFrameFallbacks(profileAnimations);

        LOGGER.info("Loaded viewmodel profile {}: inspect={} pullout={} putaway={}",
                profileId,
                profileAnimations.getOrDefault(Clip.INSPECT, Animation.empty()).length(),
                profileAnimations.getOrDefault(Clip.PULLOUT, Animation.empty()).length(),
                profileAnimations.getOrDefault(Clip.PUTAWAY, Animation.empty()).length());
        return new AnimationProfile(
                effectiveBindPose.viewmodelCamera(),
                effectiveBindPose.itemRoot(),
                effectiveBindPose.itemOffhandRoot(),
                effectiveBindPose.blockRoot(),
                effectiveBindPose.blockOffhandRoot(),
                effectiveBindPose.viewmodelArmR(),
                effectiveBindPose.viewmodelArmL(),
                profileAnimations
        );
    }

    private JsonObject readContextClips(JsonObject root, ProfileContext context) {
        JsonObject clips = GsonHelper.getAsJsonObject(root, "clips");
        if (clips.has("inspect") || clips.has("pullout") || clips.has("putaway")) {
            return clips;
        }

        String contextKey = context.configKey();
        if (!clips.has(contextKey)) {
            throw new IllegalArgumentException("Viewmodel profile is missing clip context '" + contextKey + "'");
        }
        return GsonHelper.getAsJsonObject(clips, contextKey);
    }

    private void loadProfileClip(ResourceManager resourceManager, EnumMap<Clip, Animation> profileAnimations, ProfileBindPose bindPose, JsonObject clips, Clip clip) {
        ResourceLocation location = this.optionalClipLocation(clips, clip);
        if (location == null) {
            profileAnimations.put(clip, Animation.empty());
            return;
        }
        JsonObject root = this.readFirstExisting(resourceManager, location);
        profileAnimations.put(clip, root == null ? Animation.empty() : Animation.read(root, bindPose.viewmodelCamera(), bindPose.itemRoot(), bindPose.itemOffhandRoot(), bindPose.blockRoot(), bindPose.blockOffhandRoot(), bindPose.viewmodelArmR(), bindPose.viewmodelArmL()));
    }

    private ResourceLocation firstClipLocation(JsonObject clips) {
        for (Clip clip : Clip.values()) {
            ResourceLocation location = this.optionalClipLocation(clips, clip);
            if (location != null) {
                return location;
            }
        }
        throw new IllegalArgumentException("Viewmodel animation entry must define at least one clip");
    }

    private ResourceLocation optionalClipLocation(JsonObject clips, Clip clip) {
        String key = clip.configKey();
        if (!clips.has(key) || clips.get(key).isJsonNull()) {
            return null;
        }
        return ResourceLocation.parse(GsonHelper.getAsString(clips, key));
    }

    private ResourceLocation clipLocation(JsonObject clips, Clip clip) {
        String key = clip.configKey();
        if (!clips.has(key)) {
            throw new IllegalArgumentException("Viewmodel profile is missing clip '" + key + "'");
        }
        return ResourceLocation.parse(GsonHelper.getAsString(clips, key));
    }

    private JsonObject readFirstExisting(ResourceManager resourceManager, ResourceLocation... locations) {
        for (ResourceLocation location : locations) {
            Optional<Resource> resource = resourceManager.getResource(location);
            if (resource.isPresent()) {
                try (Reader reader = resource.get().openAsReader()) {
                    return GsonHelper.parse(reader);
                } catch (IOException | RuntimeException exception) {
                    LOGGER.error("Failed to read viewmodel animation {}", location, exception);
                    throw new IllegalStateException("Failed to read " + location, exception);
                }
            }
        }
        return null;
    }

    private JsonObject readRequired(ResourceManager resourceManager, ResourceLocation location) {
        JsonObject root = this.readFirstExisting(resourceManager, location);
        if (root == null) {
            throw new IllegalArgumentException("Missing viewmodel resource " + location);
        }
        return root;
    }

    private ProfileBindPose readBindPose(JsonObject root) {
        JsonObject bones = GsonHelper.getAsJsonObject(root, "bones");
        Transform viewmodelCamera = Transform.read(GsonHelper.getAsJsonObject(bones, "viewmodel_camera"));
        Transform itemRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "item_root"));
        Transform itemOffhandRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "item_offhand_root"));
        Transform blockRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "block_root"));
        Transform blockOffhandRoot = bones.has("block_offhand_root")
                ? Transform.read(GsonHelper.getAsJsonObject(bones, "block_offhand_root"))
                : itemOffhandRoot;
        Transform viewmodelArmR = Transform.read(GsonHelper.getAsJsonObject(bones, "viewmodel_arm_R"));
        Transform viewmodelArmL = Transform.read(GsonHelper.getAsJsonObject(bones, "viewmodel_arm_L"));
        return new ProfileBindPose(viewmodelCamera, itemRoot, itemOffhandRoot, blockRoot, blockOffhandRoot, viewmodelArmR, viewmodelArmL);
    }

    private void clear() {
            this.viewmodelCamera = Transform.identity();
            this.itemRoot = Transform.identity();
            this.itemOffhandRoot = Transform.identity();
            this.blockRoot = Transform.identity();
            this.blockOffhandRoot = Transform.identity();
            this.viewmodelArmR = Transform.identity();
            this.viewmodelArmL = Transform.identity();
            this.profileRegistry.clear();
            this.activeProfileId = null;
            this.state = State.IDLE;
            this.visualStack = ItemStack.EMPTY;
            this.visualOffhandStack = ItemStack.EMPTY;
            this.renderEmptyOffhandArm = false;
            this.equipBlendWindowTick = 0;
            this.offhandEquipBlendWindowTick = 0;
            this.loaded = false;
            this.cameraLayer.cancelAll();
            this.inspectPlayback.stop();
    }

    public record Transform(float tx, float ty, float tz, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        public static Transform identity() {
            return new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
        }

        public static Transform read(JsonObject json) {
            JsonArray translation = GsonHelper.getAsJsonArray(json, "translation");
            JsonArray rotation = GsonHelper.getAsJsonArray(json, "rotation");
            JsonArray scale = GsonHelper.getAsJsonArray(json, "scale");
            return new Transform(
                    readFloat(translation, 0),
                    readFloat(translation, 1),
                    readFloat(translation, 2),
                    readFloat(rotation, 0),
                    readFloat(rotation, 1),
                    readFloat(rotation, 2),
                    readFloat(rotation, 3),
                    readFloat(scale, 0),
                    readFloat(scale, 1),
                    readFloat(scale, 2)
            );
        }

        public void apply(PoseStack poseStack) {
            poseStack.translate(this.tx, this.ty, this.tz);
            poseStack.mulPose(new Quaternionf(this.qx, this.qy, this.qz, this.qw));
            poseStack.scale(this.sx, this.sy, this.sz);
        }

        public void applyInverse(PoseStack poseStack) {
            Matrix4f inverse = this.toMatrix().invert();
            poseStack.mulPose(inverse);
        }

        public void apply(PoseStack poseStack, boolean mirrorX) {
            if (!mirrorX) {
                this.apply(poseStack);
                return;
            }

            Matrix4f transform = new Matrix4f()
                    .translation(this.tx, this.ty, this.tz)
                    .rotate(new Quaternionf(this.qx, this.qy, this.qz, this.qw))
                    .scale(this.sx, this.sy, this.sz);
            Matrix4f mirror = new Matrix4f()
                    .scale(-1.0F, 1.0F, 1.0F)
                    .mul(transform)
                    .scale(-1.0F, 1.0F, 1.0F);
            poseStack.mulPose(mirror);
        }

        public void applyMirroredPosition(PoseStack poseStack, boolean mirrorX) {
            poseStack.translate(mirrorX ? -this.tx : this.tx, this.ty, this.tz);
            poseStack.mulPose(new Quaternionf(this.qx, this.qy, this.qz, this.qw));
            poseStack.scale(this.sx, this.sy, this.sz);
        }

        public void applyLeftHandItem(PoseStack poseStack, Transform bindPose) {
            this.mirroredTransform().apply(poseStack);
        }

        public Transform mirroredTransform() {
            Matrix4f mirror = new Matrix4f().scale(-1.0F, 1.0F, 1.0F);
            return fromMatrix(new Matrix4f(mirror).mul(this.toMatrix()).mul(mirror));
        }

        private Matrix4f toMatrix() {
            return new Matrix4f()
                    .translation(this.tx, this.ty, this.tz)
                    .rotate(new Quaternionf(this.qx, this.qy, this.qz, this.qw))
                    .scale(this.sx, this.sy, this.sz);
        }

        private static Transform fromMatrix(Matrix4f matrix) {
            Vector3f translation = matrix.getTranslation(new Vector3f());
            Quaternionf rotation = matrix.getUnnormalizedRotation(new Quaternionf());
            Vector3f scale = matrix.getScale(new Vector3f());
            return new Transform(
                    translation.x(),
                    translation.y(),
                    translation.z(),
                    rotation.x(),
                    rotation.y(),
                    rotation.z(),
                    rotation.w(),
                    scale.x(),
                    scale.y(),
                    scale.z()
            );
        }

        private Matrix4f toMirroredPositionMatrix() {
            return new Matrix4f()
                    .translation(-this.tx, this.ty, this.tz)
                    .rotate(new Quaternionf(this.qx, this.qy, this.qz, this.qw))
                    .scale(this.sx, this.sy, this.sz);
        }

        public static Transform lerp(Transform from, Transform to, float alpha) {
            Quaternionf rotation = new Quaternionf(from.qx, from.qy, from.qz, from.qw)
                    .slerp(new Quaternionf(to.qx, to.qy, to.qz, to.qw), alpha);
            return new Transform(
                    lerp(from.tx, to.tx, alpha),
                    lerp(from.ty, to.ty, alpha),
                    lerp(from.tz, to.tz, alpha),
                    rotation.x,
                    rotation.y,
                    rotation.z,
                    rotation.w,
                    lerp(from.sx, to.sx, alpha),
                    lerp(from.sy, to.sy, alpha),
                    lerp(from.sz, to.sz, alpha)
            );
        }

        private static float lerp(float from, float to, float alpha) {
            return from + (to - from) * alpha;
        }

        private static float readFloat(JsonArray array, int index) {
            if (array.size() <= index) {
                throw new IllegalArgumentException("Expected at least " + (index + 1) + " transform values");
            }

            return array.get(index).getAsFloat();
        }
    }

    private enum Clip {
        INSPECT,
        PULLOUT,
        PUTAWAY;

        private String configKey() {
            return switch (this) {
                case INSPECT -> "inspect";
                case PULLOUT -> "pullout";
                case PUTAWAY -> "putaway";
            };
        }
    }

    private enum State {
        IDLE,
        PULLOUT,
        INSPECT,
        PUTAWAY
    }

    private static final class ProfileRegistry {
        private final Map<ProfileReference, AnimationProfile> profiles = new HashMap<>();
        private final Map<String, ResourceLocation> aliases = new HashMap<>();
        private final Map<ResourceLocation, JsonObject> inlineProfiles = new HashMap<>();
        private final HandRules primaryRules = new HandRules();
        private final HandRules offhandRules = new HandRules();
        private final List<BothHandsProfileRule> bothHandsRules = new ArrayList<>();
        private ProfileReference primaryFallback;
        private ProfileReference offhandFallback;
        private ProfileReference bothHandsFallback;

        private void clear() {
            this.profiles.clear();
            this.aliases.clear();
            this.inlineProfiles.clear();
            this.primaryRules.clear();
            this.offhandRules.clear();
            this.bothHandsRules.clear();
            this.primaryFallback = null;
            this.offhandFallback = null;
            this.bothHandsFallback = null;
        }

        private int profileCount() {
            return this.profiles.size();
        }

        private int primaryItemRuleCount() {
            return this.primaryRules.itemRules.size();
        }

        private int primaryTagRuleCount() {
            return this.primaryRules.tagRules.size();
        }

        private void addAlias(String key, ResourceLocation location) {
            this.aliases.put(key, location);
        }

        private ResourceLocation alias(String key) {
            return this.aliases.get(key);
        }

        private void addInlineProfile(ResourceLocation location, JsonObject profileRoot) {
            this.inlineProfiles.put(location, profileRoot);
        }

        private JsonObject inlineProfile(ResourceLocation location) {
            return this.inlineProfiles.get(location);
        }

        private boolean hasProfile(ProfileReference profileId) {
            return this.profiles.containsKey(profileId);
        }

        private AnimationProfile profile(ProfileReference profileId) {
            return this.profiles.get(profileId);
        }

        private void addProfile(ProfileReference profileId, AnimationProfile profile) {
            this.profiles.put(profileId, profile);
        }

        private void setFallback(HandLayerSide side, ProfileReference profileId) {
            if (side == HandLayerSide.OFFHAND) {
                this.offhandFallback = profileId;
            } else {
                this.primaryFallback = profileId;
            }
        }

        private void setBothHandsFallback(ProfileReference profileId) {
            this.bothHandsFallback = profileId;
        }

        private void addItemRule(HandLayerSide side, ResourceLocation itemId, ProfileReference profileId) {
            this.rules(side).itemRules.put(itemId, profileId);
        }

        private void addTagRule(HandLayerSide side, TagProfileRule rule) {
            this.rules(side).tagRules.add(rule);
        }

        private void addMatcherRule(HandLayerSide side, MatcherProfileRule rule) {
            this.rules(side).matcherRules.add(rule);
        }

        private void addBothHandsRule(BothHandsProfileRule rule) {
            this.bothHandsRules.add(rule);
        }

        private ProfileReference resolveBothHands(ItemStack mainHandStack, ItemStack offhandStack) {
            for (BothHandsProfileRule rule : this.bothHandsRules) {
                if (rule.matches(mainHandStack, offhandStack)) {
                    return rule.profileId();
                }
            }

            return this.bothHandsFallback;
        }

        private ProfileReference resolve(ItemStack stack, boolean allowEmptyHands, HandLayerSide side) {
            if (stack.isEmpty()) {
                if (!allowEmptyHands) {
                    return null;
                }

                return this.resolveSpecific(stack, side);
            }

            ProfileReference profileId = this.resolveSpecific(stack, side);
            if (profileId != null) {
                return profileId;
            }

            if (side == HandLayerSide.OFFHAND && this.offhandFallback != null) {
                return this.offhandFallback;
            }

            return this.primaryFallback;
        }

        private ProfileReference resolveSpecific(ItemStack stack, HandLayerSide side) {
            if (side == HandLayerSide.OFFHAND) {
                ProfileReference offhandProfile = this.resolveSpecific(stack, this.offhandRules);
                if (offhandProfile != null) {
                    return offhandProfile;
                }
            }

            return this.resolveSpecific(stack, this.primaryRules);
        }

        private ProfileReference resolveSpecific(ItemStack stack, HandRules rules) {
            if (!stack.isEmpty()) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                ProfileReference itemProfile = rules.itemRules.get(itemId);
                if (itemProfile != null) {
                    return itemProfile;
                }

                for (TagProfileRule rule : rules.tagRules) {
                    if (stack.is(rule.tag())) {
                        return rule.profileId();
                    }
                }
            }

            for (MatcherProfileRule rule : rules.matcherRules) {
                if (rule.matcher().matches(stack)) {
                    return rule.profileId();
                }
            }

            return null;
        }

        private HandRules rules(HandLayerSide side) {
            return side == HandLayerSide.OFFHAND ? this.offhandRules : this.primaryRules;
        }

        private static final class HandRules {
            private final Map<ResourceLocation, ProfileReference> itemRules = new HashMap<>();
            private final List<TagProfileRule> tagRules = new ArrayList<>();
            private final List<MatcherProfileRule> matcherRules = new ArrayList<>();

            private void clear() {
                this.itemRules.clear();
                this.tagRules.clear();
                this.matcherRules.clear();
            }
        }
    }

    private enum ProfileContext {
        PRIMARY("primary"),
        SECONDARY("secondary"),
        BOTH("both");

        private final String configKey;

        ProfileContext(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return this.configKey;
        }
    }

    private enum Bone {
        VIEWMODEL_CAMERA("viewmodel_camera"),
        ITEM_ROOT("item_root"),
        ITEM_OFFHAND_ROOT("item_offhand_root"),
        BLOCK_ROOT("block_root"),
        BLOCK_OFFHAND_ROOT("block_offhand_root"),
        VIEWMODEL_ARM_R("viewmodel_arm_R"),
        VIEWMODEL_ARM_L("viewmodel_arm_L");

        private final String jsonName;

        Bone(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return this.jsonName;
        }
    }

    private record ProfileReference(ResourceLocation location, ProfileContext context) {
    }

    private record TagProfileRule(TagKey<Item> tag, ProfileReference profileId) {
    }

    private record MatcherProfileRule(HandMatcher matcher, ProfileReference profileId) {
    }

    private record BothHandsProfileRule(HandMatcher mainHand, HandMatcher offhand, ProfileReference profileId) {
        private boolean matches(ItemStack mainHandStack, ItemStack offhandStack) {
            return this.mainHand.matches(mainHandStack) && this.offhand.matches(offhandStack);
        }
    }

    private record HandMatcher(ResourceLocation itemId, TagKey<Item> tag, boolean empty, boolean any, boolean blockItem) {
        private static HandMatcher read(JsonObject json) {
            boolean empty = GsonHelper.getAsBoolean(json, "empty", false);
            boolean any = GsonHelper.getAsBoolean(json, "any", false);
            boolean blockItem = GsonHelper.getAsBoolean(json, "block_item", false);
            ResourceLocation itemId = json.has("item") ? ResourceLocation.parse(GsonHelper.getAsString(json, "item")) : null;
            TagKey<Item> tag = json.has("tag")
                    ? TagKey.create(Registries.ITEM, ResourceLocation.parse(GsonHelper.getAsString(json, "tag")))
                    : null;
            if (itemId == null && tag == null && !empty && !any && !blockItem) {
                throw new IllegalArgumentException("Hand matcher must define 'item', 'tag', 'empty', 'any', or 'block_item'");
            }
            return new HandMatcher(itemId, tag, empty, any, blockItem);
        }

        private boolean matches(ItemStack stack) {
            if (stack.isEmpty()) {
                return this.empty;
            }
            if (this.any) {
                return true;
            }
            if (this.itemId != null && this.itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                return true;
            }
            if (this.tag != null && stack.is(this.tag)) {
                return true;
            }
            return this.blockItem && stack.getItem() instanceof BlockItem;
        }
    }

    private record ProfileBindPose(
            Transform viewmodelCamera,
            Transform itemRoot,
            Transform itemOffhandRoot,
            Transform blockRoot,
            Transform blockOffhandRoot,
            Transform viewmodelArmR,
            Transform viewmodelArmL
    ) {
        private ProfileBindPose withFirstFrameFallbacks(EnumMap<Clip, Animation> animations) {
            return new ProfileBindPose(
                    this.viewmodelCamera,
                    this.firstFrameTransform(animations, Bone.ITEM_ROOT, this.itemRoot),
                    this.firstFrameTransform(animations, Bone.ITEM_OFFHAND_ROOT, this.itemOffhandRoot),
                    this.firstFrameTransform(animations, Bone.BLOCK_ROOT, this.blockRoot),
                    this.firstFrameTransform(animations, Bone.BLOCK_OFFHAND_ROOT, this.blockOffhandRoot),
                    this.firstFrameTransform(animations, Bone.VIEWMODEL_ARM_R, this.viewmodelArmR),
                    this.firstFrameTransform(animations, Bone.VIEWMODEL_ARM_L, this.viewmodelArmL)
            );
        }

        private Transform firstFrameTransform(EnumMap<Clip, Animation> animations, Bone bone, Transform fallback) {
            for (Clip clip : Clip.values()) {
                Animation animation = animations.getOrDefault(clip, Animation.empty());
                Transform transform = animation.firstFrameTransformOrNull(bone);
                if (transform != null) {
                    return transform;
                }
            }
            return fallback;
        }
    }

    private record AnimationProfile(
            Transform viewmodelCamera,
            Transform itemRoot,
            Transform itemOffhandRoot,
            Transform blockRoot,
            Transform blockOffhandRoot,
            Transform viewmodelArmR,
            Transform viewmodelArmL,
            EnumMap<Clip, Animation> animations
    ) {
        private Transform bindFallback(Bone bone) {
            return switch (bone) {
                case VIEWMODEL_CAMERA -> this.viewmodelCamera;
                case ITEM_ROOT -> this.itemRoot;
                case ITEM_OFFHAND_ROOT -> this.itemOffhandRoot;
                case BLOCK_ROOT -> this.blockRoot;
                case BLOCK_OFFHAND_ROOT -> this.blockOffhandRoot;
                case VIEWMODEL_ARM_R -> this.viewmodelArmR;
                case VIEWMODEL_ARM_L -> this.viewmodelArmL;
            };
        }
    }

    private static final class InspectPlayback {
        private Animation animation = Animation.empty();
        private int animationTick;
        private int nextSoundEventIndex;
        private boolean skipNextAnimationTick;

        private boolean isActive() {
            return !this.animation.isEmpty();
        }

        private void play(Animation animation) {
            this.animation = animation;
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = true;
            this.playPendingSoundEvents(this.animation.startFrame());
        }

        private void tick() {
            if (this.animation.isEmpty()) {
                return;
            }

            if (this.skipNextAnimationTick) {
                this.skipNextAnimationTick = false;
                return;
            }

            this.animationTick++;
            this.playPendingSoundEvents(this.animation.frameAtTick(this.animationTick));
            if (this.animationTick >= this.animation.lengthTicks()) {
                this.stop();
            }
        }

        private void playPendingSoundEvents(int currentFrame) {
            List<AnimationSoundEvent> soundEvents = this.animation.soundEvents();
            while (this.nextSoundEventIndex < soundEvents.size()) {
                AnimationSoundEvent soundEvent = soundEvents.get(this.nextSoundEventIndex);
                if (soundEvent.frame() > currentFrame) {
                    break;
                }

                soundEvent.play();
                this.nextSoundEventIndex++;
            }
        }

        private void stop() {
            this.animation = Animation.empty();
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = false;
        }
    }

    private final class CameraLayer {
        private Animation animation = Animation.empty();
        private Transform bindFallback = Transform.identity();
        private final HandTransition transition = new HandTransition();
        private int animationTick;
        private boolean skipNextAnimationTick;

        private boolean isActive() {
            return !this.animation.isEmpty() || this.transition.isActive();
        }

        private void play(Animation animation, Transform bindFallback, EnumMap<Bone, Transform> sourceTransforms) {
            boolean wasActive = this.isActive();
            Transform previousTransform = wasActive ? this.currentTransform(0.0F) : Transform.identity();
            if (!animation.hasTransform(Bone.VIEWMODEL_CAMERA)) {
                this.stop();
                return;
            }

            this.animation = animation;
            this.bindFallback = bindFallback;
            this.animationTick = 0;
            this.skipNextAnimationTick = true;
            EnumMap<Bone, Transform> source = new EnumMap<>(Bone.class);
            Transform sourceTransform = sourceTransforms.get(Bone.VIEWMODEL_CAMERA);
            if (sourceTransform != null) {
                source.put(Bone.VIEWMODEL_CAMERA, sourceTransform);
            } else {
                source.put(Bone.VIEWMODEL_CAMERA, previousTransform);
            }
            this.transition.toAnimation(source);
        }

        private void cancelToDefault() {
            EnumMap<Bone, Transform> source = new EnumMap<>(Bone.class);
            source.put(Bone.VIEWMODEL_CAMERA, this.currentTransform(0.0F));
            this.animation = Animation.empty();
            this.animationTick = 0;
            this.skipNextAnimationTick = false;
            this.transition.toDefault(source);
        }

        private void tick() {
            if (this.transition.isDefaultTarget()) {
                this.transition.tick();
                return;
            }

            if (this.animation.isEmpty()) {
                return;
            }

            if (this.skipNextAnimationTick) {
                this.skipNextAnimationTick = false;
                return;
            }

            this.animationTick++;
            this.transition.tick();
            if (this.animationTick >= this.animation.lengthTicks()) {
                this.animation = Animation.empty();
                this.animationTick = 0;
                this.skipNextAnimationTick = false;
                this.transition.clear();
            }
        }

        private Transform currentTransform(Transform fallback, float partialTick) {
            if (this.transition.isDefaultTarget()) {
                return this.transition.apply(Bone.VIEWMODEL_CAMERA, Transform.identity(), Transform.identity(), partialTick);
            }

            if (this.animation.isEmpty()) {
                return Transform.identity();
            }

            Transform target = this.animation.sample(
                    Bone.VIEWMODEL_CAMERA,
                    this.animation.framePosition(this.animationTick + partialTick),
                    this.animation.firstAvailableTransform(Bone.VIEWMODEL_CAMERA, fallback)
            );
            return this.transition.apply(Bone.VIEWMODEL_CAMERA, fallback, target, partialTick);
        }

        private Transform currentTransform(float partialTick) {
            return this.currentTransform(this.bindFallback, partialTick);
        }

        private void stop() {
            this.animation = Animation.empty();
            this.animationTick = 0;
            this.skipNextAnimationTick = false;
            this.transition.clear();
        }

        private void cancelAll() {
            this.bindFallback = Transform.identity();
            this.stop();
        }
    }

    private final class HandLayer {
        private final HandLayerSide side;
        private AnimationProfile profile;
        private Animation animation = Animation.empty();
        private Clip currentClip = Clip.PULLOUT;
        private State state = State.IDLE;
        private ItemStack visualStack = ItemStack.EMPTY;
        private boolean visualStackWasEmpty;
        private ItemStack queuedPulloutStack = ItemStack.EMPTY;
        private boolean queuedPulloutAllowsEmptyHands;
        private final HandTransition transition = new HandTransition();
        private final EnumMap<Bone, Transform> settledTransforms = new EnumMap<>(Bone.class);
        private int animationTick;
        private int nextSoundEventIndex;
        private boolean skipNextAnimationTick;

        private HandLayer(HandLayerSide side) {
            this.side = side;
        }

        private boolean isActive() {
            return this.state == State.PULLOUT || this.state == State.INSPECT || this.state == State.PUTAWAY || this.transition.isActive();
        }

        private boolean isPulloutActive() {
            return this.state == State.PULLOUT;
        }

        private boolean isInspectActive() {
            return this.state == State.INSPECT;
        }

        private boolean controls(Bone bone) {
            return this.side.controls(bone);
        }

        private ItemStack visualStackOr(ItemStack fallback) {
            if (this.isActive() && this.visualStackWasEmpty) {
                return ItemStack.EMPTY;
            }
            return this.visualStack.isEmpty() ? fallback : this.visualStack;
        }

        private boolean hasSettledVisualStack() {
            return !this.visualStack.isEmpty() || this.visualStackWasEmpty;
        }

        private void rememberSettledStack(ItemStack stack, boolean allowEmptyHands) {
            if (!ViewmodelPose.this.hasProfileFor(stack, allowEmptyHands, this.side)) {
                return;
            }

            this.profile = null;
            this.animation = Animation.empty();
            this.currentClip = Clip.PULLOUT;
            this.state = State.IDLE;
            this.visualStack = stack.copy();
            this.visualStackWasEmpty = stack.isEmpty();
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.queuedPulloutAllowsEmptyHands = false;
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = false;
            this.transition.clear();
            this.settledTransforms.clear();
        }

        private void onHandChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
            this.queuedPulloutStack = newStack.copy();
            this.queuedPulloutAllowsEmptyHands = newAllowsEmptyHands;
            if (this.state == State.PUTAWAY) {
                return;
            }

            if (this.state == State.PULLOUT) {
                ItemStack putawayStack = this.currentVisualStackForChange(oldStack);
                EnumMap<Bone, Transform> from = this.captureCurrentControlledTransforms();
                if (!this.startPutaway(putawayStack, oldAllowsEmptyHands, from)) {
                    this.transitionToPulloutOrDefault(putawayStack, newStack, newAllowsEmptyHands, from);
                }
                return;
            }

            ItemStack putawayStack = this.currentVisualStackForChange(oldStack);
            if (ViewmodelPose.this.hasProfileFor(putawayStack, oldAllowsEmptyHands, this.side) && (this.hasSettledVisualStack() || oldAllowsEmptyHands)) {
                EnumMap<Bone, Transform> from = this.captureCurrentControlledTransforms();
                if (this.startPutaway(putawayStack, oldAllowsEmptyHands, from)) {
                    return;
                }
                this.transitionToPulloutOrDefault(putawayStack, newStack, newAllowsEmptyHands, from);
                return;
            }

            this.transitionToPulloutOrDefault(putawayStack, newStack, newAllowsEmptyHands);
        }

        private void onInspectInterrupted(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
            this.onInspectInterrupted(oldStack, newStack, oldAllowsEmptyHands, newAllowsEmptyHands, this.captureCurrentControlledTransforms());
        }

        private void onInspectInterrupted(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands, EnumMap<Bone, Transform> from) {
            this.queuedPulloutStack = newStack.copy();
            this.queuedPulloutAllowsEmptyHands = newAllowsEmptyHands;
            ItemStack putawayStack = this.currentVisualStackForChange(oldStack);
            if (this.startPutaway(putawayStack, oldAllowsEmptyHands, from)) {
                return;
            }

            this.transitionToPulloutOrDefault(putawayStack, newStack, newAllowsEmptyHands, from);
        }

        private boolean startPutaway(ItemStack stack, boolean allowEmptyHands) {
            return this.startPutaway(stack, allowEmptyHands, this.captureCurrentControlledTransforms());
        }

        private boolean startPutaway(ItemStack stack, boolean allowEmptyHands, EnumMap<Bone, Transform> from) {
            ItemStack cachedStack = stack.copy();
            if (!this.playClip(Clip.PUTAWAY, cachedStack, allowEmptyHands, State.PUTAWAY, from)) {
                return false;
            }

            this.visualStack = cachedStack;
            this.visualStackWasEmpty = cachedStack.isEmpty();
            return true;
        }

        private void startInspect(AnimationProfile profile, Animation animation, ItemStack stack, EnumMap<Bone, Transform> restartBlend) {
            this.profile = profile;
            this.animation = animation;
            this.currentClip = Clip.INSPECT;
            this.state = State.INSPECT;
            this.visualStack = stack.copy();
            this.visualStackWasEmpty = stack.isEmpty();
            this.settledTransforms.clear();
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.queuedPulloutAllowsEmptyHands = false;
            this.animationTick = 0;
            this.nextSoundEventIndex = animation.soundEvents().size();
            this.skipNextAnimationTick = true;
            this.transition.toAnimation(this.completeAnimationTransitionSource(profile, animation, restartBlend));
        }

        private boolean startPullout(ItemStack stack, boolean allowEmptyHands) {
            return this.startPullout(stack, allowEmptyHands, this.captureCurrentControlledTransforms());
        }

        private boolean startPullout(ItemStack stack, boolean allowEmptyHands, EnumMap<Bone, Transform> from) {
            if (this.playClip(Clip.PULLOUT, stack, allowEmptyHands, State.PULLOUT, from)) {
                return true;
            }

            if (!this.transition.isDefaultTarget()) {
                this.visualStack = stack.copy();
                this.state = State.IDLE;
            }
            return false;
        }

        private void transitionToPulloutOrDefault(ItemStack oldStack, ItemStack newStack, boolean newAllowsEmptyHands) {
            EnumMap<Bone, Transform> from = this.captureCurrentControlledTransforms();
            this.transitionToPulloutOrDefault(oldStack, newStack, newAllowsEmptyHands, from);
        }

        private void transitionToPulloutOrDefault(ItemStack oldStack, ItemStack newStack, boolean newAllowsEmptyHands, EnumMap<Bone, Transform> from) {
            if (from.isEmpty()) {
                this.resetPlayback();
                if (ViewmodelPose.this.hasProfileFor(newStack, newAllowsEmptyHands, this.side)) {
                    this.startPullout(newStack, newAllowsEmptyHands, new EnumMap<>(Bone.class));
                }
                return;
            }

            this.resetPlayback();
            this.queuedPulloutStack = newStack.copy();
            this.queuedPulloutAllowsEmptyHands = newAllowsEmptyHands;
            this.startBlendToDefault(oldStack, from);
        }

        private ItemStack currentVisualStackForChange(ItemStack fallback) {
            return this.isActive() ? this.visualStackOr(fallback).copy() : fallback.copy();
        }

        private boolean playClip(Clip clip, ItemStack stack, boolean allowEmptyHands, State targetState) {
            return this.playClip(clip, stack, allowEmptyHands, targetState, this.captureCurrentControlledTransforms());
        }

        private boolean playClip(Clip clip, ItemStack stack, boolean allowEmptyHands, State targetState, EnumMap<Bone, Transform> from) {
            ProfileReference profileId = ViewmodelPose.this.profileRegistry.resolve(stack, allowEmptyHands, this.side);
            if (profileId == null) {
                return false;
            }

            AnimationProfile nextProfile = ViewmodelPose.this.profileRegistry.profile(profileId);
            if (nextProfile == null) {
                LOGGER.warn("Missing resolved viewmodel profile {}", profileId);
                return false;
            }

            Animation nextAnimation = nextProfile.animations().getOrDefault(clip, Animation.empty());
            if (nextAnimation.isEmpty()) {
                return false;
            }

            this.profile = nextProfile;
            this.animation = nextAnimation;
            this.currentClip = clip;
            this.state = targetState;
            this.visualStack = stack.copy();
            this.visualStackWasEmpty = stack.isEmpty();
            this.settledTransforms.clear();
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = true;
            this.transition.toAnimation(this.completeAnimationTransitionSource(nextProfile, nextAnimation, from));
            this.playPendingSoundEvents(this.animation.startFrame());
            return true;
        }

        private EnumMap<Bone, Transform> completeAnimationTransitionSource(AnimationProfile profile, Animation animation, EnumMap<Bone, Transform> source) {
            EnumMap<Bone, Transform> completed = new EnumMap<>(Bone.class);
            completed.putAll(source);
            for (Bone bone : Bone.values()) {
                if (!this.controls(bone) || completed.containsKey(bone)) {
                    continue;
                }

                Transform fallback = profile.bindFallback(bone);
                completed.put(bone, animation.firstFrameTransform(bone, fallback));
            }
            return completed;
        }

        private void tick(ItemStack selectedStack) {
            if (this.transition.isDefaultTarget()) {
                this.transition.tick();
                if (!this.transition.isActive()) {
                    if (this.state == State.PUTAWAY) {
                        ItemStack nextStack = this.queuedPulloutStack.copy();
                        boolean nextAllowsEmptyHands = this.queuedPulloutAllowsEmptyHands;
                        this.queuedPulloutStack = ItemStack.EMPTY;
                        this.queuedPulloutAllowsEmptyHands = false;
                        this.resetPlayback();
                        if (ViewmodelPose.this.hasProfileFor(nextStack, nextAllowsEmptyHands, this.side)) {
                            this.startPullout(nextStack, nextAllowsEmptyHands, new EnumMap<>(Bone.class));
                        }
                        return;
                    }

                    this.resetPlayback();
                }
                return;
            }

            if (!this.isActive() || this.animation.isEmpty()) {
                return;
            }

            if (this.skipNextAnimationTick) {
                this.skipNextAnimationTick = false;
                return;
            }

            this.animationTick++;
            this.transition.tick();
            if (this.currentClip != Clip.INSPECT) {
                this.playPendingSoundEvents(this.animation.frameAtTick(this.animationTick));
            }
            if (this.animationTick < this.animation.lengthTicks()) {
                return;
            }

            if (this.currentClip == Clip.PULLOUT) {
                this.rememberSettledTransformsAtEnd();
                this.animationTick = 0;
                this.skipNextAnimationTick = false;
                this.transition.clear();
                this.state = State.IDLE;
                this.markEquipSuppressionWindow();
                return;
            }

            if (this.currentClip == Clip.PUTAWAY) {
                ItemStack nextStack = this.queuedPulloutStack.copy();
                boolean nextAllowsEmptyHands = this.queuedPulloutAllowsEmptyHands;
                this.queuedPulloutStack = ItemStack.EMPTY;
                this.queuedPulloutAllowsEmptyHands = false;
                this.resetPlayback();
                if (ViewmodelPose.this.hasProfileFor(nextStack, nextAllowsEmptyHands, this.side)) {
                    this.startPullout(nextStack, nextAllowsEmptyHands, new EnumMap<>(Bone.class));
                }
            }

            if (this.currentClip == Clip.INSPECT) {
                this.finishInspectPlayback();
            }
        }

        private Transform currentTransform(Bone bone, Transform fallback, float partialTick) {
            if (this.transition.isDefaultTarget()) {
                return this.transition.apply(bone, fallback, fallback, partialTick);
            }

            if (!this.isActive() || this.animation.isEmpty() || this.profile == null) {
                return this.settledTransforms.getOrDefault(bone, fallback);
            }

            Transform profileFallback = this.profile.bindFallback(bone);
            Transform transform = this.animation.sample(bone, this.animationFramePosition(partialTick), this.animation.firstAvailableTransform(bone, profileFallback));
            return this.transition.apply(bone, fallback, transform, partialTick);
        }

        private void primeRestartBlendFrom(EnumMap<Bone, Transform> transforms) {
            this.transition.toAnimation(transforms);
        }

        private void primeVisualStack(ItemStack stack) {
            this.visualStack = stack.copy();
            this.visualStackWasEmpty = stack.isEmpty();
        }

        private EnumMap<Bone, Transform> captureCurrentControlledTransforms() {
            EnumMap<Bone, Transform> transforms = new EnumMap<>(Bone.class);
            if (!this.isActive()) {
                transforms.putAll(this.settledTransforms);
                return transforms;
            }

            for (Bone bone : Bone.values()) {
                if (!this.controls(bone)) {
                    continue;
                }

                transforms.put(bone, this.currentTransform(bone, ViewmodelPose.this.bindFallback(bone), 0.0F));
            }
            return transforms;
        }

        private void rememberSettledTransformsAtEnd() {
            this.settledTransforms.clear();
            if (this.animation.isEmpty()) {
                return;
            }

            for (Bone bone : Bone.values()) {
                if (!this.controls(bone)) {
                    continue;
                }

                Transform fallback = this.profile == null ? ViewmodelPose.this.bindFallback(bone) : this.profile.bindFallback(bone);
                this.settledTransforms.put(bone, this.animation.lastFrameTransform(bone, fallback));
            }
        }

        private Transform currentTransformWithoutRestartBlend(Bone bone) {
            Transform fallback = this.profile == null ? ViewmodelPose.this.bindFallback(bone) : this.profile.bindFallback(bone);
            if (!this.isActive() || this.animation.isEmpty() || this.profile == null) {
                return fallback;
            }

            return this.animation.sample(bone, this.animation.framePosition(this.animationTick), fallback);
        }

        private float animationFramePosition(float partialTick) {
            return this.animation.framePosition(this.animationTick + partialTick);
        }

        private void playPendingSoundEvents(int currentFrame) {
            List<AnimationSoundEvent> soundEvents = this.animation.soundEvents();
            while (this.nextSoundEventIndex < soundEvents.size()) {
                AnimationSoundEvent soundEvent = soundEvents.get(this.nextSoundEventIndex);
                if (soundEvent.frame() > currentFrame) {
                    break;
                }

                soundEvent.play();
                this.nextSoundEventIndex++;
            }
        }

        private void resetPlayback() {
            this.profile = null;
            this.animation = Animation.empty();
            this.currentClip = Clip.PULLOUT;
            this.state = State.IDLE;
            this.visualStack = ItemStack.EMPTY;
            this.visualStackWasEmpty = false;
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = false;
            this.transition.clear();
            this.settledTransforms.clear();
        }

        private void finishInspectPlayback() {
            this.rememberSettledTransformsAtEnd();
            this.profile = null;
            this.animation = Animation.empty();
            this.currentClip = Clip.PULLOUT;
            this.state = State.IDLE;
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = false;
            this.transition.clear();
        }

        private void cancel() {
            this.resetPlayback();
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.queuedPulloutAllowsEmptyHands = false;
            this.transition.clear();
        }

        private void startBlendToDefault(ItemStack stack, EnumMap<Bone, Transform> from) {
            if (from.isEmpty()) {
                this.resetPlayback();
                return;
            }

            this.profile = null;
            this.animation = Animation.empty();
            this.currentClip = Clip.PUTAWAY;
            this.state = State.PUTAWAY;
            this.visualStack = stack.copy();
            this.visualStackWasEmpty = stack.isEmpty();
            this.settledTransforms.clear();
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = false;
            this.transition.toDefault(from);
        }

        private void markEquipSuppressionWindow() {
            if (this.side == HandLayerSide.MAIN) {
                ViewmodelPose.this.equipBlendWindowTick = EQUIP_BLEND_WINDOW_TICKS;
            } else {
                ViewmodelPose.this.offhandEquipBlendWindowTick = EQUIP_BLEND_WINDOW_TICKS;
            }
        }
    }

    private static final class HandTransition {
        private final EnumMap<Bone, Transform> from = new EnumMap<>(Bone.class);
        private Target target = Target.NONE;
        private int tick;

        private boolean isActive() {
            return this.target != Target.NONE && !this.from.isEmpty();
        }

        private boolean isDefaultTarget() {
            return this.target == Target.DEFAULT && !this.from.isEmpty();
        }

        private void toAnimation(EnumMap<Bone, Transform> source) {
            this.start(Target.ANIMATION, source);
        }

        private void toDefault(EnumMap<Bone, Transform> source) {
            this.start(Target.DEFAULT, source);
        }

        private void start(Target target, EnumMap<Bone, Transform> source) {
            this.from.clear();
            this.from.putAll(source);
            this.target = this.from.isEmpty() ? Target.NONE : target;
            this.tick = 0;
        }

        private void tick() {
            if (!this.isActive()) {
                return;
            }

            this.tick++;
            if (this.tick >= RESTART_BLEND_TICKS) {
                this.clear();
            }
        }

        private Transform apply(Bone bone, Transform fallback, Transform targetTransform, float partialTick) {
            if (!this.isActive()) {
                return targetTransform;
            }

            float alpha = Math.min((this.tick + partialTick) / RESTART_BLEND_TICKS, 1.0F);
            Transform source = this.from.getOrDefault(bone, fallback);
            return Transform.lerp(source, targetTransform, smoothStep(alpha));
        }

        private void clear() {
            this.from.clear();
            this.target = Target.NONE;
            this.tick = 0;
        }

        private enum Target {
            NONE,
            ANIMATION,
            DEFAULT
        }
    }

    private enum HandLayerSide {
        MAIN,
        OFFHAND;

        private boolean controls(Bone bone) {
            return switch (this) {
                case MAIN -> bone == Bone.ITEM_ROOT || bone == Bone.BLOCK_ROOT || bone == Bone.VIEWMODEL_ARM_R;
                case OFFHAND -> bone == Bone.ITEM_OFFHAND_ROOT || bone == Bone.BLOCK_OFFHAND_ROOT || bone == Bone.VIEWMODEL_ARM_L;
            };
        }

    }

    private record Animation(boolean loop, int startFrame, int fps, List<AnimationFrame> frames, List<AnimationSoundEvent> soundEvents) {
        public static Animation empty() {
            return new Animation(false, 0, ANIMATION_FPS, List.of(), List.of());
        }

        public static Animation read(JsonObject root, Transform cameraBind, Transform itemBind, Transform offhandItemBind, Transform blockBind, Transform offhandBlockBind, Transform rightArmBind, Transform leftArmBind) {
            if (!root.has("animations")) {
                return empty();
            }

            JsonObject animations = GsonHelper.getAsJsonObject(root, "animations");
            if (animations.entrySet().isEmpty()) {
                return empty();
            }

            JsonObject animationJson = animations.entrySet().iterator().next().getValue().getAsJsonObject();
            boolean loop = false;
            JsonArray frameArray = GsonHelper.getAsJsonArray(animationJson, "frames");
            List<AnimationFrame> frames = new ArrayList<>(frameArray.size());
            for (JsonElement element : frameArray) {
                frames.add(AnimationFrame.read(element.getAsJsonObject(), cameraBind, itemBind, offhandItemBind, blockBind, offhandBlockBind, rightArmBind, leftArmBind));
            }
            frames.sort(Comparator.comparingInt(AnimationFrame::frame));
            List<AnimationSoundEvent> soundEvents = readSoundEvents(animationJson);
            int startFrame = Math.max(1, GsonHelper.getAsInt(animationJson, "start_frame", frames.isEmpty() ? 1 : frames.get(0).frame()));
            frames.removeIf(frame -> frame.frame() < startFrame);
            int fps = GsonHelper.getAsInt(animationJson, "fps");
            if (fps != ANIMATION_FPS) {
                throw new IllegalArgumentException("Viewmodel animations must be exported at " + ANIMATION_FPS + " FPS, got " + fps);
            }
            return frames.isEmpty() ? empty() : new Animation(loop, startFrame, fps, List.copyOf(frames), soundEvents);
        }

        private static List<AnimationSoundEvent> readSoundEvents(JsonObject animationJson) {
            if (!animationJson.has("events")) {
                return List.of();
            }

            JsonArray eventArray = GsonHelper.getAsJsonArray(animationJson, "events");
            List<AnimationSoundEvent> soundEvents = new ArrayList<>();
            for (JsonElement element : eventArray) {
                JsonObject eventJson = element.getAsJsonObject();
                String type = GsonHelper.getAsString(eventJson, "type", "");
                if (!"sound".equals(type)) {
                    continue;
                }

                soundEvents.add(AnimationSoundEvent.read(eventJson));
            }

            soundEvents.sort(Comparator.comparingInt(AnimationSoundEvent::frame));
            return List.copyOf(soundEvents);
        }

        public boolean isEmpty() {
            return this.frames.isEmpty();
        }

        public int length() {
            if (this.frames.isEmpty()) {
                return 0;
            }

            int lastFrame = this.frames.get(this.frames.size() - 1).frame();
            return Math.max(1, lastFrame - this.startFrame + 1);
        }

        public int lengthTicks() {
            return Math.max(1, (int) Math.ceil(this.length() / this.framesPerTick()));
        }

        public float framePosition(float elapsedTicks) {
            return elapsedTicks * this.framesPerTick();
        }

        public int frameAtTick(int elapsedTicks) {
            return (int) Math.floor(this.startFrame + this.framePosition(elapsedTicks));
        }

        private float framesPerTick() {
            return this.fps / 20.0F;
        }

        public Transform sample(Bone bone, float framePosition, Transform fallback) {
            if (this.frames.isEmpty()) {
                return fallback;
            }

            float absoluteFrame = this.startFrame + framePosition;
            if (this.loop) {
                absoluteFrame = this.loopedFrame(absoluteFrame);
            }

            AnimationFrame first = this.frames.get(0);
            if (absoluteFrame <= first.frame()) {
                return first.transformOrFallback(bone, fallback);
            }

            AnimationFrame last = this.frames.get(this.frames.size() - 1);
            if (absoluteFrame >= last.frame()) {
                return last.transformOrFallback(bone, fallback);
            }

            AnimationFrame fromFrame = first;
            AnimationFrame toFrame = last;
            for (int index = 0; index < this.frames.size() - 1; index++) {
                AnimationFrame current = this.frames.get(index);
                AnimationFrame next = this.frames.get(index + 1);
                if (absoluteFrame >= current.frame() && absoluteFrame <= next.frame()) {
                    fromFrame = current;
                    toFrame = next;
                    break;
                }
            }

            float frameSpan = Math.max(1.0F, toFrame.frame() - fromFrame.frame());
            float alpha = (absoluteFrame - fromFrame.frame()) / frameSpan;
            Transform from = fromFrame.transformOrFallback(bone, fallback);
            Transform to = toFrame.transformOrFallback(bone, fallback);
            return Transform.lerp(from, to, alpha);
        }

        public Transform firstFrameTransform(Bone bone, Transform fallback) {
            if (this.frames.isEmpty()) {
                return fallback;
            }

            return this.frames.get(0).transformOrFallback(bone, fallback);
        }

        public Transform firstAvailableTransform(Bone bone, Transform fallback) {
            for (AnimationFrame frame : this.frames) {
                Transform transform = frame.transformOrNull(bone);
                if (transform != null) {
                    return transform;
                }
            }
            return fallback;
        }

        public boolean hasTransform(Bone bone) {
            for (AnimationFrame frame : this.frames) {
                if (frame.transformOrNull(bone) != null) {
                    return true;
                }
            }
            return false;
        }

        public Transform lastFrameTransform(Bone bone, Transform fallback) {
            if (this.frames.isEmpty()) {
                return fallback;
            }

            return this.frames.get(this.frames.size() - 1).transformOrFallback(bone, fallback);
        }

        public Transform firstFrameTransformOrNull(Bone bone) {
            if (this.frames.isEmpty()) {
                return null;
            }

            return this.frames.get(0).transformOrNull(bone);
        }

        private float loopedFrame(float absoluteFrame) {
            int lastFrame = this.frames.get(this.frames.size() - 1).frame();
            float duration = Math.max(1.0F, lastFrame - this.startFrame + 1.0F);
            float result = (absoluteFrame - this.startFrame) % duration;
            if (result < 0.0F) {
                result += duration;
            }
            return this.startFrame + result;
        }
    }

    private record AnimationSoundEvent(int frame, ResourceLocation sound, float volume, float pitch) {
        public static AnimationSoundEvent read(JsonObject json) {
            return new AnimationSoundEvent(
                    GsonHelper.getAsInt(json, "frame"),
                    ResourceLocation.parse(GsonHelper.getAsString(json, "sound")),
                    GsonHelper.getAsFloat(json, "volume", 1.0F),
                    GsonHelper.getAsFloat(json, "pitch", 1.0F)
            );
        }

        public void play() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }

            SoundEvent soundEvent = SoundEvent.createVariableRangeEvent(this.sound);
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(soundEvent, this.pitch, this.volume));
        }
    }

    private record AnimationFrame(
            int frame,
            Transform viewmodelCamera,
            Transform itemRoot,
            Transform itemOffhandRoot,
            Transform blockRoot,
            Transform blockOffhandRoot,
            Transform viewmodelArmR,
            Transform viewmodelArmL
    ) {
        public static AnimationFrame read(JsonObject json, Transform cameraBind, Transform itemBind, Transform offhandItemBind, Transform blockBind, Transform offhandBlockBind, Transform rightArmBind, Transform leftArmBind) {
            JsonObject bones = GsonHelper.getAsJsonObject(json, "bones");
            Transform viewmodelCamera = readBone(bones, Bone.VIEWMODEL_CAMERA);
            Transform itemRoot = readBone(bones, Bone.ITEM_ROOT);
            Transform itemOffhandRoot = readBone(bones, Bone.ITEM_OFFHAND_ROOT);
            Transform blockRoot = readBone(bones, Bone.BLOCK_ROOT);
            Transform blockOffhandRoot = readBone(bones, Bone.BLOCK_OFFHAND_ROOT);
            if (blockOffhandRoot == null) {
                blockOffhandRoot = itemOffhandRoot == null ? offhandBlockBind : itemOffhandRoot;
            }
            Transform viewmodelArmR = readBone(bones, Bone.VIEWMODEL_ARM_R);
            Transform viewmodelArmL = readBone(bones, Bone.VIEWMODEL_ARM_L);
            return new AnimationFrame(
                    GsonHelper.getAsInt(json, "frame", 0),
                    viewmodelCamera,
                    itemRoot,
                    itemOffhandRoot,
                    blockRoot,
                    blockOffhandRoot,
                    viewmodelArmR,
                    viewmodelArmL == null && leftArmBind == null ? null : viewmodelArmL
            );
        }

        public Transform transformOrFallback(Bone bone, Transform fallback) {
            Transform transform = this.transformOrNull(bone);
            return transform == null ? fallback : transform;
        }

        public Transform transformOrNull(Bone bone) {
            return switch (bone) {
                case VIEWMODEL_CAMERA -> this.viewmodelCamera;
                case ITEM_ROOT -> this.itemRoot;
                case ITEM_OFFHAND_ROOT -> this.itemOffhandRoot;
                case BLOCK_ROOT -> this.blockRoot;
                case BLOCK_OFFHAND_ROOT -> this.blockOffhandRoot;
                case VIEWMODEL_ARM_R -> this.viewmodelArmR;
                case VIEWMODEL_ARM_L -> this.viewmodelArmL;
            };
        }

        private static Transform readBone(JsonObject bones, Bone bone) {
            if (!bones.has(bone.jsonName())) {
                return null;
            }

            return Transform.read(GsonHelper.getAsJsonObject(bones, bone.jsonName()));
        }
    }
}
