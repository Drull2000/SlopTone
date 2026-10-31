package ru.baritoneru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.baritoneru.Highlights;

/** Draws the glowing outline around entities SlopTone highlights. */
@Mixin(Minecraft.class)
public abstract class MinecraftGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void sloptone$glow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Highlights.shouldGlow(entity)) cir.setReturnValue(true);
    }
}
