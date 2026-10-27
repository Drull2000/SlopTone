package ru.baritoneru;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Drives the player through the vanilla key bindings, so the game itself does movement and block breaking.
 * Everything here runs on the client thread before the vanilla tick (ClientTickEvent.Pre).
 */
public final class Controls {
    public static final Set<Block> THROWAWAY = Set.of(
            Blocks.COBBLESTONE, Blocks.DIRT, Blocks.NETHERRACK, Blocks.STONE, Blocks.COBBLED_DEEPSLATE, Blocks.ANDESITE,
            Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF, Blocks.BLACKSTONE, Blocks.END_STONE, Blocks.DEEPSLATE,
            Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.BASALT, Blocks.CALCITE, Blocks.SANDSTONE, Blocks.MUD,
            Blocks.MOSSY_COBBLESTONE, Blocks.SMOOTH_BASALT, Blocks.DRIPSTONE_BLOCK);

    private final Minecraft mc = Minecraft.getInstance();
    public boolean forward, back, left, right, jump, sneak, sprint, attack, use;
    private boolean controlling, wasControlling;

    public LocalPlayer player() { return mc.player; }

    /** Called at the start of every bot tick: nothing pressed unless the logic presses it again. */
    public void clear() {
        forward = back = left = right = jump = sneak = sprint = attack = use = false;
        controlling = false;
        breakRequested = false;
    }

    /** Marks that the bot owns the keyboard this tick. */
    public void take() { controlling = true; }

    public boolean isControlling() { return controlling; }

    public void apply() {
        var o = mc.options;
        if (controlling) {
            o.keyUp.setDown(forward);
            o.keyDown.setDown(back);
            o.keyLeft.setDown(left);
            o.keyRight.setDown(right);
            o.keyJump.setDown(jump);
            o.keyShift.setDown(sneak);
            o.keySprint.setDown(sprint);
            // blocks are broken with packets (see breakBlock), never with the attack key: vanilla blocks
            // holding attack for a long time after a screen (the chat) was closed
            o.keyAttack.setDown(false);
            o.keyUse.setDown(use);
            if (mc.player != null && !sprint && mc.player.isSprinting()) mc.player.setSprinting(false);
            wasControlling = true;
        } else if (wasControlling) {
            releaseAll();
        }
        if (breakingPos != null && !breakRequested) abortBreaking();
    }

    public void releaseAll() {
        var o = mc.options;
        o.keyUp.setDown(false);
        o.keyDown.setDown(false);
        o.keyLeft.setDown(false);
        o.keyRight.setDown(false);
        o.keyJump.setDown(false);
        o.keyShift.setDown(false);
        o.keySprint.setDown(false);
        o.keyAttack.setDown(false);
        o.keyUse.setDown(false);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        abortBreaking();
        wasControlling = false;
    }

    // ---------------------------------------------------------------- position helpers

    public BlockPos feet() {
        LocalPlayer p = mc.player;
        return BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
    }

    public boolean grounded() {
        LocalPlayer p = mc.player;
        return p.onGround() || p.isInWater() || p.onClimbable();
    }

    public double horizDist(double x, double z) {
        LocalPlayer p = mc.player;
        double dx = x - p.getX(), dz = z - p.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean centered(BlockPos pos, double tol) {
        return horizDist(pos.getX() + 0.5, pos.getZ() + 0.5) < tol;
    }

    // ---------------------------------------------------------------- looking

    public void look(float yaw, float pitch, boolean smooth) {
        LocalPlayer p = mc.player;
        if (smooth && Settings.smoothLook) {
            float dy = Mth.wrapDegrees(yaw - p.getYRot());
            float dp = pitch - p.getXRot();
            yaw = p.getYRot() + Mth.clamp(dy, -35f, 35f);
            pitch = p.getXRot() + Mth.clamp(dp, -20f, 20f);
        }
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90f, 90f));
        p.setYHeadRot(yaw);
    }

    public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        return (float) (Mth.atan2(toZ - fromZ, toX - fromX) * (180 / Math.PI)) - 90f;
    }

    public void lookAt(Vec3 target, boolean smooth) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = yawTo(eye.x, eye.z, target.x, target.z);
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180 / Math.PI));
        look(yaw, pitch, smooth);
    }

    /** Walk towards a point; returns horizontal distance remaining. */
    public double walkTo(double x, double z, boolean allowSprint) {
        LocalPlayer p = mc.player;
        double d = horizDist(x, z);
        take();
        if (d > 0.08) {
            float yaw = yawTo(p.getX(), p.getZ(), x, z);
            float pitch = Mth.clamp(p.getXRot(), -20f, 30f);
            look(yaw, pitch, true);
            float diff = Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
            forward = diff < 60;
            sprint = allowSprint && Settings.allowSprint && d > 1.5 && diff < 20 && p.getFoodData().getFoodLevel() > 6;
        }
        return d;
    }

    // ---------------------------------------------------------------- blocks

    public double reach() { return mc.player.blockInteractionRange() - 0.2; }

    /** A point on the block that is visible from the eyes, or null if none within reach. */
    public Vec3 aimPoint(BlockPos pos) {
        LocalPlayer p = mc.player;
        Vec3 eye = p.getEyePosition();
        double r = reach();
        List<Vec3> pts = new ArrayList<>();
        pts.add(Vec3.atCenterOf(pos));
        for (Direction d : Direction.values())
            pts.add(Vec3.atCenterOf(pos).add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45));
        Vec3 fallback = null;
        for (Vec3 v : pts) {
            if (eye.distanceTo(v) > r) continue;
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, v, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return v;
            if (fallback == null) fallback = v;
        }
        return fallback;
    }

    public boolean canReach(BlockPos pos) {
        return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= reach() + 0.3;
    }

    public boolean visible(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 v = aimPoint(pos);
        if (v == null) return false;
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, v, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private BlockPos breakingPos;
    private Direction breakingFace;
    private float breakProgress;
    private boolean breakRequested;
    private BlockPos waitPos;
    private int waitTicks;
    private int lastSlot = -1;

    /**
     * Mines the block by talking to the server directly (start → wait for the progress → stop), like the vanilla
     * client does, but without depending on the crosshair, the attack key, window focus or the chat having been open.
     */
    public void breakBlock(BlockPos pos) {
        take();
        breakRequested = true;
        LocalPlayer p = mc.player;
        BlockState s = mc.level.getBlockState(pos);
        if (s.isAir()) return;
        if (Settings.autoTool) selectBestTool(s);
        Vec3 aim = aimPoint(pos);
        if (aim == null) aim = Vec3.atCenterOf(pos);
        lookAt(aim, false);

        // the server needs a tick to learn about a hotbar change, otherwise it mines with the old tool
        int slot = p.getInventory().getSelectedSlot();
        boolean slotChanged = slot != lastSlot;
        lastSlot = slot;

        if (pos.equals(waitPos) && waitTicks > 0) {
            waitTicks--; // finished, waiting for the server to remove the block
            return;
        }
        if (!pos.equals(breakingPos)) {
            if (breakingPos != null) abortBreaking();
            if (slotChanged) return;
            breakingFace = faceTowardsPlayer(pos);
            breakingPos = pos.immutable();
            breakProgress = 0;
            send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, breakingPos, breakingFace);
        }
        breakProgress += s.getDestroyProgress(p, mc.level, pos);
        p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
        if (breakProgress >= 1.0f) {
            send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, breakingPos, breakingFace);
            waitPos = breakingPos;
            waitTicks = 6;
            breakingPos = null;
        }
    }

    private void abortBreaking() {
        if (breakingPos != null && mc.player != null)
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, breakingPos, breakingFace);
        breakingPos = null;
    }

    private void send(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
        mc.player.connection.send(new ServerboundPlayerActionPacket(action, pos, face));
    }

    private Direction faceTowardsPlayer(BlockPos pos) {
        Vec3 d = mc.player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        if (ay >= ax && ay >= az) return d.y > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return d.x > 0 ? Direction.EAST : Direction.WEST;
        return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Right-click a face of {@code against} with an item matching {@code item}. Returns true if something was placed. */
    public boolean placeAgainst(BlockPos against, Direction face, Predicate<ItemStack> item, boolean needSneak) {
        take();
        int slot = ensureHotbar(item);
        if (slot < 0) return false;
        mc.player.getInventory().setSelectedSlot(slot);
        Vec3 hitVec = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        lookAt(hitVec, false);
        if (needSneak) {
            sneak = true;
            if (!mc.player.isShiftKeyDown()) return false;
        }
        InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, face, against, false));
        return r.consumesAction();
    }

    // ---------------------------------------------------------------- inventory

    public static boolean isThrowaway(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof BlockItem bi && THROWAWAY.contains(bi.getBlock());
    }

    public boolean hasItem(Predicate<ItemStack> pred) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (pred.test(inv.getItem(i))) return true;
        return false;
    }

    public List<ItemStack> hotbarCopy() {
        List<ItemStack> l = new ArrayList<>();
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) l.add(inv.getItem(i).copy());
        return l;
    }

    /** Finds an item in the hotbar, moving it there from the main inventory if needed. -1 if not found. */
    public int ensureHotbar(Predicate<ItemStack> pred) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (pred.test(inv.getItem(i))) return i;
        if (mc.player.containerMenu != mc.player.inventoryMenu) return -1;
        for (int i = 9; i < 36; i++) {
            if (!pred.test(inv.getItem(i))) continue;
            int target = 8;
            for (int h = 0; h < 9; h++) {
                if (inv.getItem(h).isEmpty()) { target = h; break; }
            }
            if (!inv.getItem(target).isEmpty()) {
                for (int h = 8; h >= 0; h--) {
                    if (!inv.getItem(h).isDamageableItem()) { target = h; break; }
                }
            }
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, i, target, ContainerInput.SWAP, mc.player);
            return target;
        }
        return -1;
    }

    public void selectBestTool(BlockState s) {
        var inv = mc.player.getInventory();
        int best = inv.getSelectedSlot();
        double bestScore = score(inv.getItem(best), s);
        for (int i = 0; i < 9; i++) {
            double sc = score(inv.getItem(i), s);
            if (sc > bestScore + 1e-6) {
                bestScore = sc;
                best = i;
            }
        }
        inv.setSelectedSlot(best);
    }

    private static double score(ItemStack st, BlockState s) {
        if (!st.isEmpty() && Settings.protectTools && st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() <= 3)
            return -1;
        float speed = st.isEmpty() ? 1 : Math.max(1, st.getDestroySpeed(s));
        boolean harvest = !s.requiresCorrectToolForDrops() || (!st.isEmpty() && st.isCorrectToolForDrops(s));
        double sc = speed * (harvest ? 1.0 : 0.3);
        // prefer not wearing out tools on instant blocks
        if (speed <= 1 && st.isDamageableItem()) sc -= 0.01;
        return sc;
    }
}
