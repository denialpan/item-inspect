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
        if (minecraft.player == null || minecraft.level == null || !pose.isLoaded() || !pose.isPlaying()) {
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
        if (minecraft.player == null || minecraft.level == null || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        ViewmodelPose pose = ViewmodelPose.INSTANCE;
        ItemStack stack = event.getItemStack();
        if (!pose.isLoaded() || !pose.isPlaying()) {
            return;
        }

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

            if (!stack.isEmpty()) {
                poseStack.pushPose();
                if (stack.getItem() instanceof BlockItem) {
                    if (leftMainHand) {
                        pose.leftBlockRoot(event.getPartialTick()).apply(poseStack);
                    } else {
                        pose.blockRoot(event.getPartialTick()).apply(poseStack);
                    }
                } else if (leftMainHand) {
                    pose.leftItemRoot(event.getPartialTick()).apply(poseStack);
                } else {
                    pose.itemRoot(event.getPartialTick()).apply(poseStack);
                }

                minecraft.getItemRenderer().renderStatic(
                        minecraft.player,
                        stack,
                        leftMainHand ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                        leftMainHand,
                        poseStack,
                        event.getMultiBufferSource(),
                        minecraft.level,
                        event.getPackedLight(),
                        OverlayTexture.NO_OVERLAY,
                        minecraft.player.getId()
                );
                poseStack.popPose();
            }
        } finally {
            poseStack.popPose();
        }
    }
}
