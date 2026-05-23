package com.ddd.iteminspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.GsonHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class ViewmodelPose implements ResourceManagerReloadListener {
    public static final ViewmodelPose INSTANCE = new ViewmodelPose();
    private static final ResourceLocation POSE_LOCATION = ResourceLocation.fromNamespaceAndPath(iteminspect.MODID, "viewmodel/default_pose.json");

    private Transform itemRoot = Transform.identity();
    private Transform blockRoot = Transform.identity();
    private Transform viewmodelArmR = Transform.identity();
    private Animation animation = Animation.empty();
    private int animationTick;
    private boolean playing;
    private boolean loaded;

    private ViewmodelPose() {
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
        return this.currentTransform(Bone.LEFT_VIEWMODEL_ARM_R, this.viewmodelArmR.mirroredTransform(), partialTick);
    }

    public boolean isLoaded() {
        return this.loaded;
    }

    public boolean isPlaying() {
        return this.playing;
    }

    public void restartAnimation() {
        if (!this.animation.isEmpty()) {
            this.animationTick = 0;
            this.playing = true;
        }
    }

    public void cancelAnimation() {
        this.animationTick = 0;
        this.playing = false;
    }

    public void tickAnimation() {
        if (!this.playing || this.animation.isEmpty()) {
            return;
        }

        this.animationTick++;
        if (this.animationTick >= this.animation.length()) {
            this.animationTick = this.animation.length() - 1;
            this.playing = false;
        }
    }

    private Transform currentTransform(Bone bone, Transform fallback, float partialTick) {
        if (!this.playing || this.animation.isEmpty()) {
            return fallback;
        }

        return this.animation.sample(bone, this.animationTick + partialTick, fallback);
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        Optional<Resource> resource = resourceManager.getResource(POSE_LOCATION);
        if (resource.isEmpty()) {
            iteminspect.LOGGER.warn("Missing viewmodel pose resource {}", POSE_LOCATION);
            this.loaded = false;
            return;
        }

        try (Reader reader = resource.get().openAsReader()) {
            this.loadFromJson(GsonHelper.parse(reader), POSE_LOCATION.toString());
        } catch (IOException | RuntimeException exception) {
            iteminspect.LOGGER.error("Failed to load viewmodel pose {}", POSE_LOCATION, exception);
            this.clear();
        }
    }

    private void loadFromJson(JsonObject root, String source) {
        try {
            JsonObject bones = GsonHelper.getAsJsonObject(root, "bones");
            this.itemRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "item_root"));
            this.blockRoot = Transform.read(GsonHelper.getAsJsonObject(bones, "block_root"));
            this.viewmodelArmR = Transform.read(GsonHelper.getAsJsonObject(bones, "viewmodel_arm_R"));
            this.animation = Animation.read(root, this.itemRoot, this.blockRoot, this.viewmodelArmR);
            this.playing = false;
            this.animationTick = 0;
            this.loaded = true;
            iteminspect.LOGGER.info("Loaded viewmodel pose {} with {} animation frame(s)", source, this.animation.length());
        } catch (RuntimeException exception) {
            iteminspect.LOGGER.error("Failed to parse viewmodel pose {}", source, exception);
            this.clear();
        }
    }

    private void clear() {
            this.itemRoot = Transform.identity();
            this.blockRoot = Transform.identity();
            this.viewmodelArmR = Transform.identity();
            this.animation = Animation.empty();
            this.playing = false;
            this.animationTick = 0;
            this.loaded = false;
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

    private enum Bone {
        ITEM_ROOT("item_root"),
        BLOCK_ROOT("block_root"),
        VIEWMODEL_ARM_R("viewmodel_arm_R"),
        LEFT_ITEM_ROOT("left_item_root"),
        LEFT_BLOCK_ROOT("left_block_root"),
        LEFT_VIEWMODEL_ARM_R("left_viewmodel_arm_R");

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

        public static Animation read(JsonObject root, Transform itemBind, Transform blockBind, Transform armBind) {
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
                frames.add(AnimationFrame.read(element.getAsJsonObject(), itemBind, blockBind, armBind));
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
            Transform itemRoot,
            Transform blockRoot,
            Transform viewmodelArmR,
            Transform leftItemRoot,
            Transform leftBlockRoot,
            Transform leftViewmodelArmR
    ) {
        public static AnimationFrame read(JsonObject json, Transform itemBind, Transform blockBind, Transform armBind) {
            JsonObject bones = GsonHelper.getAsJsonObject(json, "bones");
            Transform itemRoot = readBone(bones, Bone.ITEM_ROOT);
            Transform blockRoot = readBone(bones, Bone.BLOCK_ROOT);
            Transform viewmodelArmR = readBone(bones, Bone.VIEWMODEL_ARM_R);
            Transform effectiveItemRoot = itemRoot == null ? itemBind : itemRoot;
            Transform effectiveBlockRoot = blockRoot == null ? blockBind : blockRoot;
            Transform effectiveViewmodelArmR = viewmodelArmR == null ? armBind : viewmodelArmR;
            return new AnimationFrame(
                    GsonHelper.getAsInt(json, "frame", 0),
                    itemRoot,
                    blockRoot,
                    viewmodelArmR,
                    effectiveItemRoot.leftHandItemTransform(itemBind),
                    effectiveBlockRoot.leftHandBlockTransform(blockBind),
                    effectiveViewmodelArmR.mirroredTransform()
            );
        }

        public Transform transformOrFallback(Bone bone, Transform fallback) {
            Transform transform = switch (bone) {
                case ITEM_ROOT -> this.itemRoot;
                case BLOCK_ROOT -> this.blockRoot;
                case VIEWMODEL_ARM_R -> this.viewmodelArmR;
                case LEFT_ITEM_ROOT -> this.leftItemRoot;
                case LEFT_BLOCK_ROOT -> this.leftBlockRoot;
                case LEFT_VIEWMODEL_ARM_R -> this.leftViewmodelArmR;
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
