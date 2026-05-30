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
    private static final int RESTART_BLEND_TICKS = 4;
    private static final int CANCEL_BLEND_TICKS = 4;
    private static final int EQUIP_BLEND_WINDOW_TICKS = 6;

    private Transform viewmodelCamera = Transform.identity();
    private Transform itemRoot = Transform.identity();
    private Transform itemOffhandRoot = Transform.identity();
    private Transform blockRoot = Transform.identity();
    private Transform viewmodelArmR = Transform.identity();
    private Transform viewmodelArmL = Transform.identity();
    private final EnumMap<Clip, Animation> animations = new EnumMap<>(Clip.class);
    private final Map<ProfileReference, AnimationProfile> profiles = new HashMap<>();
    private final Map<String, ResourceLocation> profileAliases = new HashMap<>();
    private final Map<ResourceLocation, ProfileReference> itemProfileRules = new HashMap<>();
    private final List<TagProfileRule> tagProfileRules = new ArrayList<>();
    private final Map<ResourceLocation, ProfileReference> offhandItemProfileRules = new HashMap<>();
    private final List<TagProfileRule> offhandTagProfileRules = new ArrayList<>();
    private final List<BothHandsProfileRule> bothHandsProfileRules = new ArrayList<>();
    private final HandLayer mainHandLayer = new HandLayer(HandLayerSide.MAIN);
    private final HandLayer offhandLayer = new HandLayer(HandLayerSide.OFFHAND);
    private Animation animation = Animation.empty();
    private ProfileReference fallbackProfileId;
    private ProfileReference offhandFallbackProfileId;
    private ProfileReference bothHandsFallbackProfileId;
    private ProfileReference emptyHandsProfileId;
    private ProfileReference emptyBothHandsProfileId;
    private ProfileReference activeProfileId;
    private boolean visualStackWasEmpty;
    private State state = State.IDLE;
    private Clip currentClip = Clip.INSPECT;
    private ItemStack visualStack = ItemStack.EMPTY;
    private ItemStack queuedPulloutStack = ItemStack.EMPTY;
    private boolean queuedPulloutAllowsEmptyHands;
    private EnumMap<Bone, Transform> restartBlendFrom = new EnumMap<>(Bone.class);
    private EnumMap<Bone, Transform> cancelBlendFrom = new EnumMap<>(Bone.class);
    private int animationTick;
    private int cancelBlendTick;
    private int equipBlendWindowTick;
    private int offhandEquipBlendWindowTick;
    private int nextSoundEventIndex;
    private boolean skipNextAnimationTick;
    private boolean playing;
    private boolean loaded;

    private ViewmodelPose() {
    }

    public Transform viewmodelCamera(float partialTick) {
        return this.currentTransform(Bone.VIEWMODEL_CAMERA, this.viewmodelCamera, partialTick);
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
        return this.currentTransform(Bone.LEFT_BLOCK_ROOT, this.blockRoot.mirroredTransform(), partialTick);
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
        return this.isSharedPlaying() || this.mainHandLayer.isActive() || this.offhandLayer.isActive();
    }

    public boolean isCameraActive() {
        return this.isSharedPlaying() || !this.cancelBlendFrom.isEmpty();
    }

    public boolean shouldSuppressVanillaMainHandEquip() {
        return this.isSharedPlaying() || this.mainHandLayer.isActive() || this.equipBlendWindowTick > 0;
    }

    public boolean shouldSuppressVanillaOffhandEquip() {
        return this.isSharedPlaying() || this.offhandLayer.isActive() || this.offhandEquipBlendWindowTick > 0;
    }

    public boolean isSharedPlaying() {
        return this.playing && (this.state == State.PULLOUT || this.state == State.INSPECT || this.state == State.PUTAWAY);
    }

    public boolean shouldRenderEmptyOffhandArm() {
        return this.isSharedPlaying() && this.currentClip == Clip.INSPECT && this.emptyBothHandsProfileId != null
                && this.emptyBothHandsProfileId.equals(this.activeProfileId);
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
        return this.resolveProfile(stack, allowEmptyHands) != null;
    }

    public boolean hasSpecificProfileFor(ItemStack stack) {
        return !stack.isEmpty() && this.resolveSpecificProfile(stack) != null;
    }

    private boolean hasSettledVisualStack() {
        return !this.visualStack.isEmpty() || this.visualStackWasEmpty;
    }

    public ItemStack visualStackOr(ItemStack fallback) {
        if (this.mainHandLayer.isActive()) {
            return this.mainHandLayer.visualStackOr(fallback);
        }
        return this.visualStack.isEmpty() ? fallback : this.visualStack;
    }

    public ItemStack visualOffhandStackOr(ItemStack fallback) {
        return this.offhandLayer.visualStackOr(fallback);
    }

    public void startInspect(ItemStack stack) {
        this.startInspect(stack, false);
    }

    public void startInspect(ItemStack stack, boolean allowEmptyHands) {
        this.startInspect(stack, ItemStack.EMPTY, allowEmptyHands);
    }

    public void startInspect(ItemStack mainHandStack, ItemStack offhandStack, boolean allowEmptyHands) {
        if (this.state == State.PUTAWAY || this.equipBlendWindowTick > 0) {
            return;
        }
        EnumMap<Bone, Transform> restartBlend = this.isPlaying() ? this.captureCurrentTransforms() : new EnumMap<>(Bone.class);
        this.mainHandLayer.cancel();
        this.offhandLayer.cancel();
        if (!this.activateInspectProfile(mainHandStack, offhandStack, allowEmptyHands)) {
            return;
        }
        if (this.playClip(Clip.INSPECT, mainHandStack.isEmpty() ? offhandStack : mainHandStack, State.INSPECT)
                && !restartBlend.isEmpty()) {
            this.restartBlendFrom.clear();
            this.restartBlendFrom.putAll(restartBlend);
        }
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack) {
        this.onHotbarChanged(oldStack, newStack, false, false);
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
        this.onMainHandChanged(oldStack, newStack, oldAllowsEmptyHands, newAllowsEmptyHands);
    }

    public void onMainHandChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
        if (this.isSharedPlaying()) {
            this.mainHandLayer.primeRestartBlendFrom(this.captureControlledTransforms(HandLayerSide.MAIN));
            this.mainHandLayer.primeVisualStack(this.visualStackOr(oldStack));
            oldAllowsEmptyHands = oldAllowsEmptyHands || this.visualStackWasEmpty;
        }
        this.cancelAnimation();
        this.mainHandLayer.onHandChanged(oldStack, newStack, oldAllowsEmptyHands, newAllowsEmptyHands);
    }

    public void onOffhandChanged(ItemStack oldStack, ItemStack newStack) {
        if (this.isSharedPlaying()) {
            this.offhandLayer.primeRestartBlendFrom(this.captureControlledTransforms(HandLayerSide.OFFHAND));
            this.offhandLayer.primeVisualStack(this.visualOffhandStackOr(oldStack));
        }
        this.cancelAnimation();
        this.offhandLayer.onHandChanged(oldStack, newStack, false, false);
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

    private void onSharedHandChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
        this.queuedPulloutStack = newStack.copy();
        this.queuedPulloutAllowsEmptyHands = newAllowsEmptyHands;
        if (this.state == State.PUTAWAY) {
            return;
        }

        if (this.state == State.PULLOUT) {
            ItemStack putawayStack = this.visualStackOr(oldStack).copy();
            if (!this.startPutaway(putawayStack, this.visualStackWasEmpty || oldAllowsEmptyHands)) {
                this.visualStack = ItemStack.EMPTY;
                this.visualStackWasEmpty = false;
                this.state = State.IDLE;
                this.playing = false;
                this.animationTick = 0;
                this.restartBlendFrom.clear();
                this.startPullout(newStack, newAllowsEmptyHands);
            }
            return;
        }

        ItemStack putawayStack = this.visualStackOr(oldStack).copy();
        boolean canPutAway = this.hasProfileFor(putawayStack, this.visualStackWasEmpty || oldAllowsEmptyHands);
        if (canPutAway && (this.hasSettledVisualStack() || this.state == State.INSPECT || oldAllowsEmptyHands)) {
            if (this.startPutaway(putawayStack, this.visualStackWasEmpty || oldAllowsEmptyHands)) {
                return;
            }
            this.visualStack = ItemStack.EMPTY;
            this.visualStackWasEmpty = false;
            this.state = State.IDLE;
            this.startPullout(newStack, newAllowsEmptyHands);
            return;
        }

        this.startPullout(newStack, newAllowsEmptyHands);
    }

    private boolean startPutaway(ItemStack stack, boolean allowEmptyHands) {
        if (!this.activateProfile(stack, allowEmptyHands)) {
            return false;
        }

        return this.playClip(Clip.PUTAWAY, stack, State.PUTAWAY);
    }

    public void startPullout(ItemStack stack) {
        this.startPullout(stack, false);
    }

    public void startPullout(ItemStack stack, boolean allowEmptyHands) {
        if (!this.activateProfile(stack, allowEmptyHands)) {
            this.visualStack = ItemStack.EMPTY;
            this.visualStackWasEmpty = false;
            this.state = State.IDLE;
            return;
        }
        if (!this.playClip(Clip.PULLOUT, stack, State.PULLOUT)) {
            this.visualStack = stack.copy();
            this.visualStackWasEmpty = stack.isEmpty();
            this.state = State.IDLE;
        }
    }

    public void restartAnimation() {
        this.startInspect(ItemStack.EMPTY);
    }

    private boolean playClip(Clip clip, ItemStack stack, State targetState) {
        if (this.activeProfileId == null) {
            return false;
        }

        Animation nextAnimation = this.animations.getOrDefault(clip, Animation.empty());
        if (!nextAnimation.isEmpty()) {
            this.restartBlendFrom.clear();
            if (this.isPlaying()) {
                for (Bone bone : Bone.values()) {
                    this.restartBlendFrom.put(bone, this.currentTransform(bone, this.bindFallback(bone), 0.0F));
                }
            } else if (this.equipBlendWindowTick > 0) {
                for (Bone bone : Bone.values()) {
                    this.restartBlendFrom.put(bone, nextAnimation.firstFrameTransform(bone, this.bindFallback(bone)));
                }
            }
            this.cancelBlendFrom.clear();
            this.cancelBlendTick = 0;
            this.equipBlendWindowTick = 0;
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = true;
            this.currentClip = clip;
            this.animation = nextAnimation;
            this.state = targetState;
            this.visualStack = stack.copy();
            this.visualStackWasEmpty = stack.isEmpty();
            this.playing = true;
            this.playPendingSoundEvents(this.animation.startFrame());
            return true;
        }
        return false;
    }

    public void markEquipBlendWindow() {
        this.equipBlendWindowTick = EQUIP_BLEND_WINDOW_TICKS;
    }

    public void cancelAnimation() {
        if (this.state == State.PUTAWAY) {
            return;
        }
        if (!this.playing && !this.cancelBlendFrom.isEmpty()) {
            return;
        }

        if (this.isCameraActive()) {
            this.cancelBlendFrom.clear();
            this.cancelBlendFrom.put(Bone.VIEWMODEL_CAMERA, this.currentTransform(Bone.VIEWMODEL_CAMERA, this.viewmodelCamera, 0.0F));
            this.cancelBlendTick = 0;
        }
        this.animationTick = 0;
        this.nextSoundEventIndex = 0;
        this.skipNextAnimationTick = false;
        this.playing = false;
        this.state = State.IDLE;
        this.restartBlendFrom.clear();
    }

    public void cancelAllAnimations() {
        this.animationTick = 0;
        this.nextSoundEventIndex = 0;
        this.skipNextAnimationTick = false;
        this.cancelBlendTick = 0;
        this.equipBlendWindowTick = 0;
        this.offhandEquipBlendWindowTick = 0;
        this.playing = false;
        this.state = State.IDLE;
        this.currentClip = Clip.INSPECT;
        this.activeProfileId = null;
        this.visualStack = ItemStack.EMPTY;
        this.visualStackWasEmpty = false;
        this.queuedPulloutStack = ItemStack.EMPTY;
        this.queuedPulloutAllowsEmptyHands = false;
        this.restartBlendFrom.clear();
        this.cancelBlendFrom.clear();
        this.mainHandLayer.cancel();
        this.offhandLayer.cancel();
    }

    public void tickAnimation(ItemStack selectedStack) {
        if (!this.cancelBlendFrom.isEmpty()) {
            this.cancelBlendTick++;
            if (this.cancelBlendTick >= CANCEL_BLEND_TICKS) {
                this.cancelBlendFrom.clear();
                this.cancelBlendTick = 0;
            }
        }
        if (this.equipBlendWindowTick > 0) {
            this.equipBlendWindowTick--;
        }
        if (this.offhandEquipBlendWindowTick > 0) {
            this.offhandEquipBlendWindowTick--;
        }

        this.mainHandLayer.tick(selectedStack);
        this.offhandLayer.tick(ItemStack.EMPTY);

        if (!this.playing || this.animation.isEmpty()) {
            return;
        }

        if (this.skipNextAnimationTick) {
            this.skipNextAnimationTick = false;
            return;
        }

        this.animationTick++;
        this.playPendingSoundEvents(this.animation.startFrame() + this.animationTick);
        if (this.animationTick >= this.animation.length()) {
            this.finishCurrentClip(selectedStack);
        }
    }

    public void tickAnimation() {
        this.tickAnimation(ItemStack.EMPTY);
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

    private boolean activateProfile(ItemStack stack, boolean allowEmptyHands) {
        ProfileReference profileId = this.resolveProfile(stack, allowEmptyHands);
        return this.activateProfile(profileId);
    }

    private boolean activateInspectProfile(ItemStack mainHandStack, ItemStack offhandStack, boolean allowEmptyHands) {
        ProfileReference profileId = this.resolveBothHandsProfile(mainHandStack, offhandStack);
        if (profileId == null && mainHandStack.isEmpty() && offhandStack.isEmpty()) {
            profileId = this.emptyBothHandsProfileId;
        }
        if (profileId == null) {
            profileId = mainHandStack.isEmpty()
                    ? this.resolveProfile(offhandStack, allowEmptyHands, HandLayerSide.OFFHAND)
                    : this.resolveProfile(mainHandStack, allowEmptyHands, HandLayerSide.MAIN);
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

        AnimationProfile profile = this.profiles.get(profileId);
        if (profile == null) {
            LOGGER.warn("Missing resolved viewmodel profile {}", profileId);
            return false;
        }

        this.activeProfileId = profileId;
        this.viewmodelCamera = profile.viewmodelCamera();
        this.itemRoot = profile.itemRoot();
        this.itemOffhandRoot = profile.itemOffhandRoot();
        this.blockRoot = profile.blockRoot();
        this.viewmodelArmR = profile.viewmodelArmR();
        this.viewmodelArmL = profile.viewmodelArmL();
        this.animations.clear();
        this.animations.putAll(profile.animations());
        this.animation = this.animations.getOrDefault(this.currentClip, Animation.empty());
        return true;
    }

    private ProfileReference resolveBothHandsProfile(ItemStack mainHandStack, ItemStack offhandStack) {
        if (mainHandStack.isEmpty() || offhandStack.isEmpty()) {
            return null;
        }

        for (BothHandsProfileRule rule : this.bothHandsProfileRules) {
            if (rule.matches(mainHandStack, offhandStack)) {
                return rule.profileId();
            }
        }

        return this.bothHandsFallbackProfileId;
    }

    private ProfileReference resolveProfile(ItemStack stack, boolean allowEmptyHands) {
        return this.resolveProfile(stack, allowEmptyHands, HandLayerSide.MAIN);
    }

    private ProfileReference resolveProfile(ItemStack stack, boolean allowEmptyHands, HandLayerSide side) {
        if (stack.isEmpty()) {
            return allowEmptyHands ? this.emptyHandsProfileId : null;
        }

        ProfileReference profileId = this.resolveSpecificProfile(stack, side);
        if (profileId != null) {
            return profileId;
        }

        if (side == HandLayerSide.OFFHAND && this.offhandFallbackProfileId != null) {
            return this.offhandFallbackProfileId;
        }

        return this.fallbackProfileId;
    }

    private ProfileReference resolveSpecificProfile(ItemStack stack) {
        return this.resolveSpecificProfile(stack, HandLayerSide.MAIN);
    }

    private ProfileReference resolveSpecificProfile(ItemStack stack, HandLayerSide side) {
        if (side == HandLayerSide.OFFHAND) {
            ProfileReference offhandProfile = this.resolveSpecificProfile(stack, this.offhandItemProfileRules, this.offhandTagProfileRules);
            if (offhandProfile != null) {
                return offhandProfile;
            }
        }

        return this.resolveSpecificProfile(stack, this.itemProfileRules, this.tagProfileRules);
    }

    private ProfileReference resolveSpecificProfile(ItemStack stack, Map<ResourceLocation, ProfileReference> itemRules, List<TagProfileRule> tagRules) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        ProfileReference itemProfile = itemRules.get(itemId);
        if (itemProfile != null) {
            return itemProfile;
        }

        for (TagProfileRule rule : tagRules) {
            if (stack.is(rule.tag())) {
                return rule.profileId();
            }
        }

        return null;
    }

    private void finishCurrentClip(ItemStack selectedStack) {
        if (this.currentClip == Clip.PULLOUT) {
            if (!this.visualStackWasEmpty && !selectedStack.isEmpty() && !ItemStack.matches(this.visualStack, selectedStack)) {
                this.cancelAllAnimations();
                return;
            }
            this.playing = false;
            this.animationTick = 0;
            this.skipNextAnimationTick = false;
            this.restartBlendFrom.clear();
            this.state = State.IDLE;
            return;
        }

        if (this.currentClip == Clip.PUTAWAY) {
            this.playing = false;
            this.animationTick = 0;
            this.skipNextAnimationTick = false;
            this.restartBlendFrom.clear();
            this.visualStack = ItemStack.EMPTY;
            this.visualStackWasEmpty = false;
            this.state = State.IDLE;
            ItemStack nextStack = this.queuedPulloutStack.copy();
            boolean nextAllowsEmptyHands = this.queuedPulloutAllowsEmptyHands;
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.queuedPulloutAllowsEmptyHands = false;
            if (this.hasProfileFor(nextStack, nextAllowsEmptyHands) && (selectedStack.isEmpty() || ItemStack.matches(nextStack, selectedStack))) {
                this.startPullout(nextStack, nextAllowsEmptyHands);
            }
            return;
        }

        this.cancelAnimation();
    }

    private Transform currentTransform(Bone bone, Transform fallback, float partialTick) {
        if (bone == Bone.VIEWMODEL_CAMERA && !this.cancelBlendFrom.isEmpty() && !this.playing) {
            float alpha = Math.min((this.cancelBlendTick + partialTick) / CANCEL_BLEND_TICKS, 1.0F);
            Transform from = this.cancelBlendFrom.getOrDefault(bone, fallback);
            return Transform.lerp(from, fallback, smoothStep(alpha));
        }

        Transform transform = this.currentTransformWithoutRestartBlend(bone, fallback, partialTick);
        if (this.restartBlendFrom.isEmpty()) {
            return transform;
        }

        float alpha = Math.min((this.animationTick + partialTick) / RESTART_BLEND_TICKS, 1.0F);
        if (alpha >= 1.0F) {
            return transform;
        }

        Transform from = this.restartBlendFrom.getOrDefault(bone, fallback);
        return Transform.lerp(from, transform, smoothStep(alpha));
    }

    private Transform currentTransformWithoutRestartBlend(Bone bone, Transform fallback, float partialTick) {
        if (!this.playing || this.animation.isEmpty()) {
            Transform layered = this.layeredTransform(bone, fallback, partialTick);
            if (layered != null) {
                return layered;
            }
            return fallback;
        }

        return this.animation.sample(bone, this.animationTick + partialTick, fallback);
    }

    private Transform layeredTransform(Bone bone, Transform fallback, float partialTick) {
        if (this.mainHandLayer.controls(bone)) {
            return this.mainHandLayer.currentTransform(bone, fallback, partialTick);
        }
        if (this.offhandLayer.controls(bone)) {
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
            case VIEWMODEL_ARM_R -> this.viewmodelArmR;
            case VIEWMODEL_ARM_L -> this.viewmodelArmL;
            case LEFT_BLOCK_ROOT -> this.blockRoot.mirroredTransform();
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

    private EnumMap<Bone, Transform> captureCurrentTransforms() {
        EnumMap<Bone, Transform> transforms = new EnumMap<>(Bone.class);
        for (Bone bone : Bone.values()) {
            transforms.put(bone, this.currentTransform(bone, this.bindFallback(bone), 0.0F));
        }
        return transforms;
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
                    this.profiles.size(),
                    this.itemProfileRules.size(),
                    this.tagProfileRules.size());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to load viewmodel animations", exception);
            this.clear();
        }
    }

    private void loadProfileIndex(ResourceManager resourceManager, JsonObject root) {
        if (root.has("profiles")) {
            JsonObject aliases = GsonHelper.getAsJsonObject(root, "profiles");
            for (Map.Entry<String, JsonElement> entry : aliases.entrySet()) {
                this.profileAliases.put(entry.getKey(), ResourceLocation.parse(entry.getValue().getAsString()));
            }
        }

        JsonObject primaryRoot = root.has("primary") ? GsonHelper.getAsJsonObject(root, "primary") : null;
        JsonObject secondaryRoot = root.has("secondary") ? GsonHelper.getAsJsonObject(root, "secondary") : null;
        JsonObject bothRoot = root.has("both") ? GsonHelper.getAsJsonObject(root, "both") : null;

        JsonObject primaryFallbackRoot = primaryRoot == null ? root : primaryRoot;
        String fallbackKey = primaryFallbackRoot.has("default") ? "default" : "fallback";
        if (primaryFallbackRoot.has(fallbackKey) && !primaryFallbackRoot.get(fallbackKey).isJsonNull()) {
            this.fallbackProfileId = this.readProfileReference(primaryFallbackRoot, fallbackKey, ProfileContext.PRIMARY);
            this.ensureProfileLoaded(resourceManager, this.fallbackProfileId);
        }

        if (secondaryRoot != null && secondaryRoot.has("default") && !secondaryRoot.get("default").isJsonNull()) {
            this.offhandFallbackProfileId = this.readProfileReference(secondaryRoot, "default", ProfileContext.SECONDARY);
            this.ensureProfileLoaded(resourceManager, this.offhandFallbackProfileId);
        } else if (root.has("secondary_default") && !root.get("secondary_default").isJsonNull()) {
            this.offhandFallbackProfileId = this.readProfileReference(root, "secondary_default", ProfileContext.SECONDARY);
            this.ensureProfileLoaded(resourceManager, this.offhandFallbackProfileId);
        } else if (root.has("offhand_default") && !root.get("offhand_default").isJsonNull()) {
            this.offhandFallbackProfileId = this.readProfileReference(root, "offhand_default", ProfileContext.SECONDARY);
            this.ensureProfileLoaded(resourceManager, this.offhandFallbackProfileId);
        }

        if (bothRoot != null && bothRoot.has("default") && !bothRoot.get("default").isJsonNull()) {
            this.bothHandsFallbackProfileId = this.readProfileReference(bothRoot, "default", ProfileContext.BOTH);
            this.ensureProfileLoaded(resourceManager, this.bothHandsFallbackProfileId);
        } else if (root.has("both_default") && !root.get("both_default").isJsonNull()) {
            this.bothHandsFallbackProfileId = this.readProfileReference(root, "both_default", ProfileContext.BOTH);
            this.ensureProfileLoaded(resourceManager, this.bothHandsFallbackProfileId);
        } else if (root.has("both_hands_default") && !root.get("both_hands_default").isJsonNull()) {
            this.bothHandsFallbackProfileId = this.readProfileReference(root, "both_hands_default", ProfileContext.BOTH);
            this.ensureProfileLoaded(resourceManager, this.bothHandsFallbackProfileId);
        }

        if (bothRoot != null && bothRoot.has("empty") && !bothRoot.get("empty").isJsonNull()) {
            this.emptyBothHandsProfileId = this.readProfileReference(bothRoot, "empty", ProfileContext.BOTH);
            this.ensureProfileLoaded(resourceManager, this.emptyBothHandsProfileId);
        } else if (root.has("empty_both_hands") && !root.get("empty_both_hands").isJsonNull()) {
            this.emptyBothHandsProfileId = this.readProfileReference(root, "empty_both_hands", ProfileContext.BOTH);
            this.ensureProfileLoaded(resourceManager, this.emptyBothHandsProfileId);
        }

        if (root.has("empty_hands") && !root.get("empty_hands").isJsonNull()) {
            this.emptyHandsProfileId = this.readProfileReference(root, "empty_hands", ProfileContext.PRIMARY);
            this.ensureProfileLoaded(resourceManager, this.emptyHandsProfileId);
        }

        this.loadProfileRules(resourceManager, primaryFallbackRoot, this.itemProfileRules, this.tagProfileRules);
        if (root.has("main_hand")) {
            this.loadProfileRules(resourceManager, GsonHelper.getAsJsonObject(root, "main_hand"), this.itemProfileRules, this.tagProfileRules);
        }
        if (secondaryRoot != null) {
            this.loadProfileRules(resourceManager, secondaryRoot, this.offhandItemProfileRules, this.offhandTagProfileRules);
        }
        if (root.has("offhand")) {
            this.loadProfileRules(resourceManager, GsonHelper.getAsJsonObject(root, "offhand"), this.offhandItemProfileRules, this.offhandTagProfileRules);
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
                ProfileReference profileId = this.readProfileReference(ruleJson, "profile", ProfileContext.BOTH);
                this.bothHandsProfileRules.add(new BothHandsProfileRule(
                        HandMatcher.read(readHandMatcher(ruleJson, "primary", "main")),
                        HandMatcher.read(readHandMatcher(ruleJson, "secondary", "offhand")),
                        profileId
                ));
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }
    }

    private static JsonObject readHandMatcher(JsonObject root, String preferredKey, String legacyKey) {
        if (root.has(preferredKey)) {
            return GsonHelper.getAsJsonObject(root, preferredKey);
        }
        return GsonHelper.getAsJsonObject(root, legacyKey);
    }

    private void loadProfileRules(ResourceManager resourceManager, JsonObject root, Map<ResourceLocation, ProfileReference> itemRules, List<TagProfileRule> tagRules) {
        ProfileContext context = itemRules == this.offhandItemProfileRules ? ProfileContext.SECONDARY : ProfileContext.PRIMARY;
        if (root.has("items")) {
            JsonObject items = GsonHelper.getAsJsonObject(root, "items");
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                ResourceLocation itemId = ResourceLocation.parse(entry.getKey());
                ProfileReference profileId = this.parseProfileReference(entry.getValue().getAsString(), context);
                itemRules.put(itemId, profileId);
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }

        if (root.has("tags")) {
            JsonArray tags = GsonHelper.getAsJsonArray(root, "tags");
            for (JsonElement element : tags) {
                JsonObject tagRule = element.getAsJsonObject();
                ResourceLocation tagId = ResourceLocation.parse(GsonHelper.getAsString(tagRule, "id"));
                ProfileReference profileId = this.readProfileReference(tagRule, "profile", context);
                tagRules.add(new TagProfileRule(TagKey.create(Registries.ITEM, tagId), profileId));
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }
    }

    private ProfileReference readProfileReference(JsonObject root, String key, ProfileContext context) {
        return this.parseProfileReference(GsonHelper.getAsString(root, key), context);
    }

    private ProfileReference parseProfileReference(String value, ProfileContext context) {
        ResourceLocation location = this.profileAliases.get(value);
        if (location == null) {
            location = ResourceLocation.parse(value);
        }
        return new ProfileReference(location, context);
    }

    private void ensureProfileLoaded(ResourceManager resourceManager, ProfileReference profileId) {
        if (this.profiles.containsKey(profileId)) {
            return;
        }

        JsonObject profileRoot = this.readRequired(resourceManager, profileId.location());
        this.profiles.put(profileId, this.readProfile(resourceManager, profileId, profileRoot));
    }

    private AnimationProfile readProfile(ResourceManager resourceManager, ProfileReference profileId, JsonObject root) {
        JsonObject clips = this.readContextClips(root, profileId.context());
        ResourceLocation inspectLocation = this.clipLocation(clips, Clip.INSPECT);
        JsonObject inspectRoot = this.readRequired(resourceManager, inspectLocation);
        ProfileBindPose bindPose = this.readBindPose(inspectRoot);

        EnumMap<Clip, Animation> profileAnimations = new EnumMap<>(Clip.class);
        profileAnimations.put(Clip.INSPECT, Animation.read(inspectRoot, bindPose.viewmodelCamera(), bindPose.itemRoot(), bindPose.itemOffhandRoot(), bindPose.blockRoot(), bindPose.viewmodelArmR(), bindPose.viewmodelArmL()));
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
                effectiveBindPose.viewmodelArmR(),
                effectiveBindPose.viewmodelArmL(),
                profileAnimations
        );
    }

    private JsonObject readContextClips(JsonObject root, ProfileContext context) {
        JsonObject clips = GsonHelper.getAsJsonObject(root, "clips");
        if (clips.has("inspect")) {
            return clips;
        }

        String contextKey = context.configKey();
        if (!clips.has(contextKey)) {
            throw new IllegalArgumentException("Viewmodel profile is missing clip context '" + contextKey + "'");
        }
        return GsonHelper.getAsJsonObject(clips, contextKey);
    }

    private void loadProfileClip(ResourceManager resourceManager, EnumMap<Clip, Animation> profileAnimations, ProfileBindPose bindPose, JsonObject clips, Clip clip) {
        ResourceLocation location = this.clipLocation(clips, clip);
        JsonObject root = this.readFirstExisting(resourceManager, location);
        profileAnimations.put(clip, root == null ? Animation.empty() : Animation.read(root, bindPose.viewmodelCamera(), bindPose.itemRoot(), bindPose.itemOffhandRoot(), bindPose.blockRoot(), bindPose.viewmodelArmR(), bindPose.viewmodelArmL()));
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
        Transform viewmodelCamera = readOptionalBone(bones, "viewmodel_camera", Transform.identity());
        Transform itemRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "item_root"));
        Transform itemOffhandRoot = readOptionalBone(bones, "item_offhand_root", itemRoot.leftHandItemTransform(itemRoot));
        Transform blockRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "block_root"));
        Transform viewmodelArmR = Transform.read(GsonHelper.getAsJsonObject(bones, "viewmodel_arm_R"));
        Transform viewmodelArmL = readOptionalBone(bones, "viewmodel_arm_L", viewmodelArmR.mirroredTransform());
        return new ProfileBindPose(viewmodelCamera, itemRoot, itemOffhandRoot, blockRoot, viewmodelArmR, viewmodelArmL);
    }

    private void clear() {
            this.viewmodelCamera = Transform.identity();
            this.itemRoot = Transform.identity();
            this.itemOffhandRoot = Transform.identity();
            this.blockRoot = Transform.identity();
            this.viewmodelArmR = Transform.identity();
            this.viewmodelArmL = Transform.identity();
            this.animations.clear();
            this.profiles.clear();
            this.profileAliases.clear();
            this.itemProfileRules.clear();
            this.tagProfileRules.clear();
            this.offhandItemProfileRules.clear();
            this.offhandTagProfileRules.clear();
            this.bothHandsProfileRules.clear();
            this.animation = Animation.empty();
            this.fallbackProfileId = null;
            this.offhandFallbackProfileId = null;
            this.bothHandsFallbackProfileId = null;
            this.emptyHandsProfileId = null;
            this.emptyBothHandsProfileId = null;
            this.activeProfileId = null;
            this.visualStackWasEmpty = false;
            this.state = State.IDLE;
            this.currentClip = Clip.INSPECT;
            this.visualStack = ItemStack.EMPTY;
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.queuedPulloutAllowsEmptyHands = false;
            this.playing = false;
            this.animationTick = 0;
            this.skipNextAnimationTick = false;
            this.restartBlendFrom.clear();
            this.cancelBlendFrom.clear();
            this.cancelBlendTick = 0;
            this.equipBlendWindowTick = 0;
            this.nextSoundEventIndex = 0;
            this.loaded = false;
    }

    private static Transform readOptionalBone(JsonObject bones, String name, Transform fallback) {
        if (!bones.has(name)) {
            return fallback;
        }

        return Transform.read(GsonHelper.getAsJsonObject(bones, name));
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

        public Transform leftHandItemTransform(Transform bindPose) {
            return this.mirroredTransform();
        }

        public Transform leftHandBlockTransform(Transform bindPose) {
            return this.mirroredTransform();
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
        VIEWMODEL_ARM_R("viewmodel_arm_R"),
        VIEWMODEL_ARM_L("viewmodel_arm_L"),
        LEFT_BLOCK_ROOT("left_block_root");

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

    private record BothHandsProfileRule(HandMatcher mainHand, HandMatcher offhand, ProfileReference profileId) {
        private boolean matches(ItemStack mainHandStack, ItemStack offhandStack) {
            return this.mainHand.matches(mainHandStack) && this.offhand.matches(offhandStack);
        }
    }

    private record HandMatcher(ResourceLocation itemId, TagKey<Item> tag) {
        private static HandMatcher read(JsonObject json) {
            ResourceLocation itemId = json.has("item") ? ResourceLocation.parse(GsonHelper.getAsString(json, "item")) : null;
            TagKey<Item> tag = json.has("tag")
                    ? TagKey.create(Registries.ITEM, ResourceLocation.parse(GsonHelper.getAsString(json, "tag")))
                    : null;
            if (itemId == null && tag == null) {
                throw new IllegalArgumentException("Hand matcher must define either 'item' or 'tag'");
            }
            return new HandMatcher(itemId, tag);
        }

        private boolean matches(ItemStack stack) {
            if (stack.isEmpty()) {
                return false;
            }
            if (this.itemId != null && this.itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                return true;
            }
            return this.tag != null && stack.is(this.tag);
        }
    }

    private record ProfileBindPose(
            Transform viewmodelCamera,
            Transform itemRoot,
            Transform itemOffhandRoot,
            Transform blockRoot,
            Transform viewmodelArmR,
            Transform viewmodelArmL
    ) {
        private ProfileBindPose withFirstFrameFallbacks(EnumMap<Clip, Animation> animations) {
            return new ProfileBindPose(
                    this.viewmodelCamera,
                    this.firstFrameTransform(animations, Bone.ITEM_ROOT, this.itemRoot),
                    this.firstFrameTransform(animations, Bone.ITEM_OFFHAND_ROOT, this.itemOffhandRoot),
                    this.firstFrameTransform(animations, Bone.BLOCK_ROOT, this.blockRoot),
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
                case VIEWMODEL_ARM_R -> this.viewmodelArmR;
                case VIEWMODEL_ARM_L -> this.viewmodelArmL;
                case LEFT_BLOCK_ROOT -> this.blockRoot.mirroredTransform();
            };
        }
    }

    private final class HandLayer {
        private final HandLayerSide side;
        private AnimationProfile profile;
        private Animation animation = Animation.empty();
        private Clip currentClip = Clip.PULLOUT;
        private State state = State.IDLE;
        private ItemStack visualStack = ItemStack.EMPTY;
        private ItemStack queuedPulloutStack = ItemStack.EMPTY;
        private boolean queuedPulloutAllowsEmptyHands;
        private final EnumMap<Bone, Transform> restartBlendFrom = new EnumMap<>(Bone.class);
        private int animationTick;
        private int nextSoundEventIndex;
        private boolean skipNextAnimationTick;

        private HandLayer(HandLayerSide side) {
            this.side = side;
        }

        private boolean isActive() {
            return this.state == State.PULLOUT || this.state == State.PUTAWAY;
        }

        private boolean isPulloutActive() {
            return this.state == State.PULLOUT;
        }

        private boolean controls(Bone bone) {
            return this.side.controls(bone);
        }

        private ItemStack visualStackOr(ItemStack fallback) {
            return this.visualStack.isEmpty() ? fallback : this.visualStack;
        }

        private boolean hasSettledVisualStack() {
            return !this.visualStack.isEmpty();
        }

        private void rememberSettledStack(ItemStack stack, boolean allowEmptyHands) {
            if (!ViewmodelPose.this.hasProfileFor(stack, allowEmptyHands)) {
                return;
            }

            this.profile = null;
            this.animation = Animation.empty();
            this.currentClip = Clip.PULLOUT;
            this.state = State.IDLE;
            this.visualStack = stack.copy();
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.queuedPulloutAllowsEmptyHands = false;
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = false;
            this.restartBlendFrom.clear();
        }

        private void onHandChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
            this.queuedPulloutStack = newStack.copy();
            this.queuedPulloutAllowsEmptyHands = newAllowsEmptyHands;
            if (this.state == State.PUTAWAY) {
                return;
            }

            if (this.state == State.PULLOUT) {
                ItemStack putawayStack = this.visualStackOr(oldStack).copy();
                if (!this.startPutaway(putawayStack, oldAllowsEmptyHands)) {
                    this.resetPlayback();
                    this.startPullout(newStack, newAllowsEmptyHands);
                }
                return;
            }

            ItemStack putawayStack = this.visualStackOr(oldStack).copy();
            if (ViewmodelPose.this.hasProfileFor(putawayStack, oldAllowsEmptyHands) && (this.hasSettledVisualStack() || oldAllowsEmptyHands)) {
                if (this.startPutaway(putawayStack, oldAllowsEmptyHands)) {
                    return;
                }
            }

            this.resetPlayback();
            this.startPullout(newStack, newAllowsEmptyHands);
        }

        private boolean startPutaway(ItemStack stack, boolean allowEmptyHands) {
            return this.playClip(Clip.PUTAWAY, stack, allowEmptyHands, State.PUTAWAY);
        }

        private void startPullout(ItemStack stack, boolean allowEmptyHands) {
            if (!this.playClip(Clip.PULLOUT, stack, allowEmptyHands, State.PULLOUT)) {
                this.visualStack = stack.copy();
                this.state = State.IDLE;
            }
        }

        private boolean playClip(Clip clip, ItemStack stack, boolean allowEmptyHands, State targetState) {
            ProfileReference profileId = ViewmodelPose.this.resolveProfile(stack, allowEmptyHands, this.side);
            if (profileId == null) {
                return false;
            }

            AnimationProfile nextProfile = ViewmodelPose.this.profiles.get(profileId);
            if (nextProfile == null) {
                LOGGER.warn("Missing resolved viewmodel profile {}", profileId);
                return false;
            }

            Animation nextAnimation = nextProfile.animations().getOrDefault(clip, Animation.empty());
            if (nextAnimation.isEmpty()) {
                return false;
            }

            if (this.isActive() && this.restartBlendFrom.isEmpty()) {
                this.captureRestartBlendFrom();
            }
            this.profile = nextProfile;
            this.animation = nextAnimation;
            this.currentClip = clip;
            this.state = targetState;
            this.visualStack = stack.copy();
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = true;
            this.playPendingSoundEvents(this.animation.startFrame());
            return true;
        }

        private void tick(ItemStack selectedStack) {
            if (!this.isActive() || this.animation.isEmpty()) {
                return;
            }

            if (this.skipNextAnimationTick) {
                this.skipNextAnimationTick = false;
                return;
            }

            this.animationTick++;
            if (this.animationTick >= RESTART_BLEND_TICKS) {
                this.restartBlendFrom.clear();
            }
            this.playPendingSoundEvents(this.animation.startFrame() + this.animationTick);
            if (this.animationTick < this.animation.length()) {
                return;
            }

            if (this.currentClip == Clip.PULLOUT) {
                this.animationTick = 0;
                this.skipNextAnimationTick = false;
                this.restartBlendFrom.clear();
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
                this.restartBlendFrom.clear();
                if (ViewmodelPose.this.hasProfileFor(nextStack, nextAllowsEmptyHands)) {
                    this.startPullout(nextStack, nextAllowsEmptyHands);
                }
            }
        }

        private Transform currentTransform(Bone bone, Transform fallback, float partialTick) {
            if (!this.isActive() || this.animation.isEmpty() || this.profile == null) {
                return fallback;
            }

            Transform profileFallback = this.profile.bindFallback(bone);
            Transform transform = this.animation.sample(bone, this.animationTick + partialTick, profileFallback);
            if (this.restartBlendFrom.isEmpty()) {
                return transform;
            }

            float alpha = Math.min((this.animationTick + partialTick) / RESTART_BLEND_TICKS, 1.0F);
            if (alpha >= 1.0F) {
                return transform;
            }

            Transform from = this.restartBlendFrom.getOrDefault(bone, fallback);
            return Transform.lerp(from, transform, smoothStep(alpha));
        }

        private void captureRestartBlendFrom() {
            this.restartBlendFrom.clear();
            this.restartBlendFrom.putAll(this.captureCurrentControlledTransforms());
        }

        private void primeRestartBlendFrom(EnumMap<Bone, Transform> transforms) {
            this.restartBlendFrom.clear();
            this.restartBlendFrom.putAll(transforms);
        }

        private void primeVisualStack(ItemStack stack) {
            this.visualStack = stack.copy();
        }

        private EnumMap<Bone, Transform> captureCurrentControlledTransforms() {
            EnumMap<Bone, Transform> transforms = new EnumMap<>(Bone.class);
            for (Bone bone : Bone.values()) {
                if (!this.controls(bone)) {
                    continue;
                }

                transforms.put(bone, this.currentTransformWithoutRestartBlend(bone));
            }
            return transforms;
        }

        private Transform currentTransformWithoutRestartBlend(Bone bone) {
            Transform fallback = this.profile == null ? ViewmodelPose.this.bindFallback(bone) : this.profile.bindFallback(bone);
            if (!this.isActive() || this.animation.isEmpty() || this.profile == null) {
                return fallback;
            }

            return this.animation.sample(bone, this.animationTick, fallback);
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
            this.animationTick = 0;
            this.nextSoundEventIndex = 0;
            this.skipNextAnimationTick = false;
            this.restartBlendFrom.clear();
        }

        private void cancel() {
            this.resetPlayback();
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.queuedPulloutAllowsEmptyHands = false;
            this.restartBlendFrom.clear();
        }

        private void markEquipSuppressionWindow() {
            if (this.side == HandLayerSide.MAIN) {
                ViewmodelPose.this.equipBlendWindowTick = EQUIP_BLEND_WINDOW_TICKS;
            } else {
                ViewmodelPose.this.offhandEquipBlendWindowTick = EQUIP_BLEND_WINDOW_TICKS;
            }
        }
    }

    private enum HandLayerSide {
        MAIN,
        OFFHAND;

        private boolean controls(Bone bone) {
            return switch (this) {
                case MAIN -> bone == Bone.ITEM_ROOT || bone == Bone.BLOCK_ROOT || bone == Bone.VIEWMODEL_ARM_R;
                case OFFHAND -> bone == Bone.ITEM_OFFHAND_ROOT || bone == Bone.LEFT_BLOCK_ROOT || bone == Bone.VIEWMODEL_ARM_L;
            };
        }

    }

    private record Animation(boolean loop, int startFrame, List<AnimationFrame> frames, List<AnimationSoundEvent> soundEvents) {
        public static Animation empty() {
            return new Animation(false, 0, List.of(), List.of());
        }

        public static Animation read(JsonObject root, Transform cameraBind, Transform itemBind, Transform offhandItemBind, Transform blockBind, Transform rightArmBind, Transform leftArmBind) {
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
                frames.add(AnimationFrame.read(element.getAsJsonObject(), cameraBind, itemBind, offhandItemBind, blockBind, rightArmBind, leftArmBind));
            }
            frames.sort(Comparator.comparingInt(AnimationFrame::frame));
            List<AnimationSoundEvent> soundEvents = readSoundEvents(animationJson);
            int startFrame = GsonHelper.getAsInt(animationJson, "start_frame", frames.isEmpty() ? 0 : frames.get(0).frame());
            return frames.isEmpty() ? empty() : new Animation(loop, startFrame, List.copyOf(frames), soundEvents);
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
            Transform viewmodelArmR,
            Transform viewmodelArmL,
            Transform leftBlockRoot
    ) {
        public static AnimationFrame read(JsonObject json, Transform cameraBind, Transform itemBind, Transform offhandItemBind, Transform blockBind, Transform rightArmBind, Transform leftArmBind) {
            JsonObject bones = GsonHelper.getAsJsonObject(json, "bones");
            Transform viewmodelCamera = readBone(bones, Bone.VIEWMODEL_CAMERA);
            Transform itemRoot = readBone(bones, Bone.ITEM_ROOT);
            Transform itemOffhandRoot = readBone(bones, Bone.ITEM_OFFHAND_ROOT);
            Transform blockRoot = readBone(bones, Bone.BLOCK_ROOT);
            Transform viewmodelArmR = readBone(bones, Bone.VIEWMODEL_ARM_R);
            Transform viewmodelArmL = readBone(bones, Bone.VIEWMODEL_ARM_L);
            Transform effectiveBlockRoot = blockRoot == null ? blockBind : blockRoot;
            return new AnimationFrame(
                    GsonHelper.getAsInt(json, "frame", 0),
                    viewmodelCamera,
                    itemRoot,
                    itemOffhandRoot,
                    blockRoot,
                    viewmodelArmR,
                    viewmodelArmL == null && leftArmBind == null ? null : viewmodelArmL,
                    effectiveBlockRoot.leftHandBlockTransform(blockBind)
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
                case VIEWMODEL_ARM_R -> this.viewmodelArmR;
                case VIEWMODEL_ARM_L -> this.viewmodelArmL;
                case LEFT_BLOCK_ROOT -> this.leftBlockRoot;
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
