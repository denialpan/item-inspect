package com.ddd.iteminspect.mixin;

import com.ddd.iteminspect.ViewmodelPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float oMainHandHeight;

    @Shadow
    private ItemStack offHandItem;

    @Shadow
    private float offHandHeight;

    @Shadow
    private float oOffHandHeight;

    @Inject(method = "tick", at = @At("TAIL"))
    private void iteminspect$suppressMainHandEquipAnimation(CallbackInfo callbackInfo) {
        if (this.minecraft.player == null) {
            return;
        }

        if (ViewmodelPose.INSTANCE.shouldSuppressVanillaMainHandEquip()) {
            this.mainHandItem = this.minecraft.player.getMainHandItem();
            this.mainHandHeight = 1.0F;
            this.oMainHandHeight = 1.0F;
        }
        if (ViewmodelPose.INSTANCE.shouldSuppressVanillaOffhandEquip()) {
            this.offHandItem = this.minecraft.player.getOffhandItem();
            this.offHandHeight = 1.0F;
            this.oOffHandHeight = 1.0F;
        }
    }
}
