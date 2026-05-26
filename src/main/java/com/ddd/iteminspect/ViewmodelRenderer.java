package com.ddd.iteminspect;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

public final class ViewmodelRenderer {
    private ViewmodelRenderer() {
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        ViewmodelPose pose = ViewmodelPose.INSTANCE;
        if (minecraft.player == null || minecraft.level == null || !minecraft.options.bobView().get() || !pose.isLoaded() || !pose.isCameraActive()) {
            return;
        }

        ViewmodelPose.Transform camera = pose.viewmodelCamera((float)event.getPartialTick());
        double yaw = Math.asin(2.0D * (camera.qw() * camera.qy() - camera.qx() * camera.qz()));
        double pitch = Math.atan2(
                2.0D * (camera.qw() * camera.qx() + camera.qy() * camera.qz()),
                1.0D - 2.0D * (camera.qx() * camera.qx() + camera.qy() * camera.qy())
        );
        double roll = Math.atan2(
                2.0D * (camera.qw() * camera.qz() + camera.qx() * camera.qy()),
                1.0D - 2.0D * (camera.qy() * camera.qy() + camera.qz() * camera.qz())
        );

        event.setYaw(event.getYaw() - (float)Math.toDegrees(yaw));
        event.setPitch(event.getPitch() - (float)Math.toDegrees(pitch));
        event.setRoll(event.getRoll() - (float)Math.toDegrees(roll));
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        ViewmodelPose pose = ViewmodelPose.INSTANCE;
        if (!pose.isLoaded() || !pose.isPlaying()) {
            return;
        }

        if (event.getHand() == InteractionHand.OFF_HAND) {
            event.setCanceled(true);
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        ItemStack stack = pose.visualStackOr(event.getItemStack());
        ItemStack offhandStack = pose.visualOffhandStackOr(minecraft.player.getOffhandItem());
        event.setCanceled(true);

        boolean leftMainHand = minecraft.player.getMainArm() == HumanoidArm.LEFT;
        poseStack.pushPose();
        pose.viewmodelCamera(event.getPartialTick()).apply(poseStack);

        try {
            EntityRenderer<?> renderer = minecraft.getEntityRenderDispatcher().getRenderer(minecraft.player);
            if (renderer instanceof PlayerRenderer playerRenderer) {
                poseStack.pushPose();
                if (leftMainHand) {
                    pose.viewmodelArmL(event.getPartialTick()).mirroredTransform().apply(poseStack);
                } else {
                    pose.viewmodelArmR(event.getPartialTick()).apply(poseStack);
                }
                playerRenderer.renderRightHand(poseStack, event.getMultiBufferSource(), event.getPackedLight(), minecraft.player);
                poseStack.popPose();

                poseStack.pushPose();
                if (leftMainHand) {
                    pose.viewmodelArmR(event.getPartialTick()).mirroredTransform().apply(poseStack);
                } else {
                    pose.viewmodelArmL(event.getPartialTick()).apply(poseStack);
                }
                playerRenderer.renderLeftHand(poseStack, event.getMultiBufferSource(), event.getPackedLight(), minecraft.player);
                poseStack.popPose();
            }

            renderHeldItem(event, pose, stack, leftMainHand);
            renderHeldItem(event, pose, offhandStack, !leftMainHand);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderHeldItem(RenderHandEvent event, ViewmodelPose pose, ItemStack stack, boolean leftHand) {
        if (stack.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        if (stack.getItem() instanceof BlockItem) {
            if (leftHand) {
                pose.leftBlockRoot(event.getPartialTick()).apply(poseStack);
            } else {
                pose.blockRoot(event.getPartialTick()).apply(poseStack);
            }
        } else if (leftHand) {
            pose.leftItemRoot(event.getPartialTick()).apply(poseStack);
        } else {
            pose.itemRoot(event.getPartialTick()).apply(poseStack);
        }

        minecraft.getItemRenderer().renderStatic(
                minecraft.player,
                stack,
                leftHand ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                leftHand,
                poseStack,
                event.getMultiBufferSource(),
                minecraft.level,
                event.getPackedLight(),
                OverlayTexture.NO_OVERLAY,
                leftHand ? minecraft.player.getId() + 1 : minecraft.player.getId()
        );
        poseStack.popPose();
    }
}
