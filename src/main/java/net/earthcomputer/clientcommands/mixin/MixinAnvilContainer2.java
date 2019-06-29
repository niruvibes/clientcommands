package net.earthcomputer.clientcommands.mixin;

import net.earthcomputer.clientcommands.features.EnchantmentCracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.container.AnvilContainer$2")
public class MixinAnvilContainer2 {

    @Inject(method = "onTakeItem", at = @At("HEAD"))
    public void onAnvilUse(PlayerEntity entity, ItemStack stack, CallbackInfoReturnable<ItemStack> ci) {
        EnchantmentCracker.onAnvilUse();
    }

}
