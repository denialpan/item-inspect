package com.ddd.iteminspect.mixin;

import com.ddd.iteminspect.ViewmodelPose;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void move(float x, float y, float z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void iteminspect$applyViewmodelCameraTranslation(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo callbackInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        ViewmodelPose pose = ViewmodelPose.INSTANCE;
        if (minecraft.player == null || minecraft.level == null || !minecraft.options.bobView().get() || !pose.isLoaded() || !pose.isCameraActive()) {
            return;
        }

        ViewmodelPose.Transform camera = pose.viewmodelCamera(partialTick);
        if (camera.tx() == 0.0F && camera.ty() == 0.0F && camera.tz() == 0.0F) {
            return;
        }

        this.move(camera.tx(), camera.ty(), camera.tz());
    }
}
