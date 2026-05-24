package com.ddd.iteminspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
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
    private Transform blockRoot = Transform.identity();
    private Transform viewmodelArmR = Transform.identity();
    private Transform viewmodelArmL = Transform.identity();
    private final EnumMap<Clip, Animation> animations = new EnumMap<>(Clip.class);
    private final Map<ResourceLocation, AnimationProfile> profiles = new HashMap<>();
    private final Map<ResourceLocation, ResourceLocation> itemProfileRules = new HashMap<>();
    private final List<TagProfileRule> tagProfileRules = new ArrayList<>();
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
        return this.currentTransform(Bone.LEFT_ITEM_ROOT, this.itemRoot.leftHandItemTransform(this.itemRoot), partialTick);
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
        return this.playing && (this.state == State.PULLOUT || this.state == State.INSPECT || this.state == State.PUTAWAY);
    }

    public boolean isCameraActive() {
        return this.playing || !this.cancelBlendFrom.isEmpty();
    }

    public boolean shouldSuppressVanillaMainHandEquip() {
        return this.state != State.IDLE || this.equipBlendWindowTick > 0;
    }

    public boolean hasProfileFor(ItemStack stack) {
        return this.hasProfileFor(stack, false);
    }

    public boolean hasProfileFor(ItemStack stack, boolean allowEmptyHands) {
        return this.resolveProfile(stack, allowEmptyHands) != null;
    }

    public ItemStack visualStackOr(ItemStack fallback) {
        return this.visualStack.isEmpty() ? fallback : this.visualStack;
    }

    public void startInspect(ItemStack stack) {
        this.startInspect(stack, false);
    }

    public void startInspect(ItemStack stack, boolean allowEmptyHands) {
        if (this.state == State.PULLOUT || this.state == State.PUTAWAY || this.equipBlendWindowTick > 0) {
            return;
        }
        if (!this.activateProfile(stack, allowEmptyHands)) {
            return;
        }
        this.playClip(Clip.INSPECT, stack, State.INSPECT);
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack) {
        this.onHotbarChanged(oldStack, newStack, false, false);
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack, boolean oldAllowsEmptyHands, boolean newAllowsEmptyHands) {
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
            this.skipNextAnimationTick = true;
            this.currentClip = clip;
            this.animation = nextAnimation;
            this.state = targetState;
            this.visualStack = stack.copy();
            this.visualStackWasEmpty = stack.isEmpty();
            this.playing = true;
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
        this.skipNextAnimationTick = false;
        this.playing = false;
        this.state = this.visualStack.isEmpty() && !this.visualStackWasEmpty ? State.IDLE : State.READY;
        this.restartBlendFrom.clear();
    }

    public void cancelAllAnimations() {
        this.animationTick = 0;
        this.skipNextAnimationTick = false;
        this.cancelBlendTick = 0;
        this.equipBlendWindowTick = 0;
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

        if (!this.playing || this.animation.isEmpty()) {
            return;
        }

        if (this.skipNextAnimationTick) {
            this.skipNextAnimationTick = false;
            return;
        }

        this.animationTick++;
        if (this.animationTick >= this.animation.length()) {
            this.finishCurrentClip(selectedStack);
        }
    }

    public void tickAnimation() {
        this.tickAnimation(ItemStack.EMPTY);
    }

    private boolean activateProfile(ItemStack stack, boolean allowEmptyHands) {
        ResourceLocation profileId = this.resolveProfile(stack, allowEmptyHands);
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
        this.blockRoot = profile.blockRoot();
        this.viewmodelArmR = profile.viewmodelArmR();
        this.viewmodelArmL = profile.viewmodelArmL();
        this.animations.clear();
        this.animations.putAll(profile.animations());
        this.animation = this.animations.getOrDefault(this.currentClip, Animation.empty());
        return true;
    }

    private ResourceLocation resolveProfile(ItemStack stack, boolean allowEmptyHands) {
        if (stack.isEmpty()) {
            return allowEmptyHands ? this.emptyHandsProfileId : null;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        ResourceLocation itemProfile = this.itemProfileRules.get(itemId);
        if (itemProfile != null) {
            return itemProfile;
        }

        for (TagProfileRule rule : this.tagProfileRules) {
            if (stack.is(rule.tag())) {
                return rule.profileId();
            }
        }

        return this.fallbackProfileId;
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
            return fallback;
        }

        return this.animation.sample(bone, this.animationTick + partialTick, fallback);
    }

    private Transform bindFallback(Bone bone) {
        return switch (bone) {
            case VIEWMODEL_CAMERA -> this.viewmodelCamera;
            case ITEM_ROOT -> this.itemRoot;
            case BLOCK_ROOT -> this.blockRoot;
            case VIEWMODEL_ARM_R -> this.viewmodelArmR;
            case VIEWMODEL_ARM_L -> this.viewmodelArmL;
            case LEFT_ITEM_ROOT -> this.itemRoot.leftHandItemTransform(this.itemRoot);
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
        if (root.has("fallback") && !root.get("fallback").isJsonNull()) {
            this.fallbackProfileId = ResourceLocation.parse(GsonHelper.getAsString(root, "fallback"));
            this.ensureProfileLoaded(resourceManager, this.fallbackProfileId);
        }

        if (root.has("empty_hands") && !root.get("empty_hands").isJsonNull()) {
            this.emptyHandsProfileId = ResourceLocation.parse(GsonHelper.getAsString(root, "empty_hands"));
            this.ensureProfileLoaded(resourceManager, this.emptyHandsProfileId);
        }

        if (root.has("items")) {
            JsonObject items = GsonHelper.getAsJsonObject(root, "items");
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                ResourceLocation itemId = ResourceLocation.parse(entry.getKey());
                ResourceLocation profileId = ResourceLocation.parse(entry.getValue().getAsString());
                this.itemProfileRules.put(itemId, profileId);
                this.ensureProfileLoaded(resourceManager, profileId);
            }
        }

        if (root.has("tags")) {
            JsonArray tags = GsonHelper.getAsJsonArray(root, "tags");
            for (JsonElement element : tags) {
                JsonObject tagRule = element.getAsJsonObject();
                ResourceLocation tagId = ResourceLocation.parse(GsonHelper.getAsString(tagRule, "id"));
                ResourceLocation profileId = ResourceLocation.parse(GsonHelper.getAsString(tagRule, "profile"));
                this.tagProfileRules.add(new TagProfileRule(TagKey.create(Registries.ITEM, tagId), profileId));
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
        profileAnimations.put(Clip.INSPECT, Animation.read(inspectRoot, bindPose.viewmodelCamera(), bindPose.itemRoot(), bindPose.blockRoot(), bindPose.viewmodelArmR(), bindPose.viewmodelArmL()));
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
                bindPose.blockRoot(),
                bindPose.viewmodelArmR(),
                bindPose.viewmodelArmL(),
                profileAnimations
        );
    }

    private void loadProfileClip(ResourceManager resourceManager, EnumMap<Clip, Animation> profileAnimations, ProfileBindPose bindPose, JsonObject clips, Clip clip) {
        ResourceLocation location = this.clipLocation(clips, clip);
        JsonObject root = this.readFirstExisting(resourceManager, location);
        profileAnimations.put(clip, root == null ? Animation.empty() : Animation.read(root, bindPose.viewmodelCamera(), bindPose.itemRoot(), bindPose.blockRoot(), bindPose.viewmodelArmR(), bindPose.viewmodelArmL()));
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
        Transform blockRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "block_root"));
        Transform viewmodelArmR = Transform.read(GsonHelper.getAsJsonObject(bones, "viewmodel_arm_R"));
        Transform viewmodelArmL = readOptionalBone(bones, "viewmodel_arm_L", viewmodelArmR.mirroredTransform());
        return new ProfileBindPose(viewmodelCamera, itemRoot, blockRoot, viewmodelArmR, viewmodelArmL);
    }

    private void clear() {
            this.viewmodelCamera = Transform.identity();
            this.itemRoot = Transform.identity();
            this.blockRoot = Transform.identity();
            this.viewmodelArmR = Transform.identity();
            this.viewmodelArmL = Transform.identity();
            this.animations.clear();
            this.profiles.clear();
            this.itemProfileRules.clear();
            this.tagProfileRules.clear();
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
        BLOCK_ROOT("block_root"),
        VIEWMODEL_ARM_R("viewmodel_arm_R"),
        VIEWMODEL_ARM_L("viewmodel_arm_L"),
        LEFT_ITEM_ROOT("left_item_root"),
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

    private record ProfileBindPose(
            Transform viewmodelCamera,
            Transform itemRoot,
            Transform blockRoot,
            Transform viewmodelArmR,
            Transform viewmodelArmL
    ) {
    }

    private record AnimationProfile(
            Transform viewmodelCamera,
            Transform itemRoot,
            Transform blockRoot,
            Transform viewmodelArmR,
            Transform viewmodelArmL,
            EnumMap<Clip, Animation> animations
    ) {
    }

    private record Animation(boolean loop, List<AnimationFrame> frames) {
        public static Animation empty() {
            return new Animation(false, List.of());
        }

        public static Animation read(JsonObject root, Transform cameraBind, Transform itemBind, Transform blockBind, Transform rightArmBind, Transform leftArmBind) {
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
                frames.add(AnimationFrame.read(element.getAsJsonObject(), cameraBind, itemBind, blockBind, rightArmBind, leftArmBind));
            }
            frames.sort(Comparator.comparingInt(AnimationFrame::frame));
            return frames.isEmpty() ? empty() : new Animation(loop, List.copyOf(frames));
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

    private record AnimationFrame(
            int frame,
            Transform viewmodelCamera,
            Transform itemRoot,
            Transform blockRoot,
            Transform viewmodelArmR,
            Transform viewmodelArmL,
            Transform leftItemRoot,
            Transform leftBlockRoot
    ) {
        public static AnimationFrame read(JsonObject json, Transform cameraBind, Transform itemBind, Transform blockBind, Transform rightArmBind, Transform leftArmBind) {
            JsonObject bones = GsonHelper.getAsJsonObject(json, "bones");
            Transform viewmodelCamera = readBone(bones, Bone.VIEWMODEL_CAMERA);
            Transform itemRoot = readBone(bones, Bone.ITEM_ROOT);
            Transform blockRoot = readBone(bones, Bone.BLOCK_ROOT);
            Transform viewmodelArmR = readBone(bones, Bone.VIEWMODEL_ARM_R);
            Transform viewmodelArmL = readBone(bones, Bone.VIEWMODEL_ARM_L);
            Transform effectiveItemRoot = itemRoot == null ? itemBind : itemRoot;
            Transform effectiveBlockRoot = blockRoot == null ? blockBind : blockRoot;
            return new AnimationFrame(
                    GsonHelper.getAsInt(json, "frame", 0),
                    viewmodelCamera,
                    itemRoot,
                    blockRoot,
                    viewmodelArmR,
                    viewmodelArmL == null && leftArmBind == null ? null : viewmodelArmL,
                    effectiveItemRoot.leftHandItemTransform(itemBind),
                    effectiveBlockRoot.leftHandBlockTransform(blockBind)
            );
        }

        public Transform transformOrFallback(Bone bone, Transform fallback) {
            Transform transform = switch (bone) {
                case VIEWMODEL_CAMERA -> this.viewmodelCamera;
                case ITEM_ROOT -> this.itemRoot;
                case BLOCK_ROOT -> this.blockRoot;
                case VIEWMODEL_ARM_R -> this.viewmodelArmR;
                case VIEWMODEL_ARM_L -> this.viewmodelArmL;
                case LEFT_ITEM_ROOT -> this.leftItemRoot;
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
