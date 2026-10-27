package ru.baritoneru.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import ru.baritoneru.Bot;
import ru.baritoneru.path.Goal;
import ru.baritoneru.path.World;

/** Digs a straight tunnel of the given size in the direction the player faced. */
public final class TunnelProcess extends BotProcess {
    private final BlockPos origin;
    private final Direction dir;
    private final int height, width, length;
    private int progress;

    public TunnelProcess(BlockPos origin, Direction dir, int height, int width, int length) {
        this.origin = origin;
        this.dir = dir;
        this.height = height;
        this.width = width;
        this.length = length;
    }

    @Override
    public String name() { return "Тоннель " + width + "x" + height; }

    @Override
    public Goal tick(Bot bot) {
        var c = bot.controls;
        manual = false;
        BlockPos feet = c.feet();
        BlockPos d = feet.subtract(origin);
        progress = Math.max(0, d.getX() * dir.getStepX() + d.getZ() * dir.getStepZ());
        if (length > 0 && progress >= length) {
            finish("§aТоннель готов§r: " + progress + " блоков.");
            return null;
        }
        World w = bot.pathing.mainWorld();
        Direction side = dir.getClockWise();
        // clear the current slice (for wide tunnels) and the next one
        for (int ahead = 0; ahead <= 1; ahead++) {
            BlockPos base = origin.relative(dir, progress + ahead);
            for (int h = height - 1; h >= 0; h--) {
                for (int o = -(width - 1) / 2; o <= width / 2; o++) {
                    BlockPos p = base.relative(side, o).above(h);
                    if (w.passable(p.getX(), p.getY(), p.getZ())) continue;
                    if (w.clearCost(p.getX(), p.getY(), p.getZ()) >= World.INF) {
                        finish("§cТоннель остановлен§r: не могу сломать " + p.toShortString() + " (бедрок/лава/запрещено).");
                        return null;
                    }
                    if (c.canReach(p)) {
                        manual = true;
                        c.breakBlock(p);
                        return null;
                    }
                }
            }
        }
        return new Goal.Block(origin.relative(dir, progress + 1));
    }

    @Override
    public String status() { return "прокопано " + progress + (length > 0 ? "/" + length : "") + " на " + dir.getName(); }
}
