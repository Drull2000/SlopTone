package ru.baritoneru.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets X-Ray colour the outline and view distance of its client-only block displays. */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setGlowColorOverride")
    void sloptone$setGlowColorOverride(int color);

    @Invoker("setViewRange")
    void sloptone$setViewRange(float range);
}
