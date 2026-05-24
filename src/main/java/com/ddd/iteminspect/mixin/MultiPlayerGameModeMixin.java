package com.ddd.iteminspect.mixin;

import com.ddd.iteminspect.ViewmodelPose;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("RETURN"))
    private void iteminspect$cancelViewmodelAfterEntityAttack(Player player, Entity targetEntity, CallbackInfo callbackInfo) {
        ViewmodelPose.INSTANCE.cancelAnimation();
    }

    @Inject(method = "startDestroyBlock", at = @At("RETURN"))
    private void iteminspect$cancelViewmodelAfterStartDestroyBlock(BlockPos loc, Direction face, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (callbackInfo.getReturnValueZ()) {
            ViewmodelPose.INSTANCE.cancelAnimation();
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("RETURN"))
    private void iteminspect$cancelViewmodelAfterContinueDestroyBlock(BlockPos posBlock, Direction directionFacing, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (callbackInfo.getReturnValueZ()) {
            ViewmodelPose.INSTANCE.cancelAnimation();
        }
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void iteminspect$cancelViewmodelAfterUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult result, CallbackInfoReturnable<InteractionResult> callbackInfo) {
        cancelIfActionConsumed(callbackInfo.getReturnValue());
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void iteminspect$cancelViewmodelAfterUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callbackInfo) {
        cancelIfActionConsumed(callbackInfo.getReturnValue());
    }

    @Inject(method = "interact", at = @At("RETURN"))
    private void iteminspect$cancelViewmodelAfterEntityInteract(Player player, Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callbackInfo) {
        cancelIfActionConsumed(callbackInfo.getReturnValue());
    }

    @Inject(method = "interactAt", at = @At("RETURN"))
    private void iteminspect$cancelViewmodelAfterEntityInteractAt(Player player, Entity target, EntityHitResult ray, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callbackInfo) {
        cancelIfActionConsumed(callbackInfo.getReturnValue());
    }

    private static void cancelIfActionConsumed(InteractionResult result) {
        if (result != null && result.consumesAction()) {
            ViewmodelPose.INSTANCE.cancelAnimation();
        }
    }
}
