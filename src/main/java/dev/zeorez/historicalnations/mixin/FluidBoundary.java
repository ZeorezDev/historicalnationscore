package dev.zeorez.historicalnations.mixin;

import dev.zeorez.historicalnations.property.ParcelProtection;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
abstract class FluidBoundary {
    // Official development name and Forge 1.20.1 production SRG name.
    @Inject(method={"canSpreadTo","m_75977_"}, at=@At("HEAD"), cancellable=true, remap=false)
    private void historicalNationsFlow(BlockGetter level, BlockPos from, BlockState source, Direction direction,
            BlockPos to, BlockState target, FluidState state, Fluid fluid, CallbackInfoReturnable<Boolean> result) {
        if (ParcelProtection.crosses(level,from,to)) result.setReturnValue(false);
    }
}
