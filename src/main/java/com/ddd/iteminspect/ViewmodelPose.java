package com.ddd.iteminspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

public final class ViewmodelPose implements ResourceManagerReloadListener {
    public static final ViewmodelPose INSTANCE = new ViewmodelPose();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MODID = "iteminspect";
    private static final ResourceLocation INSPECT_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/default/default_inspect.json");
    private static final ResourceLocation PULLOUT_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/default/default_pullout.json");
    private static final ResourceLocation PULLOUT_TYPO_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/default/defualt_pullout.json");
    private static final ResourceLocation PUTAWAY_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/default/default_putaway.json");
    private static final ResourceLocation LEGACY_INSPECT_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/default_inspect.json");
    private static final ResourceLocation LEGACY_PULLOUT_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/default_pullout.json");
    private static final ResourceLocation LEGACY_PULLOUT_TYPO_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/defualt_pullout.json");
    private static final ResourceLocation LEGACY_PUTAWAY_LOCATION = ResourceLocation.fromNamespaceAndPath(MODID, "viewmodel/default_putaway.json");
    private static final int RESTART_BLEND_TICKS = 4;
    private static final int CANCEL_BLEND_TICKS = 4;
    private static final int EQUIP_BLEND_WINDOW_TICKS = 6;

    private Transform viewmodelCamera = Transform.identity();
    private Transform itemRoot = Transform.identity();
    private Transform blockRoot = Transform.identity();
    private Transform viewmodelArmR = Transform.identity();
    private Transform viewmodelArmL = Transform.identity();
    private final EnumMap<Clip, Animation> animations = new EnumMap<>(Clip.class);
    private Animation animation = Animation.empty();
    private State state = State.IDLE;
    private Clip currentClip = Clip.INSPECT;
    private ItemStack visualStack = ItemStack.EMPTY;
    private ItemStack queuedPulloutStack = ItemStack.EMPTY;
    private EnumMap<Bone, Transform> restartBlendFrom = new EnumMap<>(Bone.class);
    private EnumMap<Bone, Transform> cancelBlendFrom = new EnumMap<>(Bone.class);
    private int animationTick;
    private int cancelBlendTick;
    private int equipBlendWindowTick;
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

    public ItemStack visualStackOr(ItemStack fallback) {
        return this.visualStack.isEmpty() ? fallback : this.visualStack;
    }

    public void startInspect(ItemStack stack) {
        if (this.state == State.PULLOUT || this.state == State.PUTAWAY) {
            return;
        }
        this.playClip(Clip.INSPECT, stack, State.INSPECT);
    }

    public void onHotbarChanged(ItemStack oldStack, ItemStack newStack) {
        this.queuedPulloutStack = newStack.copy();
        ItemStack putawayStack = !this.visualStack.isEmpty() ? this.visualStack.copy() : oldStack.copy();
        if (!putawayStack.isEmpty() && (this.state == State.PULLOUT || this.state == State.READY || this.state == State.INSPECT)) {
            if (this.playClip(Clip.PUTAWAY, putawayStack, State.PUTAWAY)) {
                return;
            }
            this.visualStack = ItemStack.EMPTY;
            this.state = State.IDLE;
            this.startPullout(newStack);
            return;
        }

        this.startPullout(newStack);
    }

    public void startPullout(ItemStack stack) {
        if (stack.isEmpty()) {
            this.visualStack = ItemStack.EMPTY;
            this.state = State.IDLE;
            return;
        }
        if (!this.playClip(Clip.PULLOUT, stack, State.PULLOUT)) {
            this.visualStack = stack.copy();
            this.state = State.READY;
        }
    }

    public void restartAnimation() {
        this.startInspect(ItemStack.EMPTY);
    }

    private boolean playClip(Clip clip, ItemStack stack, State targetState) {
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
            this.currentClip = clip;
            this.animation = nextAnimation;
            this.state = targetState;
            this.visualStack = stack.copy();
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
        this.playing = false;
        this.state = this.visualStack.isEmpty() ? State.IDLE : State.READY;
        this.restartBlendFrom.clear();
    }

    public void tickAnimation() {
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

        this.animationTick++;
        if (this.animationTick >= this.animation.length()) {
            this.finishCurrentClip();
        }
    }

    private void finishCurrentClip() {
        if (this.currentClip == Clip.PULLOUT) {
            this.playing = false;
            this.animationTick = 0;
            this.restartBlendFrom.clear();
            this.state = this.visualStack.isEmpty() ? State.IDLE : State.READY;
            return;
        }

        if (this.currentClip == Clip.PUTAWAY) {
            this.playing = false;
            this.animationTick = 0;
            this.restartBlendFrom.clear();
            this.visualStack = ItemStack.EMPTY;
            this.state = State.IDLE;
            ItemStack nextStack = this.queuedPulloutStack.copy();
            this.queuedPulloutStack = ItemStack.EMPTY;
            if (!nextStack.isEmpty()) {
                this.startPullout(nextStack);
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
            JsonObject inspectRoot = this.readFirstExisting(resourceManager, INSPECT_LOCATION, LEGACY_INSPECT_LOCATION);
            if (inspectRoot == null) {
                LOGGER.warn("Missing viewmodel inspect animation {}", INSPECT_LOCATION);
                return;
            }

            this.loadBindPose(inspectRoot);
            this.animations.put(Clip.INSPECT, Animation.read(inspectRoot, this.viewmodelCamera, this.itemRoot, this.blockRoot, this.viewmodelArmR, this.viewmodelArmL));
            this.loadOptionalClip(resourceManager, Clip.PULLOUT, PULLOUT_LOCATION, PULLOUT_TYPO_LOCATION, LEGACY_PULLOUT_LOCATION, LEGACY_PULLOUT_TYPO_LOCATION);
            this.loadOptionalClip(resourceManager, Clip.PUTAWAY, PUTAWAY_LOCATION, LEGACY_PUTAWAY_LOCATION);
            this.animation = this.animations.getOrDefault(Clip.INSPECT, Animation.empty());
            this.loaded = true;
            LOGGER.info("Loaded viewmodel animations: inspect={} pullout={} putaway={}",
                    this.animations.getOrDefault(Clip.INSPECT, Animation.empty()).length(),
                    this.animations.getOrDefault(Clip.PULLOUT, Animation.empty()).length(),
                    this.animations.getOrDefault(Clip.PUTAWAY, Animation.empty()).length());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to load viewmodel animations", exception);
            this.clear();
        }
    }

    private void loadOptionalClip(ResourceManager resourceManager, Clip clip, ResourceLocation... locations) {
        JsonObject root = this.readFirstExisting(resourceManager, locations);
        this.animations.put(clip, root == null ? Animation.empty() : Animation.read(root, this.viewmodelCamera, this.itemRoot, this.blockRoot, this.viewmodelArmR, this.viewmodelArmL));
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

    private void loadBindPose(JsonObject root) {
        JsonObject bones = GsonHelper.getAsJsonObject(root, "bones");
        this.viewmodelCamera = readOptionalBone(bones, "viewmodel_camera", Transform.identity());
        this.itemRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "item_root"));
        this.blockRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "block_root"));
        this.viewmodelArmR = Transform.read(GsonHelper.getAsJsonObject(bones, "viewmodel_arm_R"));
        this.viewmodelArmL = readOptionalBone(bones, "viewmodel_arm_L", this.viewmodelArmR.mirroredTransform());
    }

    private void clear() {
            this.viewmodelCamera = Transform.identity();
            this.itemRoot = Transform.identity();
            this.blockRoot = Transform.identity();
            this.viewmodelArmR = Transform.identity();
            this.viewmodelArmL = Transform.identity();
            this.animations.clear();
            this.animation = Animation.empty();
            this.state = State.IDLE;
            this.currentClip = Clip.INSPECT;
            this.visualStack = ItemStack.EMPTY;
            this.queuedPulloutStack = ItemStack.EMPTY;
            this.playing = false;
            this.animationTick = 0;
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
        PUTAWAY
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
