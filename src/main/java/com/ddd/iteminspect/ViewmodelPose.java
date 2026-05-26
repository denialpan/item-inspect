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
    private final Map<ResourceLocation, AnimationProfile> profiles = new HashMap<>();
    private final Map<ResourceLocation, ResourceLocation> itemProfileRules = new HashMap<>();
    private final List<TagProfileRule> tagProfileRules = new ArrayList<>();
    private final Map<ResourceLocation, ResourceLocation> offhandItemProfileRules = new HashMap<>();
    private final List<TagProfileRule> offhandTagProfileRules = new ArrayList<>();
    private final List<BothHandsProfileRule> bothHandsProfileRules = new ArrayList<>();
    private final HandLayer mainHandLayer = new HandLayer(HandLayerSide.MAIN);
    private final HandLayer offhandLayer = new HandLayer(HandLayerSide.OFFHAND);
    private Animation animation = Animation.empty();
    private ResourceLocation fallbackProfileId;
    private ResourceLocation emptyHandsProfileId;
    private ResourceLocation activeProfileId;
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

    public boolean hasProfileFor(ItemStack stack) {
        return this.hasProfileFor(stack, false);
    }

    public boolean hasProfileFor(ItemStack stack, boolean allowEmptyHands) {
        return this.resolveProfile(stack, allowEmptyHands) != null;
    }

    public boolean hasSpecificProfileFor(ItemStack stack) {
        return !stack.isEmpty() && this.resolveSpecificProfile(stack) != null;
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
        if (this.state == State.PULLOUT || this.state == State.PUTAWAY || this.equipBlendWindowTick > 0) {
            return;
        }
        this.mainHandLayer.cancel();
        this.offhandLayer.cancel();
        if (!this.activateInspectProfile(mainHandStack, offhandStack, allowEmptyHands)) {
            return;
        }
        this.playClip(Clip.INSPECT, mainHandStack.isEmpty() ? offhandStack : mainHandStack, State.INSPECT);
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack) {
        this.onHotbarChanged(oldStack, newStack, false, false);
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
        this.onMainHandChanged(oldStack, newStack, oldAllowsEmptyHands, newAllowsEmptyHands);
    }

    public void onMainHandChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
        this.cancelAnimation();
        this.mainHandLayer.onHandChanged(oldStack, newStack, oldAllowsEmptyHands, newAllowsEmptyHands);
    }

    public void onOffhandChanged(ItemStack oldStack, ItemStack newStack) {
        this.cancelAnimation();
        this.offhandLayer.onHandChanged(oldStack, newStack, false, false);
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
        if (canPutAway && (this.state == State.READY || this.state == State.INSPECT || oldAllowsEmptyHands)) {
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
            this.state = State.READY;
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
            if (this.isCameraActive()) {
                for (Bone bone : Bone.values()) {
                    this.restartBlendFrom.put(bone, this.currentTransform(bone, this.bindFallback(bone), 0.0F));
                }
            } else if (this.equipBlendWindowTick > 0) {
                for (Bone bone : Bone.values()) {
                    this.restartBlendFrom.put(bone, this.bindFallback(bone));
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
        this.state = this.visualStack.isEmpty() && !this.visualStackWasEmpty ? State.IDLE : State.READY;
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
        ResourceLocation profileId = this.resolveProfile(stack, allowEmptyHands);
        return this.activateProfile(profileId);
    }

    private boolean activateInspectProfile(ItemStack mainHandStack, ItemStack offhandStack, boolean allowEmptyHands) {
        ResourceLocation profileId = this.resolveBothHandsProfile(mainHandStack, offhandStack);
        if (profileId == null) {
            profileId = mainHandStack.isEmpty()
                    ? this.resolveProfile(offhandStack, allowEmptyHands, HandLayerSide.OFFHAND)
                    : this.resolveProfile(mainHandStack, allowEmptyHands, HandLayerSide.MAIN);
        }
        return this.activateProfile(profileId);
    }

    private boolean activateProfile(ResourceLocation profileId) {
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

    private ResourceLocation resolveBothHandsProfile(ItemStack mainHandStack, ItemStack offhandStack) {
        if (mainHandStack.isEmpty() || offhandStack.isEmpty()) {
            return null;
        }

        for (BothHandsProfileRule rule : this.bothHandsProfileRules) {
            if (rule.matches(mainHandStack, offhandStack)) {
                return rule.profileId();
            }
        }

        return null;
    }

    private ResourceLocation resolveProfile(ItemStack stack, boolean allowEmptyHands) {
        return this.resolveProfile(stack, allowEmptyHands, HandLayerSide.MAIN);
    }

    private ResourceLocation resolveProfile(ItemStack stack, boolean allowEmptyHands, HandLayerSide side) {
        if (stack.isEmpty()) {
            return allowEmptyHands ? this.emptyHandsProfileId : null;
        }

        ResourceLocation profileId = this.resolveSpecificProfile(stack, side);
        return profileId == null ? this.fallbackProfileId : profileId;
    }

    private ResourceLocation resolveSpecificProfile(ItemStack stack) {
        return this.resolveSpecificProfile(stack, HandLayerSide.MAIN);
    }

    private ResourceLocation resolveSpecificProfile(ItemStack stack, HandLayerSide side) {
        if (side == HandLayerSide.OFFHAND) {
            ResourceLocation offhandProfile = this.resolveSpecificProfile(stack, this.offhandItemProfileRules, this.offhandTagProfileRules);
            if (offhandProfile != null) {
                return offhandProfile;
            }
        }

        return this.resolveSpecificProfile(stack, this.itemProfileRules, this.tagProfileRules);
    }

    private ResourceLocation resolveSpecificProfile(ItemStack stack, Map<ResourceLocation, ResourceLocation> itemRules, List<TagProfileRule> tagRules) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        ResourceLocation itemProfile = itemRules.get(itemId);
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
            this.state = this.visualStack.isEmpty() && !this.visualStackWasEmpty ? State.IDLE : State.READY;
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
        String fallbackKey = root.has("default") ? "default" : "fallback";
        if (root.has(fallbackKey) && !root.get(fallbackKey).isJsonNull()) {
            this.fallbackProfileId = ResourceLocation.parse(GsonHelper.getAsString(root, fallbackKey));
            this.ensureProfileLoaded(resourceManager, this.fallbackProfileId);
        }

        if (root.has("empty_hands") && !root.get("empty_hands").isJsonNull()) {
            this.emptyHandsProfileId = ResourceLocation.parse(GsonHelper.getAsString(root, "empty_hands"));
            this.ensureProfileLoaded(resourceManager, this.emptyHandsProfileId);
        }

        this.loadProfileRules(resourceManager, root, this.itemProfileRules, this.tagProfileRules);
        if (root.has("main_hand")) {
            this.loadProfileRules(resourceManager, GsonHelper.getAsJsonObject(root, "main_hand"), this.itemProfileRules, this.tagProfileRules);
        }
        if (root.has("offhand")) {
            this.loadProfileRules(resourceManager, GsonHelper.getAsJsonObject(root, "offhand"), this.offhandItemProfileRules, this.offhandTagProfileRules);
        }
        if (root.has("both_hands")) {
            JsonArray bothHands = GsonHelper.getAsJsonArray(root, "both_hands");
            for (JsonElement element : bothHands) {
                JsonObject ruleJson = element.getAsJsonObject();
                ResourceLocation profileId = ResourceLocation.parse(GsonHelper.getAsString(ruleJson, "profile"));
                this.bothHandsProfileRules.add(new BothHandsProfileRule(
                        HandMatcher.read(GsonHelper.getAsJsonObject(ruleJson, "main")),
                        HandMatcher.read(GsonHelper.getAsJsonObject(ruleJson, "offhand")),
                        profileId
                ));
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }
    }

    private void loadProfileRules(ResourceManager resourceManager, JsonObject root, Map<ResourceLocation, ResourceLocation> itemRules, List<TagProfileRule> tagRules) {
        if (root.has("items")) {
            JsonObject items = GsonHelper.getAsJsonObject(root, "items");
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                ResourceLocation itemId = ResourceLocation.parse(entry.getKey());
                ResourceLocation profileId = ResourceLocation.parse(entry.getValue().getAsString());
                itemRules.put(itemId, profileId);
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }

        if (root.has("tags")) {
            JsonArray tags = GsonHelper.getAsJsonArray(root, "tags");
            for (JsonElement element : tags) {
                JsonObject tagRule = element.getAsJsonObject();
                ResourceLocation tagId = ResourceLocation.parse(GsonHelper.getAsString(tagRule, "id"));
                ResourceLocation profileId = ResourceLocation.parse(GsonHelper.getAsString(tagRule, "profile"));
                tagRules.add(new TagProfileRule(TagKey.create(Registries.ITEM, tagId), profileId));
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }
    }

    private void ensureProfileLoaded(ResourceManager resourceManager, ResourceLocation profileId) {
        if (this.profiles.containsKey(profileId)) {
            return;
        }

        JsonObject profileRoot = this.readRequired(resourceManager, profileId);
        this.profiles.put(profileId, this.readProfile(resourceManager, profileId, profileRoot));
    }

    private AnimationProfile readProfile(ResourceManager resourceManager, ResourceLocation profileId, JsonObject root) {
        JsonObject clips = GsonHelper.getAsJsonObject(root, "clips");
        ResourceLocation inspectLocation = this.clipLocation(clips, Clip.INSPECT);
        JsonObject inspectRoot = this.readRequired(resourceManager, inspectLocation);
        ProfileBindPose bindPose = this.readBindPose(inspectRoot);

        EnumMap<Clip, Animation> profileAnimations = new EnumMap<>(Clip.class);
        profileAnimations.put(Clip.INSPECT, Animation.read(inspectRoot, bindPose.viewmodelCamera(), bindPose.itemRoot(), bindPose.itemOffhandRoot(), bindPose.blockRoot(), bindPose.viewmodelArmR(), bindPose.viewmodelArmL()));
        this.loadProfileClip(resourceManager, profileAnimations, bindPose, clips, Clip.PULLOUT);
        this.loadProfileClip(resourceManager, profileAnimations, bindPose, clips, Clip.PUTAWAY);

        LOGGER.info("Loaded viewmodel profile {}: inspect={} pullout={} putaway={}",
                profileId,
                profileAnimations.getOrDefault(Clip.INSPECT, Animation.empty()).length(),
                profileAnimations.getOrDefault(Clip.PULLOUT, Animation.empty()).length(),
                profileAnimations.getOrDefault(Clip.PUTAWAY, Animation.empty()).length());
        return new AnimationProfile(
                bindPose.viewmodelCamera(),
                bindPose.itemRoot(),
                bindPose.itemOffhandRoot(),
                bindPose.blockRoot(),
                bindPose.viewmodelArmR(),
                bindPose.viewmodelArmL(),
                profileAnimations
        );
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
            this.itemProfileRules.clear();
            this.tagProfileRules.clear();
            this.offhandItemProfileRules.clear();
            this.offhandTagProfileRules.clear();
            this.bothHandsProfileRules.clear();
            this.animation = Animation.empty();
            this.fallbackProfileId = null;
            this.emptyHandsProfileId = null;
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
        READY,
        INSPECT,
        PUTAWAY
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

    private record TagProfileRule(TagKey<Item> tag, ResourceLocation profileId) {
    }

    private record BothHandsProfileRule(HandMatcher mainHand, HandMatcher offhand, ResourceLocation profileId) {
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
        private int animationTick;
        private int nextSoundEventIndex;
        private boolean skipNextAnimationTick;

        private HandLayer(HandLayerSide side) {
            this.side = side;
        }

        private boolean isActive() {
            return this.state == State.PULLOUT || this.state == State.PUTAWAY;
        }

        private boolean controls(Bone bone) {
            return this.side.controls(bone);
        }

        private ItemStack visualStackOr(ItemStack fallback) {
            return this.visualStack.isEmpty() ? fallback : this.visualStack;
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
            if (ViewmodelPose.this.hasProfileFor(putawayStack, oldAllowsEmptyHands) && (this.state == State.READY || oldAllowsEmptyHands)) {
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
                this.state = stack.isEmpty() ? State.IDLE : State.READY;
            }
        }

        private boolean playClip(Clip clip, ItemStack stack, boolean allowEmptyHands, State targetState) {
            ResourceLocation profileId = ViewmodelPose.this.resolveProfile(stack, allowEmptyHands, this.side);
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
            this.playPendingSoundEvents(this.animation.startFrame() + this.animationTick);
            if (this.animationTick < this.animation.length()) {
                return;
            }

            if (this.currentClip == Clip.PULLOUT) {
                this.animationTick = 0;
                this.skipNextAnimationTick = false;
                this.state = this.visualStack.isEmpty() ? State.IDLE : State.READY;
                this.markEquipSuppressionWindow();
                return;
            }

            if (this.currentClip == Clip.PUTAWAY) {
                ItemStack nextStack = this.queuedPulloutStack.copy();
                boolean nextAllowsEmptyHands = this.queuedPulloutAllowsEmptyHands;
                this.queuedPulloutStack = ItemStack.EMPTY;
                this.queuedPulloutAllowsEmptyHands = false;
                this.resetPlayback();
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
            return this.animation.sample(bone, this.animationTick + partialTick, profileFallback);
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
        }

        private void cancel() {
            this.resetPlayback();
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.queuedPulloutAllowsEmptyHands = false;
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
            return this.frames.size();
        }

        public Transform sample(Bone bone, float framePosition, Transform fallback) {
            if (this.frames.isEmpty()) {
                return fallback;
            }

            float clampedFrame = this.loop ? positiveModulo(framePosition, this.frames.size()) : Math.min(framePosition, this.frames.size() - 1);
            int fromIndex = (int)Math.floor(clampedFrame);
            int toIndex = this.loop ? (fromIndex + 1) % this.frames.size() : Math.min(fromIndex + 1, this.frames.size() - 1);
            float alpha = clampedFrame - fromIndex;
            Transform from = this.frames.get(fromIndex).transformOrFallback(bone, fallback);
            Transform to = this.frames.get(toIndex).transformOrFallback(bone, fallback);
            return Transform.lerp(from, to, alpha);
        }

        private static float positiveModulo(float value, int modulo) {
            float result = value % modulo;
            return result < 0.0F ? result + modulo : result;
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
            Transform transform = switch (bone) {
                case VIEWMODEL_CAMERA -> this.viewmodelCamera;
                case ITEM_ROOT -> this.itemRoot;
                case ITEM_OFFHAND_ROOT -> this.itemOffhandRoot;
                case BLOCK_ROOT -> this.blockRoot;
                case VIEWMODEL_ARM_R -> this.viewmodelArmR;
                case VIEWMODEL_ARM_L -> this.viewmodelArmL;
                case LEFT_BLOCK_ROOT -> this.leftBlockRoot;
            };
            return transform == null ? fallback : transform;
        }

        private static Transform readBone(JsonObject bones, Bone bone) {
            if (!bones.has(bone.jsonName())) {
                return null;
            }

            return Transform.read(GsonHelper.getAsJsonObject(bones, bone.jsonName()));
        }
    }
}
