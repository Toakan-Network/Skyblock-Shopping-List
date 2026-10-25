package uk.toakan.tad.skyblockshoppinglist.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import uk.toakan.tad.skyblockshoppinglist.KillTracker;

/**
 * Tells {@link KillTracker} when the server reports a mob dead (entity event {@link EntityEvent#DEATH}), for the
 * Mob drops kill counts. Observes the event only: it never cancels or changes it.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathMixin {

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void onEntityEvent(byte id, CallbackInfo ci) {
        // In singleplayer the integrated server's entities run this too; only read the client's.
        if (id != EntityEvent.DEATH || !Minecraft.getInstance().isSameThread()) return;
        KillTracker.onDeath((LivingEntity) (Object) this);
    }
}
