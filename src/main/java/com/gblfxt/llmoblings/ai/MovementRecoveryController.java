package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.AABB;
import java.util.function.Consumer;

final class MovementRecoveryController {
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final double MIN_PROGRESS_DISTANCE = 0.75D;
    private static final int STUCK_SAMPLES_BEFORE_RECOVERY = 2;
    private static final int RECOVERY_STEP_INTERVAL_TICKS = 12;
    private static final int MAX_RECOVERY_ATTEMPTS = 6;

    private final CompanionEntity companion;
    private final Consumer<String> messenger;

    private Vec3 lastSamplePos = null;
    private int lastSampleTick = 0;
    private int stuckSamples = 0;

    private boolean recoveryActive = false;
    private int recoveryTicks = 0;
    private int recoveryAttempts = 0;
    private BlockPos currentGoal = null;
    private String currentGoalKind = "";
    private int lastAnnouncementTick = -200;
    private int recoveryCooldownUntilTick = 0;

    MovementRecoveryController(CompanionEntity companion, Consumer<String> messenger) {
        this.companion = companion;
        this.messenger = messenger;
    }

    void reset() {
        lastSamplePos = null;
        lastSampleTick = 0;
        stuckSamples = 0;
        recoveryActive = false;
        recoveryTicks = 0;
        recoveryAttempts = 0;
        currentGoal = null;
        currentGoalKind = "";
    }

    boolean tickTowardsEntity(Entity target, boolean allowTeleportFallback) {
        if (target == null || !target.isAlive()) {
            reset();
            return false;
        }
        return tickInternal(target.blockPosition(), "entity", allowTeleportFallback);
    }

    boolean tickTowardsPosition(BlockPos goal, boolean allowTeleportFallback) {
        if (goal == null) {
            reset();
            return false;
        }
        return tickInternal(goal, "position", allowTeleportFallback);
    }

    private boolean tickInternal(BlockPos goal, String goalKind, boolean allowTeleportFallback) {
        if (goal == null) {
            reset();
            return false;
        }

        if (!goal.equals(currentGoal) || !goalKind.equals(currentGoalKind)) {
            currentGoal = goal.immutable();
            currentGoalKind = goalKind;
            recoveryActive = false;
            recoveryTicks = 0;
            recoveryAttempts = 0;
            stuckSamples = 0;
            lastSamplePos = companion.position();
            lastSampleTick = companion.tickCount;
            return false;
        }

        double distanceToGoal = companion.position().distanceTo(Vec3.atCenterOf(goal));
        if (distanceToGoal < 2.75D) {
            reset();
            return false;
        }

        if (companion.isPassenger() || companion.isInWaterOrBubble() || companion.canFly()) {
            reset();
            return false;
        }

        if (!recoveryActive) {
            if (companion.tickCount < recoveryCooldownUntilTick) {
                return false;
            }
            sampleProgress(distanceToGoal);
            if (stuckSamples < STUCK_SAMPLES_BEFORE_RECOVERY) {
                return false;
            }

            recoveryActive = true;
            recoveryTicks = 0;
            recoveryAttempts = 0;
            companion.getNavigation().stop();
            announce("Estou preso. Vou tentar sair daqui.");
        }

        recoveryTicks++;
        if (hasMeaningfulProgress(goal)) {
            reset();
            return false;
        }

        if (recoveryTicks == 1 || recoveryTicks % RECOVERY_STEP_INTERVAL_TICKS == 0) {
            recoveryAttempts++;
            companion.getNavigation().stop();
            boolean wantsVerticalEscape = isLikelyInPit(goal) || goal.getY() > companion.blockPosition().getY() + 1;
            if (wantsVerticalEscape) {
                if (tryDigEscapeRoute(goal)) {
                    return true;
                }
                if (tryDigUpwardStep(goal)) {
                    return true;
                }
                if (tryPillarOutOfPit()) {
                    return true;
                }
                if (tryJumpTowardNearbyLedge(goal)) {
                    return true;
                }
                if (tryReachHigherGround(goal)) {
                    return true;
                }
            } else {
                if (tryReachHigherGround(goal)) {
                    return true;
                }
                if (tryJumpTowardNearbyLedge(goal)) {
                    return true;
                }
                if (tryDigEscapeRoute(goal)) {
                    return true;
                }
                if (tryPillarOutOfPit()) {
                    return true;
                }
                if (tryDigUpwardStep(goal)) {
                    return true;
                }
            }
            if (allowTeleportFallback && recoveryAttempts >= MAX_RECOVERY_ATTEMPTS && distanceToGoal > 10.0D) {
                Vec3 target = Vec3.atCenterOf(goal);
                companion.teleportTo(target.x, target.y, target.z);
                announce("Saí do aperto. Voltei para perto do destino.");
                reset();
                return true;
            }
        }

        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS && recoveryTicks > MAX_RECOVERY_ATTEMPTS * RECOVERY_STEP_INTERVAL_TICKS) {
            announce("Ainda não consegui sair, mas vou tentar de novo daqui a pouco.");
            companion.getNavigation().stop();
            recoveryCooldownUntilTick = companion.tickCount + 100;
            reset();
            return false;
        }

        return true;
    }

    private void sampleProgress(double distanceToGoal) {
        if (lastSamplePos == null) {
            lastSamplePos = companion.position();
            lastSampleTick = companion.tickCount;
            return;
        }

        if (companion.tickCount - lastSampleTick < SAMPLE_INTERVAL_TICKS) {
            return;
        }

        double moved = companion.position().distanceTo(lastSamplePos);
        boolean navigating = !companion.getNavigation().isDone() || distanceToGoal > 3.0D;

        if (navigating && moved < MIN_PROGRESS_DISTANCE && companion.onGround()) {
            stuckSamples++;
        } else {
            stuckSamples = 0;
        }

        lastSamplePos = companion.position();
        lastSampleTick = companion.tickCount;
    }

    private boolean hasMeaningfulProgress(BlockPos goal) {
        if (lastSamplePos == null) {
            lastSamplePos = companion.position();
            return false;
        }

        double moved = companion.position().distanceTo(lastSamplePos);
        double distanceToGoal = companion.position().distanceTo(Vec3.atCenterOf(goal));
        if (moved > 1.1D || distanceToGoal < 3.0D) {
            return true;
        }
        return companion.getY() > lastSamplePos.y + 0.6D;
    }

    private boolean tryReachHigherGround(BlockPos goal) {
        BlockPos best = findBestEscapeStand(goal);
        if (best == null) {
            return false;
        }

        double speed = best.getY() > companion.blockPosition().getY() ? 1.25D : 1.1D;
        companion.getNavigation().moveTo(best.getX(), best.getY(), best.getZ(), speed);
        if (best.getY() > companion.blockPosition().getY()) {
            nudgeToward(Vec3.atCenterOf(best));
        }
        return true;
    }

    private BlockPos findBestEscapeStand(BlockPos goal) {
        BlockPos current = companion.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos feet = current.offset(dx, dy, dz);
                    if (canStandAt(feet)) {
                        candidates.add(feet.immutable());
                    }
                }
            }
        }

        return candidates.stream()
                .filter(pos -> !pos.equals(current))
                .filter(pos -> pos.getY() >= current.getY())
                .min(Comparator
                        .comparingInt((BlockPos pos) -> -pos.getY())
                        .thenComparingDouble(pos -> pos.distSqr(goal)))
                .orElse(null);
    }

    private boolean isLikelyInPit(BlockPos goal) {
        BlockPos feet = companion.blockPosition();
        int solidSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos sidePos = feet.relative(direction);
            BlockState sideState = companion.level().getBlockState(sidePos);
            if (!sideState.isAir() && sideState.isFaceSturdy(companion.level(), sidePos, direction.getOpposite())) {
                solidSides++;
            }
        }

        boolean floorSolid = companion.level().getBlockState(feet.below()).isFaceSturdy(companion.level(), feet.below(), Direction.UP);
        boolean headClear = companion.level().getBlockState(feet).isAir() && companion.level().getBlockState(feet.above()).isAir();
        boolean wantsUpwardMovement = goal != null && goal.getY() > feet.getY() + 1;
        return floorSolid && headClear && (solidSides >= 3 || (solidSides >= 2 && wantsUpwardMovement));
    }

    private boolean tryJumpTowardNearbyLedge(BlockPos goal) {
        BlockPos feet = companion.blockPosition();
        Direction preferred = getPreferredHorizontalDirection(goal);
        Direction[] directions = orderDirections(preferred);

        for (Direction direction : directions) {
            BlockPos wallPos = feet.relative(direction);
            BlockPos ledgeFeet = wallPos.above();
            if (!companion.level().getBlockState(wallPos).isFaceSturdy(companion.level(), wallPos, Direction.UP)) {
                continue;
            }
            if (!canStandAt(ledgeFeet)) {
                continue;
            }

            Vec3 ledgeCenter = Vec3.atBottomCenterOf(ledgeFeet);
            companion.getLookControl().setLookAt(ledgeCenter.x, ledgeCenter.y + 0.5, ledgeCenter.z);
            Vec3 push = ledgeCenter.subtract(companion.position());
            if (push.lengthSqr() > 1.0E-4D) {
                push = push.normalize().scale(0.18D);
                companion.setDeltaMovement(push.x, Math.max(companion.getDeltaMovement().y, 0.42D), push.z);
            }
            companion.getJumpControl().jump();
            companion.getNavigation().moveTo(ledgeCenter.x, ledgeCenter.y, ledgeCenter.z, 1.0D);
            return true;
        }
        return false;
    }

    private boolean tryDigEscapeRoute(BlockPos goal) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockPos feet = companion.blockPosition();
        Direction preferred = getPreferredHorizontalDirection(goal);
        Direction[] directions = orderDirections(preferred);
        boolean wantsUpwardEscape = isLikelyInPit(goal) || goal.getY() > feet.getY() + 0.8D;

        for (Direction direction : directions) {
            if (wantsUpwardEscape && tryDigStairEscape(serverLevel, feet, direction)) {
                return true;
            }
        }

        if (wantsUpwardEscape) {
            return false;
        }

        for (Direction direction : directions) {
            BlockPos front = feet.relative(direction);
            BlockPos upperFront = front.above();
            boolean opened = tryBreakForEscape(serverLevel, front);
            opened = tryBreakForEscape(serverLevel, upperFront) || opened;
            if (opened) {
                companion.swing(InteractionHand.MAIN_HAND, true);
                companion.getNavigation().moveTo(front.getX() + 0.5D, front.getY(), front.getZ() + 0.5D, 1.0D);
                return true;
            }
        }

        return false;
    }

    private boolean tryDigStairEscape(ServerLevel serverLevel, BlockPos feet, Direction direction) {
        BlockPos front = feet.relative(direction);
        BlockPos stepFeet = front.above();
        BlockPos upperFront = front.above();
        BlockPos upperFront2 = front.above(2);

        if (canStandAt(stepFeet)) {
            Vec3 ledgeCenter = Vec3.atBottomCenterOf(stepFeet);
            companion.getLookControl().setLookAt(ledgeCenter.x, ledgeCenter.y + 0.5D, ledgeCenter.z);
            companion.getJumpControl().jump();
            companion.getNavigation().moveTo(ledgeCenter.x, ledgeCenter.y, ledgeCenter.z, 1.15D);
            return true;
        }

        boolean opened = false;
        opened = tryBreakForEscape(serverLevel, upperFront) || opened;
        opened = tryBreakForEscape(serverLevel, upperFront2) || opened;

        if (!opened) {
            return false;
        }

        Vec3 ledgeCenter = Vec3.atBottomCenterOf(stepFeet);
        companion.getLookControl().setLookAt(ledgeCenter.x, ledgeCenter.y + 0.5D, ledgeCenter.z);
        companion.getJumpControl().jump();
        companion.getNavigation().moveTo(ledgeCenter.x, ledgeCenter.y, ledgeCenter.z, 1.15D);
        companion.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    private boolean tryBreakForEscape(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(serverLevel, pos) < 0 || state.getFluidState().isSource()) {
            return false;
        }
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN) || state.is(Blocks.REINFORCED_DEEPSLATE)) {
            return false;
        }
        UltimineHelper.equipBestTool(companion, state);
        boolean removed = serverLevel.destroyBlock(pos, true, companion);
        if (removed) {
            companion.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_DESTROY);
        }
        return removed;
    }

    private boolean tryPillarOutOfPit() {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockPos feet = companion.blockPosition();
        BlockPos head = feet.above();
        if (!companion.onGround() || !serverLevel.getBlockState(feet).isAir() || !serverLevel.getBlockState(head).isAir()) {
            return false;
        }

        int slot = findDisposableBlockSlot();
        if (slot < 0) {
            return false;
        }

        AABB raisedBox = companion.getBoundingBox().move(0.0D, 1.05D, 0.0D);
        if (!serverLevel.noCollision(companion, raisedBox)) {
            return false;
        }

        ItemStack stack = companion.getItem(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        BlockState placeState = blockItem.getBlock().defaultBlockState();
        companion.setPos(companion.getX(), companion.getY() + 1.01D, companion.getZ());
        if (!serverLevel.setBlockAndUpdate(feet, placeState)) {
            companion.setPos(companion.getX(), companion.getY() - 1.01D, companion.getZ());
            return false;
        }

        stack.shrink(1);
        if (stack.isEmpty()) {
            companion.setItem(slot, ItemStack.EMPTY);
        } else {
            companion.setItem(slot, stack);
        }
        if (slot == companion.getSelectedSlot()) {
            companion.setItemSlot(EquipmentSlot.MAINHAND, companion.getItem(slot).copy());
        }

        companion.swing(InteractionHand.MAIN_HAND, true);
        companion.getNavigation().stop();
        return true;
    }

    private int findDisposableBlockSlot() {
        for (int slot = 0; slot < companion.getContainerSize(); slot++) {
            ItemStack stack = companion.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }

            BlockState state = blockItem.getBlock().defaultBlockState();
            if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.COBBLESTONE)
                    || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.NETHERRACK)
                    || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND)) {
                return slot;
            }
        }
        return -1;
    }

    private Direction getPreferredHorizontalDirection(BlockPos goal) {
        Vec3 delta = Vec3.atCenterOf(goal).subtract(companion.position());
        if (Math.abs(delta.x) >= Math.abs(delta.z)) {
            return delta.x >= 0 ? Direction.EAST : Direction.WEST;
        }
        return delta.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Direction[] orderDirections(Direction preferred) {
        Direction left = preferred.getClockWise();
        Direction right = preferred.getCounterClockWise();
        Direction back = preferred.getOpposite();
        return new Direction[] { preferred, left, right, back };
    }

    private boolean tryJumpToward(BlockPos goal) {
        Vec3 target = Vec3.atCenterOf(goal);
        nudgeToward(target);
        return true;
    }

    private void nudgeToward(Vec3 target) {
        Vec3 direction = target.subtract(companion.position());
        Vec3 horizontal = new Vec3(direction.x, 0.0D, direction.z);
        if (horizontal.lengthSqr() < 1.0E-4D) {
            return;
        }

        Vec3 normalized = horizontal.normalize().scale(0.18D);
        double upward = companion.onGround() ? 0.42D : Math.max(companion.getDeltaMovement().y, 0.12D);
        companion.setDeltaMovement(
                companion.getDeltaMovement().x * 0.4D + normalized.x,
                upward,
                companion.getDeltaMovement().z * 0.4D + normalized.z
        );
        companion.hurtMarked = true;
    }

    private boolean tryDigUpwardStep(BlockPos goal) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockPos current = companion.blockPosition();
        for (Direction direction : preferredDirections(goal)) {
            BlockPos nextFeet = current.relative(direction).above();

            if (canStandAt(nextFeet)) {
                companion.getNavigation().moveTo(nextFeet.getX(), nextFeet.getY(), nextFeet.getZ(), 1.15D);
                nudgeToward(Vec3.atCenterOf(nextFeet));
                return true;
            }

            List<BlockPos> blockers = List.of(nextFeet, nextFeet.above(), current.above(), current.above(2));
            for (BlockPos blockPos : blockers) {
                BlockState state = companion.level().getBlockState(blockPos);
                if (!canDigForRecovery(state, blockPos)) {
                    continue;
                }

                UltimineHelper.equipBestTool(companion, state);
                companion.swing(InteractionHand.MAIN_HAND, true);
                boolean removed = serverLevel.destroyBlock(blockPos, true, companion);
                if (removed) {
                    LLMoblings.LOGGER.info("[{}] Recovery broke {} at {}",
                            companion.getCompanionName(), state.getBlock(), blockPos);
                    companion.getNavigation().moveTo(nextFeet.getX(), nextFeet.getY(), nextFeet.getZ(), 1.15D);
                    nudgeToward(Vec3.atCenterOf(nextFeet));
                    return true;
                }
            }
        }

        return false;
    }

    private List<Direction> preferredDirections(BlockPos goal) {
        List<Direction> directions = new ArrayList<>(4);
        Vec3 delta = Vec3.atCenterOf(goal).subtract(companion.position());

        Direction primaryX = delta.x >= 0 ? Direction.EAST : Direction.WEST;
        Direction primaryZ = delta.z >= 0 ? Direction.SOUTH : Direction.NORTH;

        if (Math.abs(delta.x) >= Math.abs(delta.z)) {
            directions.add(primaryX);
            directions.add(primaryZ);
        } else {
            directions.add(primaryZ);
            directions.add(primaryX);
        }

        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            if (!directions.contains(direction)) {
                directions.add(direction);
            }
        }
        return directions;
    }

    private boolean canDigForRecovery(BlockState state, BlockPos pos) {
        if (state.isAir() || state.liquid()) {
            return false;
        }
        if (state.hasBlockEntity() || state.getBlock() instanceof BedBlock) {
            return false;
        }
        return state.getDestroySpeed(companion.level(), pos) >= 0.0F;
    }

    private boolean canStandAt(BlockPos feetPos) {
        BlockState feet = companion.level().getBlockState(feetPos);
        BlockState head = companion.level().getBlockState(feetPos.above());
        BlockState ground = companion.level().getBlockState(feetPos.below());
        return (!feet.isSolid() || feet.isAir())
                && (!head.isSolid() || head.isAir())
                && ground.isSolid();
    }

    private void announce(String message) {
        if (companion.tickCount - lastAnnouncementTick < 40) {
            return;
        }
        lastAnnouncementTick = companion.tickCount;
        messenger.accept(message);
    }
}
