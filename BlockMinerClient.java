package com.codephantom.blockminer;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Golden shovel: LEFT click block = corner 1 (or chest = drop-off), RIGHT click block = corner 2.
 * /block mine <block>  -> shows summary, /confirm starts, /220 stops.
 */
public class BlockMinerClient implements ClientModInitializer {

    enum State { IDLE, MINING, TO_CHEST, UNLOADING }

    static BlockPos corner1, corner2, chest;
    static Block pendingBlock, targetBlock;
    static State state = State.IDLE;
    static BlockPos target, lastTarget;
    static final Set<BlockPos> skipped = new HashSet<>();
    static int unloadTicks, walkTicks;

    @Override
    public void onInitializeClient() {
        // LEFT click with golden shovel: chest -> drop-off, anything else -> corner 1
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            if (!level.isClientSide() || !holdingShovel(player)) return InteractionResult.PASS;
            BlockState s = level.getBlockState(pos);
            if (isChest(s)) {
                chest = pos.immutable();
                msg("Drop-off chest set: " + pos.toShortString());
            } else {
                corner1 = pos.immutable();
                msg("Corner 1 set: " + pos.toShortString());
            }
            return InteractionResult.FAIL; // cancel breaking the block
        });

        // RIGHT click with golden shovel: corner 2
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || !holdingShovel(player))
                return InteractionResult.PASS;
            corner2 = hit.getBlockPos().immutable();
            msg("Corner 2 set: " + corner2.toShortString());
            return InteractionResult.SUCCESS;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
            dispatcher.register(ClientCommandManager.literal("block")
                .then(ClientCommandManager.literal("mine")
                    .then(ClientCommandManager.argument("block", StringArgumentType.word())
                        .executes(c -> { prepare(StringArgumentType.getString(c, "block")); return 1; }))));
            dispatcher.register(ClientCommandManager.literal("confirm")
                .executes(c -> { confirm(); return 1; }));
            dispatcher.register(ClientCommandManager.literal("220")
                .executes(c -> { stop("Stopped."); return 1; }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(BlockMinerClient::tick);
    }

    // ---------- commands ----------

    static void prepare(String name) {
        if (corner1 == null || corner2 == null) { msg("Mark both corners first (golden shovel: left + right click)."); return; }
        if (chest == null) { msg("Set the drop-off chest first (left click a chest with the golden shovel)."); return; }
        Block found = null;
        for (var id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getPath().equals(name)) { found = BuiltInRegistries.BLOCK.getValue(id); break; }
        }
        if (found == null || found == Blocks.AIR) { msg("Unknown block: " + name); return; }
        long vol = (long) (Math.abs(corner1.getX() - corner2.getX()) + 1)
                 * (Math.abs(corner1.getY() - corner2.getY()) + 1)
                 * (Math.abs(corner1.getZ() - corner2.getZ()) + 1);
        if (vol > 500_000) { msg("Box too big (" + vol + " blocks). Max 500000."); return; }
        pendingBlock = found;
        msg("Ready: mine " + name + " in a box of " + vol + " blocks, drop-off at " + chest.toShortString()
            + ". Type /confirm to start, /220 to stop.");
    }

    static void confirm() {
        if (pendingBlock == null) { msg("Nothing to confirm. Use /block mine <block> first."); return; }
        targetBlock = pendingBlock;
        pendingBlock = null;
        skipped.clear();
        target = null; lastTarget = null; walkTicks = 0;
        state = State.MINING;
        msg("Started.");
    }

    static void stop(String why) {
        state = State.IDLE;
        target = null; lastTarget = null;
        Minecraft mc = Minecraft.getInstance();
        releaseKeys(mc);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        msg(why);
    }

    // ---------- main loop ----------

    static void tick(Minecraft mc) {
        if (state == State.IDLE) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) { state = State.IDLE; return; }
        LocalPlayer p = mc.player;
        switch (state) {
            case MINING -> mine(mc, p);
            case TO_CHEST -> toChest(mc, p);
            case UNLOADING -> unload(mc, p);
            default -> { }
        }
    }

    static void mine(Minecraft mc, LocalPlayer p) {
        if (p.getInventory().getFreeSlot() == -1) {
            releaseKeys(mc);
            mc.gameMode.stopDestroyBlock();
            walkTicks = 0;
            state = State.TO_CHEST;
            msg("Inventory full, heading to the chest.");
            return;
        }
        // picks the best hotbar tool, skipping nearly-broken ones (= auto pickaxe swap)
        if (!selectBestTool(p, targetBlock.defaultBlockState())) {
            stop("No usable tool left in the hotbar. Put fresh pickaxes in your hotbar and run it again.");
            return;
        }

        if (target == null || !isMinable(mc, target)) {
            target = findTarget(mc, p);
            lastTarget = null;
            walkTicks = 0;
        }
        if (target == null) { stop("Done. Nothing safe left to mine in the box."); return; }

        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(c) <= 4.0 * 4.0) {
            releaseKeys(mc);
            lookAt(p, c);
            Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
            if (!target.equals(lastTarget)) {
                mc.gameMode.startDestroyBlock(target, face);
                lastTarget = target;
            } else {
                mc.gameMode.continueDestroyBlock(target, face);
            }
            p.swing(InteractionHand.MAIN_HAND);
        } else {
            if (!walkToward(mc, p, c)) { skipped.add(target); target = null; }
        }
    }

    static void toChest(Minecraft mc, LocalPlayer p) {
        Vec3 c = Vec3.atCenterOf(chest);
        if (p.getEyePosition().distanceToSqr(c) <= 3.5 * 3.5) {
            releaseKeys(mc);
            lookAt(p, c);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(c, Direction.UP, chest, false));
            unloadTicks = 0;
            state = State.UNLOADING;
            return;
        }
        if (!walkToward(mc, p, c)) stop("Can't walk safely to the chest.");
    }

    static void unload(Minecraft mc, LocalPlayer p) {
        unloadTicks++;
        if (p.containerMenu instanceof ChestMenu menu) {
            if (unloadTicks == 5) {
                int chestSlots = menu.getRowCount() * 9;
                for (int i = chestSlots; i < menu.slots.size(); i++) {
                    ItemStack st = menu.slots.get(i).getItem();
                    if (!st.isEmpty() && st.is(targetBlock.asItem())) {
                        mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, p);
                    }
                }
            } else if (unloadTicks >= 15) {
                p.closeContainer();
                if (p.getInventory().getFreeSlot() == -1) { stop("The chest looks full. Stopped."); return; }
                walkTicks = 0;
                state = State.MINING;
                msg("Unloaded, back to mining.");
            }
        } else if (unloadTicks > 40) {
            stop("Couldn't open the chest. Stopped.");
        }
    }

    // ---------- helpers ----------

    static BlockPos findTarget(Minecraft mc, LocalPlayer p) {
        BlockPos feetBelow = p.blockPosition().below();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(corner1, corner2)) {
            if (!isMinable(mc, pos) || pos.equals(feetBelow) || !isExposed(mc, pos)) continue;
            double d = pos.distSqr(p.blockPosition());
            if (d < bestD) { bestD = d; best = pos.immutable(); }
        }
        return best;
    }

    static boolean isMinable(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).is(targetBlock) && !skipped.contains(pos) && isSafe(mc, pos);
    }

    /** Safe = no lava or water in the block itself or any of its 6 neighbours. */
    static boolean isSafe(Minecraft mc, BlockPos pos) {
        if (badFluid(mc, pos)) return false;
        for (Direction d : Direction.values()) if (badFluid(mc, pos.relative(d))) return false;
        return true;
    }

    static boolean badFluid(Minecraft mc, BlockPos pos) {
        var f = mc.level.getFluidState(pos);
        return f.is(FluidTags.LAVA) || f.is(FluidTags.WATER);
    }

    static boolean isExposed(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (mc.level.getBlockState(n).getCollisionShape(mc.level, n).isEmpty()) return true;
        }
        return false;
    }

    /** Returns false if it is unsafe or we are stuck, so the caller can give up on this target. */
    static boolean walkToward(Minecraft mc, LocalPlayer p, Vec3 c) {
        if (++walkTicks > 300) { releaseKeys(mc); return false; }
        // face the target horizontally
        double dx = c.x - p.getX(), dz = c.z - p.getZ();
        p.setYRot((float) (Mth.atan2(dz, dx) * 57.29578) - 90f);
        p.setXRot(0f);

        BlockPos ahead = p.blockPosition().relative(p.getDirection());
        boolean lava = isLavaNear(mc, ahead) || isLavaNear(mc, ahead.below());
        boolean drop = mc.level.getBlockState(ahead.below()).isAir() && mc.level.getBlockState(ahead.below(2)).isAir();
        if (lava || drop) { releaseKeys(mc); return false; }

        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
        return true;
    }

    static boolean isLavaNear(Minecraft mc, BlockPos pos) {
        if (mc.level.getFluidState(pos).is(FluidTags.LAVA)) return true;
        for (Direction d : Direction.values())
            if (mc.level.getFluidState(pos.relative(d)).is(FluidTags.LAVA)) return true;
        return false;
    }

    static boolean selectBestTool(LocalPlayer p, BlockState state) {
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            if (st.isDamageableItem() && st.getDamageValue() >= st.getMaxDamage() - 2) continue;
            float sp = st.getDestroySpeed(state);
            if (sp > bestSpeed) { bestSpeed = sp; best = i; }
        }
        if (best == -1) return false;
        p.getInventory().setSelectedSlot(best);
        return true;
    }

    static void lookAt(LocalPlayer p, Vec3 c) {
        double dx = c.x - p.getX(), dy = c.y - p.getEyeY(), dz = c.z - p.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Mth.atan2(dz, dx) * 57.29578) - 90f);
        p.setXRot((float) -(Mth.atan2(dy, h) * 57.29578));
    }

    static void releaseKeys(Minecraft mc) {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    static boolean holdingShovel(Player p) { return p.getMainHandItem().is(Items.GOLDEN_SHOVEL); }

    static boolean isChest(BlockState s) {
        return s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST) || s.is(Blocks.BARREL);
    }

    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[BlockMiner] " + s), false);
    }
}
