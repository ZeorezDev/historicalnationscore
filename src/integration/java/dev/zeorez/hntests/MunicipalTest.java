package dev.zeorez.hntests;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.zeorez.historicalnations.api.Parcels;
import dev.zeorez.historicalnations.core.WorldRegistry;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import static dev.zeorez.hntests.PrototypeServerTest.check;

final class MunicipalTest {
    private final PrototypeServerTest.Suite s;
    MunicipalTest(PrototypeServerTest.Suite s) { this.s = s; }

    void setup() throws CommandSyntaxException {
        var a = s.a;
        a.setPos(-17,80,33);
        s.checkCount(a,2);
        var dim = a.serverLevel().dimension().location();
        check(Parcels.plot(s.server,dim,a.blockPosition()).isPresent(), "v0.2 plot migration");
        s.command(a,"town invite HnCem","invited");
        s.command(s.c,"town accept Ankara","town_joined");
        s.command(a,"town deputy set HnCem","updated");
        s.command(s.c,"town description set Başkent belediyesi","updated");
        s.command(s.c,"town kick HnAlice","!invalid_successor");
        s.command(s.c,"town disband","!mayor_only");
        s.command(s.c,"town setmayor HnCem","!mayor_only");
        s.command(s.c,"town invite HnDeniz","invited");
        s.command(s.d,"town accept Ankara","town_joined");
        s.command(s.c,"town kick HnDeniz","updated");
        s.command(a,"plot group create Merkez","updated");
        s.command(a,"plot group join Merkez","updated");
        s.command(a,"plot perm interact citizen true","updated");
        s.command(a,"plot set name Belediye","updated");
        s.command(a,"plot forsale 100","updated");
        s.c.setPos(-17,80,33);
        s.command(s.c,"plot claim","!no_economy");
        s.command(a,"plot forsale 0","updated");
        s.command(s.c,"plot claim","updated");
        room(a,new BlockPos(-28,80,34),"Daire");
        s.command(a,"quarter set owner HnCem","updated");
        s.command(a,"quarter set color #00bbaa","updated");
        s.command(a,"quarter set description Birinci kat","updated");
        s.command(a,"quarter create Overlap","!quarter_overlap");
        a.setPos(-20,80,34);
        s.command(a,"quarter pos1","corner");
        a.setPos(-19,82,37);
        s.command(a,"quarter pos2","corner");
        a.setPos(-28,80,34);
        s.command(a,"quarter addselection","updated");
        a.setPos(-31,80,34);
        s.command(a,"quarter pos1","corner");
        a.setPos(-17,83,34);
        s.command(a,"quarter pos2","corner");
        a.setPos(-28,80,34);
        s.command(a,"quarter replace 1","!quarter_overlap");
        s.command(a,"quarter show","boundary");
    }
    void verify() throws CommandSyntaxException {
        var a = s.a;
        a.setPos(-28,80,34);
        var pos = a.blockPosition();
        var dim = a.level().dimension().location();
        var view = Parcels.quarter(s.server,dim,pos).orElseThrow();
        var plot = Parcels.plot(s.server,dim,pos).orElseThrow();
        check(view.owner().equals(s.c.getUUID()) && view.boxes().size() == 2 && view.color() == 0x00bbaa
                && view.description().equals("Birinci kat"),"Quarter reload");
        check(plot.owner().equals(s.c.getUUID()) && plot.permissions().get("INTERACT.CITIZEN")
                && plot.group().equals("Merkez"), "Shared plot rules reload");
        var data = WorldRegistry.get(s.server);
        check(data.deputy(view.town()).equals(s.c.getUUID()) && data.description(view.town()).equals("Başkent belediyesi"),"Municipality reload");
        s.command(a,"town setmayor HnCem","!land_transfer");
        s.command(s.c,"town leave","town_left");
        check(Parcels.quarter(s.server,dim,pos).orElseThrow().owner() == null && data.deputy(view.town()) == null,"Departure revoked offices/ownership");
        var claims = xaero.pac.common.server.api.OpenPACServerAPI.get(s.server).getServerClaimsManager();
        claims.tryToUnclaim(dim,a.getUUID(),dim,-2,2,-2,2,false);
        claims.claim(dim,s.b.getUUID(),-1,-2,2,false);
        check(Parcels.plot(s.server,dim,pos).isEmpty() && Parcels.quarter(s.server,dim,pos).isEmpty()
                && claims.get(dim,-2,2).getPlayerId().equals(s.b.getUUID()), "External personal claim isolated");
    }

    void boundaries() throws CommandSyntaxException {
        var a = s.a;
        var world = s.server.overworld();
        a.setPos(32,80,32);
        s.command(a,"town create Bursa","town_created");
        s.command(a,"town claim","land_claimed");
        var start = new BlockPos(34,80,34);
        room(a,start,"Test");
        a.setPos(40,85,37);
        s.command(a,"quarter pos1","corner");
        a.setPos(42,87,39);
        s.command(a,"quarter pos2","corner");
        var volume = dev.zeorez.historicalnations.TerritoryConfig.VOLUME;
        var count = dev.zeorez.historicalnations.TerritoryConfig.QUARTERS;
        int oldVolume = volume.get(), oldCount = count.get();
        try {
            volume.set(8);
            s.command(a,"quarter create Limit","!invalid_selection");
            volume.set(oldVolume);
            count.set(1);
            s.command(a,"quarter create Limit","!quarter_limit");
        } finally { volume.set(oldVolume); count.set(oldCount); }
        s.command(a,"quarter create Annex","updated");
        a.setPos(start.getX(),start.getY(),start.getZ());
        s.command(a,"quarter merge Annex","updated");
        check(Parcels.quarter(s.server,world.dimension().location(),start).orElseThrow().boxes().size() == 2, "Merged geometry");
        // Both edges are inside the same physical OPAC claim.
        hopper(world,start.offset(1,1,1),true);
        hopper(world,start.offset(3,1,1),false);
        flow(world,start.offset(1,2,2),true);
        flow(world,start.offset(1,0,2),false);
        var piston = new net.minecraftforge.event.level.PistonEvent.Pre(world,start.offset(3,0,0),Direction.EAST,
                net.minecraftforge.event.level.PistonEvent.PistonMoveType.EXTEND);
        check(net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(piston), "Piston crosses apartment boundary");
        var inside = start.offset(1,1,1);
        var outside = start.offset(6,1,1);
        var explosion = new net.minecraft.world.level.Explosion(world,null,inside.getX(),inside.getY(),inside.getZ(),
                1.0f,java.util.List.of(inside,outside));
        var detonate = new net.minecraftforge.event.level.ExplosionEvent.Detonate(world,explosion,new java.util.ArrayList<>());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(detonate);
        check(!detonate.getAffectedBlocks().contains(inside), "Explosion kept a protected block");
        a.setPos(47,80,33);
        s.command(a,"quarter pos1","corner");
        a.setPos(48,80,33);
        s.command(a,"town claim","land_claimed");
        s.command(a,"quarter pos2","corner");
        s.command(a,"quarter create Cross","!invalid_selection");
        s.command(a,"plot group create Ortak","updated");
        s.command(a,"plot group join Ortak","updated");
        s.command(a,"plot perm destroy citizen false","updated");
        a.setPos(32,80,32);
        s.command(a,"plot group join Ortak","updated");
        check(Boolean.FALSE.equals(Parcels.plot(s.server,world.dimension().location(),a.blockPosition())
                .orElseThrow().permissions().get("DESTROY.CITIZEN")), "Shared rule across two plots");
        s.command(a,"plot set type jail","updated");
        s.command(a,"plot forsale 0","!municipal_facility");
        s.command(a,"town invite HnCem","invited");
        s.command(s.c,"town accept Bursa","town_joined");
        s.command(a,"town deputy set HnCem","updated");
        s.command(a,"town description set Bursa belediyesi","updated");
        s.command(a,"town unclaim","land_released");
        a.setPos(48,80,33);
        s.command(a,"town unclaim","land_released");
        s.command(a,"town setmayor HnCem","new_mayor");
        var town = s.api.getPlayerTown(a.getUUID()).orElseThrow();
        var data = WorldRegistry.get(s.server);
        check(town.mayor().equals(s.c.getUUID()) && data.deputy(town.id()) == null
                && data.description(town.id()).equals("Bursa belediyesi"), "Landless succession");
        s.command(s.c,"town disband","town_disbanded");
    }

    private static void hopper(ServerLevel world, BlockPos pos, boolean allowed) {
        var hopper = Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.FACING,Direction.EAST);
        world.setBlockAndUpdate(pos,hopper);
        world.setBlockAndUpdate(pos.east(),Blocks.BARREL.defaultBlockState());
        var be = (net.minecraft.world.level.block.entity.HopperBlockEntity)world.getBlockEntity(pos);
        var dest = (net.minecraft.world.Container)world.getBlockEntity(pos.east());
        be.setItem(0,new ItemStack(Items.DIAMOND));
        net.minecraft.world.level.block.entity.HopperBlockEntity.pushItemsTick(world,pos,hopper,be);
        check(dest.isEmpty() != allowed, "Vanilla hopper transfer, expected " + allowed);
    }
    private static void flow(ServerLevel world, BlockPos pos, boolean allowed) {
        world.setBlockAndUpdate(pos.below(),Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(pos,Blocks.WATER.defaultBlockState());
        world.getFluidState(pos).tick(world,pos);
        check(world.getFluidState(pos.below()).is(net.minecraft.tags.FluidTags.WATER) == allowed,
                "Vanilla downward fluid transfer, expected " + allowed);
    }

    void network(ServerPlayer a, ServerPlayer b) throws CommandSyntaxException {
        var chunk = a.chunkPosition();
        var lower = new BlockPos(chunk.getMinBlockX()+4,90,chunk.getMinBlockZ()+4);
        var upper = lower.above(6);
        room(a,lower,"Alt");
        s.command(a,"quarter set owner HnNetA","updated");
        room(a,upper,"Üst");
        s.command(a,"quarter set owner HnNetB","updated");
        exercise(a,lower,true);
        exercise(b,lower,false);
        exercise(b,upper,true);
        exercise(a,upper,false);
        // A later normal OPAC unclaim must still target the original chunk.
        a.teleportTo(lower.getX()+0.5,lower.getY(),lower.getZ()+0.5);
    }

    private void room(ServerPlayer actor, BlockPos floor, String name) throws CommandSyntaxException {
        actor.setPos(floor.getX(),floor.getY(),floor.getZ());
        s.command(actor,"quarter pos1","corner");
        actor.setPos(floor.getX()+3,floor.getY()+3,floor.getZ()+3);
        s.command(actor,"quarter pos2","corner");
        s.command(actor,"quarter create "+name,"updated");
    }

    private void exercise(ServerPlayer player, BlockPos floor, boolean allowed) {
        ServerLevel world = player.serverLevel();
        var base = floor.offset(1,0,1);
        var block = base.above();
        player.teleportTo(base.getX()+2.5,base.getY()+1,base.getZ()+0.5);
        world.setBlockAndUpdate(base,Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(block,Blocks.AIR.defaultBlockState());
        var stack = new ItemStack(Blocks.COBBLESTONE,8);
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(base).add(0,0.5,0),Direction.UP,base,false);
        player.gameMode.useItemOn(player,world,stack,InteractionHand.MAIN_HAND,hit);
        check(world.getBlockState(block).is(Blocks.COBBLESTONE) == allowed, "Actual placement by " + player.getScoreboardName() + " at " + block);
        world.setBlockAndUpdate(block,Blocks.COBBLESTONE.defaultBlockState());
        player.gameMode.destroyBlock(block);
        check(world.getBlockState(block).isAir() == allowed, "Actual break by " + player.getScoreboardName() + " at " + block);
    }
}
