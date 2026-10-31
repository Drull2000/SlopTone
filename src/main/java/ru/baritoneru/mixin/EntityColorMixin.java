package ru.baritoneru.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.baritoneru.Highlights;

/** Gives highlighted entities their own outline colour (per player, red for monsters, green for animals). */
@Mixin(Entity.class)
public abstract class EntityColorMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void sloptone$color(CallbackInfoReturnable<Integer> cir) {
        int c = Highlights.colorFor((Entity) (Object) this);
        if (c != -1) cir.setReturnValue(c);
    }
}
