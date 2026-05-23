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

public final class ViewmodelRenderer {
    private ViewmodelRenderer() {
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
        if (leftMainHand) {
            pose.leftViewmodelArmR(event.getPartialTick()).apply(poseStack);
        } else {
            pose.viewmodelArmR(event.getPartialTick()).apply(poseStack);
        }
        EntityRenderer<?> renderer = minecraft.getEntityRenderDispatcher().getRenderer(minecraft.player);
        if (renderer instanceof PlayerRenderer playerRenderer) {
            if (leftMainHand) {
                playerRenderer.renderLeftHand(poseStack, event.getMultiBufferSource(), event.getPackedLight(), minecraft.player);
            } else {
                playerRenderer.renderRightHand(poseStack, event.getMultiBufferSource(), event.getPackedLight(), minecraft.player);
            }
        }
        poseStack.popPose();

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
    }
}
