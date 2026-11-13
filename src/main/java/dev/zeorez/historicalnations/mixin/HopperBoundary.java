package dev.zeorez.historicalnations.mixin;

import dev.zeorez.historicalnations.property.ParcelProtection;
import net.minecraft.core.BlockPos;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
abstract class HopperBoundary {
    // Forge replaces vanilla's Container overload with this HopperBlockEntity overload.
    @Inject(method="ejectItems", at=@At("HEAD"), cancellable=true, remap=false)
    private static void historicalNationsPush(Level level, BlockPos pos, BlockState state, HopperBlockEntity source,
            CallbackInfoReturnable<Boolean> result) {
        if (historicalNationsCrosses(level,pos,pos.relative(state.getValue(HopperBlock.FACING)))) result.setReturnValue(false);
    }
    @Inject(method={"suckInItems","m_155552_"}, at=@At("HEAD"), cancellable=true, remap=false)
    private static void historicalNationsPull(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> result) {
        var pos = BlockPos.containing(hopper.getLevelX(),hopper.getLevelY(),hopper.getLevelZ());
        if (historicalNationsCrosses(level,pos,pos.above())) result.setReturnValue(false);
    }
    @org.spongepowered.asm.mixin.Unique
    private static boolean historicalNationsCrosses(Level level, BlockPos pos, BlockPos port) {
        if (ParcelProtection.crosses(level,pos,port)) return true;
        var state = level.getBlockState(port);
        return state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && ParcelProtection.crosses(level,port,port.relative(ChestBlock.getConnectedDirection(state)));
    }
}
