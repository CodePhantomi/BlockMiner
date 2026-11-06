package com.codephantom.blockminer.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Golden shovel: LEFT click block = corner 1 (or chest = drop-off), RIGHT click block = corner 2.
 * /block mine <block>  -> shows summary, /confirm starts, /220 stops.
 * /block mine <block> [amount]  and  /block mine <block> closest [amount]
 * (amount = how many of that block to mine; no chest set = stops when the inventory is full)
 * /block mine <block> closest -> no box needed: mines the nearest ones within 32 blocks of where you stand.
 * Tunnel mode is automatic: if the block is buried, it digs through whatever is in the way (inside the box only).
 * At the chest it unloads everything except tools/weapons/armor (damageable items).
 */
public class BlockMinerClient implements ClientModInitializer {

    enum State { IDLE, MINING, TO_CHEST, UNLOADING }

    static BlockPos corner1, corner2, chest;
    static BlockPos boxA, boxB;            // the area actually being mined
    static boolean pendingClosest, closestMode;   // closestMode = running without a box
    static int pendingAmount, goal, mined, finishTicks;   // goal 0 = no limit
    static boolean goalReached;
    static Block pendingBlock, targetBlock;
    static State state = State.IDLE;
    static BlockPos target, lastTarget;
    static final Set<BlockPos> skipped = new HashSet<>();
    static int unloadTicks, walkTicks, collectTicks, collectId = -1;
    static final Set<Integer> ignoredItems = new HashSet<>();

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
            dispatcher.register(ClientCommands.literal("block")
                .then(ClientCommands.literal("mine")
                    .then(ClientCommands.argument("block", StringArgumentType.word())
                        .executes(c -> { prepare(StringArgumentType.getString(c, "block"), false, 0); return 1; })
                        .then(ClientCommands.argument("amount", IntegerArgumentType.integer(1))
                            .executes(c -> { prepare(StringArgumentType.getString(c, "block"), false, IntegerArgumentType.getInteger(c, "amount")); return 1; }))
                        .then(ClientCommands.literal("closest")
                            .executes(c -> { prepare(StringArgumentType.getString(c, "block"), true, 0); return 1; })
                            .then(ClientCommands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(c -> { prepare(StringArgumentType.getString(c, "block"), true, IntegerArgumentType.getInteger(c, "amount")); return 1; }))))));
            dispatcher.register(ClientCommands.literal("confirm")
                .executes(c -> { confirm(); return 1; }));
            dispatcher.register(ClientCommands.literal("220")
                .executes(c -> { stop("Stopped."); return 1; }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(BlockMinerClient::tick);
    }

    // ---------- commands ----------

    static void prepare(String name, boolean closest, int amount) {
        if (!closest && (corner1 == null || corner2 == null)) {
            msg("Mark both corners first (golden shovel: left + right click), or add the word closest.");
            return;
        }
        Block found = null;
        for (var id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getPath().equals(name)) { found = BuiltInRegistries.BLOCK.getValue(id); break; }
        }
        if (found == null || found == Blocks.AIR) { msg("Unknown block: " + name); return; }

        String where;
        if (closest) {
            where = "the closest ones within 32 blocks of you (12 up/down), digging through anything in the way";
        } else {
            long vol = (long) (Math.abs(corner1.getX() - corner2.getX()) + 1)
                     * (Math.abs(corner1.getY() - corner2.getY()) + 1)
                     * (Math.abs(corner1.getZ() - corner2.getZ()) + 1);
            if (vol > 500_000) { msg("Box too big (" + vol + " blocks). Max 500000."); return; }
            where = "in your box of " + vol + " blocks";
        }
        pendingBlock = found;
        pendingClosest = closest;
        pendingAmount = amount;
        msg("Ready: mine " + (amount > 0 ? amount : "all") + " " + name + " " + where + ". "
            + (chest == null ? "No drop-off chest set, so it stops when your inventory is full. "
                             : "Drop-off at " + chest.toShortString() + ". ")
            + "Type /confirm to start, /220 to stop.");
    }

    static void confirm() {
        if (pendingBlock == null) { msg("Nothing to confirm. Use /block mine <block> first."); return; }
        Minecraft mc = Minecraft.getInstance();
        if (pendingClosest) {
            if (mc.player == null) return;
            BlockPos c = mc.player.blockPosition();
            boxA = c.offset(-32, -12, -32);
            boxB = c.offset(32, 12, 32);
        } else {
            boxA = corner1;
            boxB = corner2;
        }
        targetBlock = pendingBlock;
        pendingBlock = null;
        closestMode = pendingClosest;
        goal = pendingAmount;
        mined = 0; goalReached = false; finishTicks = 0;
        skipped.clear();
        ignoredItems.clear();
        collectTicks = 0; collectId = -1;
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
        // count wanted blocks that have just been broken
        if (target != null && target.equals(lastTarget) && !mc.level.getBlockState(target).is(targetBlock)) {
            mined++;
            target = null;
            lastTarget = null;
            if (goal > 0) msg("Mined " + mined + "/" + goal);
            if (goal > 0 && mined >= goal) {
                goalReached = true;
                finishTicks = 0;
                mc.gameMode.stopDestroyBlock();
            }
        }
        if (goalReached) {                       // wait a moment, collect the last drops, then stop
            releaseKeys(mc);
            if (++finishTicks < 15) return;
            if (!collectDrops(mc, p)) stop("Done: mined " + mined + ".");
            return;
        }

        if (p.getInventory().getFreeSlot() == -1) {
            if (chest == null) { stop("Inventory full and no chest is set. Stopped (mined " + mined + ")."); return; }
            releaseKeys(mc);
            mc.gameMode.stopDestroyBlock();
            walkTicks = 0;
            state = State.TO_CHEST;
            msg("Inventory full, heading to the chest.");
            return;
        }

        if (collectDrops(mc, p)) return;

        if (target == null || !isMinable(mc, target)) {
            target = findTarget(mc, p);
            lastTarget = null;
            walkTicks = 0;
        }
        if (target == null) { stop("Done. Nothing safe left to mine (mined " + mined + ")."); return; }

        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(target);
        BlockPos work = null;

        if (eye.distanceToSqr(c) > 4.0 * 4.0) {
            // Far away: carve a 2-high walking space straight ahead (tunnel mode), otherwise just walk.
            faceHoriz(p, c);
            BlockPos ahead = p.blockPosition().relative(p.getDirection());
            for (BlockPos b : new BlockPos[] { ahead, ahead.above() }) {
                if (isSolid(mc, b)) {
                    if (!diggable(mc, b)) { releaseKeys(mc); skipped.add(target); target = null; return; }
                    work = b;
                    break;
                }
            }
            if (work == null) {
                if (!walkToward(mc, p, c)) { skipped.add(target); target = null; }
                return;
            }
        } else {
            // Close: mine the first solid block on the line to the target (the target itself if the way is clear).
            work = firstBlocker(mc, eye, c);
            if (work == null) { releaseKeys(mc); skipped.add(target); target = null; return; }
        }

        if (!selectBestTool(p, mc.level.getBlockState(work))) {
            stop("No usable tool left in the hotbar. Put fresh pickaxes in your hotbar and run it again.");
            return;
        }
        walkTicks = 0;
        releaseKeys(mc);
        Vec3 wc = Vec3.atCenterOf(work);
        lookAt(p, wc);
        Direction face = Direction.getApproximateNearest(eye.x - wc.x, eye.y - wc.y, eye.z - wc.z);
        if (!work.equals(lastTarget)) {
            mc.gameMode.startDestroyBlock(work, face);
            lastTarget = work;
        } else {
            mc.gameMode.continueDestroyBlock(work, face);
        }
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** Walks to the nearest dropped item within 8 blocks. Returns true while it is busy collecting. */
    static boolean collectDrops(Minecraft mc, LocalPlayer p) {
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(8.0))) {
            if (ignoredItems.contains(e.getId()) || isLavaNear(mc, e.blockPosition())) continue;
            double d = e.distanceToSqr(p);
            if (d < bd) { bd = d; best = e; }
        }
        if (best == null) { collectTicks = 0; collectId = -1; return false; }

        if (best.getId() != collectId) { collectId = best.getId(); collectTicks = 0; }
        if (++collectTicks > 80) {            // could not pick it up, give up on this one
            ignoredItems.add(best.getId());
            releaseKeys(mc);
            return false;
        }

        mc.gameMode.stopDestroyBlock();
        lastTarget = null;                    // so mining restarts cleanly afterwards
        if (bd < 1.0) { releaseKeys(mc); return true; }   // on top of it, wait for the pickup

        walkTicks = 0;
        if (!walkToward(mc, p, best.position())) ignoredItems.add(best.getId());
        return true;
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
        AbstractContainerMenu menu = p.containerMenu;
        if (menu != null && menu.containerId != 0) {          // any opened container: chest, barrel, shulker, ender chest...
            if (unloadTicks == 5) {
                int chestSlots = menu.slots.size() - 36;        // the last 36 slots are always the player's inventory
                for (int i = chestSlots; i < menu.slots.size(); i++) {
                    ItemStack st = menu.slots.get(i).getItem();
                    if (!st.isEmpty() && !st.isDamageableItem()) {
                        mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
                    }
                }
            } else if (unloadTicks >= 15) {
                p.closeContainer();
                if (p.getInventory().getFreeSlot() == -1) { stop("The drop-off looks full. Stopped."); return; }
                walkTicks = 0;
                state = State.MINING;
                msg("Unloaded, back to mining.");
            }
        } else if (unloadTicks > 40) {
            stop("Couldn't open the chest. Stopped.");
        }
    }

    // ---------- helpers ----------

    /** Prefers blocks that are already exposed; if none are left, picks the nearest buried one (tunnel mode). */
    static BlockPos findTarget(Minecraft mc, LocalPlayer p) {
        if (closestMode) {                      // no box: search around wherever the player is right now
            BlockPos here = p.blockPosition();
            boxA = here.offset(-32, -12, -32);
            boxB = here.offset(32, 12, 32);
        }
        BlockPos feetBelow = p.blockPosition().below();
        BlockPos bestExposed = null, bestBuried = null;
        double dExposed = Double.MAX_VALUE, dBuried = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(boxA, boxB)) {
            if (!isMinable(mc, pos) || pos.equals(feetBelow)) continue;
            double d = pos.distSqr(p.blockPosition());
            if (isExposed(mc, pos)) {
                if (d < dExposed) { dExposed = d; bestExposed = pos.immutable(); }
            } else if (d < dBuried) {
                dBuried = d; bestBuried = pos.immutable();
            }
        }
        return bestExposed != null ? bestExposed : bestBuried;
    }

    static boolean isSolid(Minecraft mc, BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    static boolean inBox(BlockPos b) {
        return b.getX() >= Math.min(boxA.getX(), boxB.getX()) && b.getX() <= Math.max(boxA.getX(), boxB.getX())
            && b.getY() >= Math.min(boxA.getY(), boxB.getY()) && b.getY() <= Math.max(boxA.getY(), boxB.getY())
            && b.getZ() >= Math.min(boxA.getZ(), boxB.getZ()) && b.getZ() <= Math.max(boxA.getZ(), boxB.getZ());
    }

    /** In box mode tunnel mode may only dig inside the box (in closest mode there is no box), never next to lava/water, never the chest, never bedrock/barriers. */
    static boolean diggable(Minecraft mc, BlockPos b) {
        BlockState st = mc.level.getBlockState(b);
        if ((!closestMode && !inBox(b)) || b.equals(chest) || isChest(st)) return false;
        if (st.is(Blocks.BEDROCK) || st.is(Blocks.BARRIER)) return false;
        return isSafe(mc, b);
    }

    /** First solid block on the line from the eye to the target; the target itself if nothing is in the way. */
    static BlockPos firstBlocker(Minecraft mc, Vec3 eye, Vec3 c) {
        Vec3 d = c.subtract(eye);
        double len = d.length();
        for (int i = 1; i * 0.25 <= len; i++) {
            Vec3 pt = eye.add(d.scale(i * 0.25 / len));
            BlockPos b = BlockPos.containing(pt);
            if (b.equals(target)) return target;
            if (isSolid(mc, b)) return diggable(mc, b) ? b : null;
        }
        return target;
    }

    static void faceHoriz(LocalPlayer p, Vec3 c) {
        double dx = c.x - p.getX(), dz = c.z - p.getZ();
        p.setYRot((float) (Mth.atan2(dz, dx) * 57.29578) - 90f);
        p.setXRot(0f);
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

    /** Blocks that can be the drop-off: chests, barrels, ender chests and any shulker box. */
    static boolean isChest(BlockState s) {
        return s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST) || s.is(Blocks.BARREL)
            || s.is(Blocks.ENDER_CHEST) || s.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Chat output via reflection so renamed chat methods in new Minecraft versions can't break the build. */
    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Component c = Component.literal("[BlockMiner] " + s);
        try {
            mc.player.getClass().getMethod("sendSystemMessage", Component.class).invoke(mc.player, c);
            return;
        } catch (Throwable ignored) { }
        try {
            mc.player.getClass().getMethod("displayClientMessage", Component.class, boolean.class).invoke(mc.player, c, false);
            return;
        } catch (Throwable ignored) { }
        try {
            Object chat = mc.gui.getClass().getMethod("getChat").invoke(mc.gui);
            chat.getClass().getMethod("addMessage", Component.class).invoke(chat, c);
            return;
        } catch (Throwable ignored) { }
        System.out.println("[BlockMiner] " + s);
    }
}
