package com.rinventor.ptm2.fixes.board;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.rinventor.ptm2.objects.blocks.SidewalkBlock;
import com.rinventor.ptm2.objects.blocks.SidewalkSlab;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Бордюр тротуаров из миниблоков (LittleTiles), клиентская часть, мир не меняется.
 * LittleTiles замораживает состояние блока тротуара, поэтому свойство shape (форма бордюра) остаётся таким,
 * каким было у целого блока, и бордюр рисуется на всёй плитке. LittleModelFix рисует такие плитки плоскими (shape=0),
 * а этот класс раз в полсекунды находит у плиток тротуара края без соседней плитки тротуара того же уровня и
 * рисует вдоль них полоску бордюра с родной текстурой (в том числе у диагональных плиток).
 * Отключение: JVM-параметр -Dptm2.curbfix=false.
 */
@Mod.EventBusSubscriber(modid = "ptm2", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CurbOverlay {
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ptm2.curbfix"));
    private static final double WIDTH = 0.25D;
    private static final double STEP = 0.25D;
    private static final double OUT = 0.04D;
    private static final double LIFT = 0.004D;
    private static final double LEVEL_TOLERANCE = 0.1D;
    private static final int RADIUS = 3;
    private static final int PERIOD = 10;
    private static final double MAX_DIST = 96.0D;

    private static volatile boolean broken;
    private static boolean inited;
    private static long lastScan = Long.MIN_VALUE;
    private static List<Strip> strips = new ArrayList<>();
    private static final Map<Block, TextureAtlasSprite> SPRITES = new HashMap<>();

    private static Field fMinX;
    private static Field fMinZ;
    private static Field fMaxX;
    private static Field fMaxY;
    private static Field fMaxZ;
    private static Field fVx;
    private static Field fVy;
    private static Field fVz;
    private static Method mCorners;
    private static Method mAllTiles;
    private static Method mGetGrid;
    private static Method mGetState;
    private static Field fCount;

    private CurbOverlay() {
    }

    private static final class Poly {
        final double[] x = new double[4];
        final double[] z = new double[4];
        double top;
        double cx;
        double cz;
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        Block block;

        void finish() {
            for (int i = 0; i < 4; i++) {
                cx += x[i] / 4.0D;
                cz += z[i] / 4.0D;
                minX = Math.min(minX, x[i]);
                maxX = Math.max(maxX, x[i]);
                minZ = Math.min(minZ, z[i]);
                maxZ = Math.max(maxZ, z[i]);
            }
        }

        boolean contains(double px, double pz) {
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                return false;
            }
            boolean in = false;
            for (int i = 0, j = 3; i < 4; j = i++) {
                if ((z[i] > pz) != (z[j] > pz) && px < (x[j] - x[i]) * (pz - z[i]) / (z[j] - z[i]) + x[i]) {
                    in = !in;
                }
            }
            return in;
        }
    }

    private static final class Strip {
        double ax;
        double az;
        double bx;
        double bz;
        double nx;
        double nz;
        double w;
        double y;
        Block block;
    }

    private static void init() throws ReflectiveOperationException {
        ClassLoader cl = CurbOverlay.class.getClassLoader();
        Class<?> box = Class.forName("team.creative.littletiles.common.math.box.LittleBox", true, cl);
        fMinX = box.getField("minX");
        fMinZ = box.getField("minZ");
        fMaxX = box.getField("maxX");
        fMaxY = box.getField("maxY");
        fMaxZ = box.getField("maxZ");
        Class<?> v3 = Class.forName("team.creative.creativecore.common.util.math.vec.Vec3f", true, cl);
        fVx = v3.getField("x");
        fVy = v3.getField("y");
        fVz = v3.getField("z");
        Class<?> tb = Class.forName("team.creative.littletiles.common.math.box.LittleTransformableBox", true, cl);
        mCorners = tb.getMethod("getTiltedCorners");
        inited = true;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        if (!ENABLED || broken || e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return;
        }
        try {
            if (!inited) {
                init();
            }
            long t = level.getGameTime() / PERIOD;
            if (t != lastScan) {
                lastScan = t;
                scan(level, player);
            }
            List<Strip> list = strips;
            if (list.isEmpty()) {
                return;
            }
            Vec3 cam = e.getCamera().getPosition();
            PoseStack.Pose pose = e.getPoseStack().last();
            Matrix4f m = pose.pose();
            Matrix3f nm = pose.normal();
            MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
            VertexConsumer vc = bs.getBuffer(RenderType.cutout());
            double maxSq = MAX_DIST * MAX_DIST;
            for (Strip s : list) {
                double dx = s.ax - cam.x;
                double dz = s.az - cam.z;
                if (dx * dx + dz * dz > maxSq) {
                    continue;
                }
                TextureAtlasSprite sp = sprite(s.block);
                if (sp == null) {
                    continue;
                }
                int light = LevelRenderer.getLightColor(level, BlockPos.containing(s.ax, s.y + 0.5D, s.az));
                float uo = sp.getU(16.0D);
                float ui = sp.getU(12.0D);
                float v0 = sp.getV(0.0D);
                float v1 = sp.getV(16.0D);
                double ix = s.nx * s.w;
                double iz = s.nz * s.w;
                double y = s.y - cam.y;
                double ax = s.ax - cam.x;
                double az = s.az - cam.z;
                double bx = s.bx - cam.x;
                double bz = s.bz - cam.z;
                vert(vc, m, nm, ax, y, az, uo, v0, light);
                vert(vc, m, nm, bx, y, bz, uo, v1, light);
                vert(vc, m, nm, bx - ix, y, bz - iz, ui, v1, light);
                vert(vc, m, nm, ax - ix, y, az - iz, ui, v0, light);
                vert(vc, m, nm, ax - ix, y, az - iz, ui, v0, light);
                vert(vc, m, nm, bx - ix, y, bz - iz, ui, v1, light);
                vert(vc, m, nm, bx, y, bz, uo, v1, light);
                vert(vc, m, nm, ax, y, az, uo, v0, light);
            }
            bs.endBatch(RenderType.cutout());
        } catch (Throwable t) {
            broken = true;
        }
    }

    private static void vert(VertexConsumer vc, Matrix4f m, Matrix3f nm, double x, double y, double z, float u, float v, int light) {
        vc.vertex(m, (float) x, (float) y, (float) z).color(255, 255, 255, 255).uv(u, v).uv2(light).normal(nm, 0.0F, 1.0F, 0.0F).endVertex();
    }

    private static TextureAtlasSprite sprite(Block b) {
        if (SPRITES.containsKey(b)) {
            return SPRITES.get(b);
        }
        TextureAtlasSprite result = null;
        try {
            Property<?> pr = b.getStateDefinition().getProperty("shape");
            if (pr instanceof IntegerProperty) {
                IntegerProperty ip = (IntegerProperty) pr;
                BlockState s0 = b.defaultBlockState().setValue(ip, 0);
                BlockState s1 = b.defaultBlockState().setValue(ip, 1);
                BlockRenderDispatcher bd = Minecraft.getInstance().getBlockRenderer();
                RandomSource rnd = RandomSource.create(42L);
                Set<TextureAtlasSprite> base = new HashSet<>();
                for (BakedQuad q : bd.getBlockModel(s0).getQuads(s0, Direction.UP, rnd, ModelData.EMPTY, null)) {
                    base.add(q.getSprite());
                }
                for (BakedQuad q : bd.getBlockModel(s1).getQuads(s1, Direction.UP, rnd, ModelData.EMPTY, null)) {
                    if (!base.contains(q.getSprite())) {
                        result = q.getSprite();
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            result = null;
        }
        SPRITES.put(b, result);
        return result;
    }

    private static void scan(ClientLevel level, Player p) throws ReflectiveOperationException {
        List<Poly> polys = new ArrayList<>();
        int pcx = Mth.floor(p.getX()) >> 4;
        int pcz = Mth.floor(p.getZ()) >> 4;
        for (int cx = pcx - RADIUS; cx <= pcx + RADIUS; cx++) {
            for (int cz = pcz - RADIUS; cz <= pcz + RADIUS; cz++) {
                LevelChunk ch = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (ch == null) {
                    continue;
                }
                for (BlockEntity be : new ArrayList<>(ch.getBlockEntities().values())) {
                    if (be.getClass().getName().endsWith(".BETiles")) {
                        collect(be, polys);
                    }
                }
            }
        }
        Map<Long, List<Poly>> grid = new HashMap<>();
        for (Poly q : polys) {
            for (int gx = Mth.floor(q.minX); gx <= Mth.floor(q.maxX); gx++) {
                for (int gz = Mth.floor(q.minZ); gz <= Mth.floor(q.maxZ); gz++) {
                    grid.computeIfAbsent(key(gx, gz), k -> new ArrayList<>()).add(q);
                }
            }
        }
        List<Strip> out = new ArrayList<>();
        for (Poly q : polys) {
            edges(level, q, grid, out);
        }
        strips = out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static void collect(BlockEntity be, List<Poly> polys) throws ReflectiveOperationException {
        if (mGetGrid == null) {
            mGetGrid = be.getClass().getMethod("getGrid");
            mAllTiles = be.getClass().getMethod("allTiles");
        }
        Object grid = mGetGrid.invoke(be);
        if (fCount == null) {
            fCount = grid.getClass().getField("count");
        }
        double count = fCount.getInt(grid);
        BlockPos bp = be.getBlockPos();
        for (Object pr : (Iterable<?>) mAllTiles.invoke(be)) {
            Object tile = ((Map.Entry<?, ?>) pr).getValue();
            if (mGetState == null) {
                mGetState = tile.getClass().getMethod("getState");
            }
            Block b = ((BlockState) mGetState.invoke(tile)).getBlock();
            if (!(b instanceof SidewalkBlock) && !(b instanceof SidewalkSlab)) {
                continue;
            }
            for (Object box : (Iterable<?>) tile) {
                Poly q = new Poly();
                q.block = b;
                if (box.getClass().getName().endsWith("LittleTransformableBox")) {
                    Object[] c = (Object[]) mCorners.invoke(box);
                    int[] idx = {0, 1, 5, 4};
                    double top = -Double.MAX_VALUE;
                    for (int k = 0; k < 4; k++) {
                        Object v = c[idx[k]];
                        q.x[k] = bp.getX() + fVx.getFloat(v) / count;
                        q.z[k] = bp.getZ() + fVz.getFloat(v) / count;
                        top = Math.max(top, fVy.getFloat(v) / count);
                    }
                    q.top = bp.getY() + top;
                } else {
                    double x0 = bp.getX() + fMinX.getInt(box) / count;
                    double x1 = bp.getX() + fMaxX.getInt(box) / count;
                    double z0 = bp.getZ() + fMinZ.getInt(box) / count;
                    double z1 = bp.getZ() + fMaxZ.getInt(box) / count;
                    q.x[0] = x0;
                    q.z[0] = z0;
                    q.x[1] = x1;
                    q.z[1] = z0;
                    q.x[2] = x1;
                    q.z[2] = z1;
                    q.x[3] = x0;
                    q.z[3] = z1;
                    q.top = bp.getY() + fMaxY.getInt(box) / count;
                }
                q.finish();
                if (q.maxX - q.minX > 0.01D && q.maxZ - q.minZ > 0.01D) {
                    polys.add(q);
                }
            }
        }
    }

    private static boolean covered(ClientLevel level, Poly owner, Map<Long, List<Poly>> grid, double sx, double sz) {
        List<Poly> near = grid.get(key(Mth.floor(sx), Mth.floor(sz)));
        if (near != null) {
            for (Poly o : near) {
                if (o != owner && Math.abs(o.top - owner.top) <= LEVEL_TOLERANCE && o.contains(sx, sz)) {
                    return true;
                }
            }
        }
        Block nb = level.getBlockState(BlockPos.containing(sx, owner.top - 0.05D, sz)).getBlock();
        return nb instanceof SidewalkBlock || nb instanceof SidewalkSlab;
    }

    private static void edges(ClientLevel level, Poly q, Map<Long, List<Poly>> grid, List<Strip> out) {
        double w = Math.min(WIDTH, Math.min(q.maxX - q.minX, q.maxZ - q.minZ) * 0.5D);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            double px = q.x[i];
            double pz = q.z[i];
            double ex = q.x[j];
            double ez = q.z[j];
            double len = Math.hypot(ex - px, ez - pz);
            if (len < 0.01D) {
                continue;
            }
            double nx = -(ez - pz) / len;
            double nz = (ex - px) / len;
            double mx = (px + ex) / 2.0D - q.cx;
            double mz = (pz + ez) / 2.0D - q.cz;
            if (nx * mx + nz * mz < 0.0D) {
                nx = -nx;
                nz = -nz;
            }
            int n = (int) Math.ceil(len / STEP);
            double run = -1.0D;
            for (int s = 0; s < n; s++) {
                double t = (s + 0.5D) / n;
                double sx = px + (ex - px) * t + nx * OUT;
                double sz = pz + (ez - pz) * t + nz * OUT;
                boolean cov = covered(level, q, grid, sx, sz);
                if (!cov && run < 0.0D) {
                    run = (double) s / n;
                }
                if (run >= 0.0D && (cov || s == n - 1)) {
                    double end = cov ? (double) s / n : 1.0D;
                    if (end > run) {
                        Strip st = new Strip();
                        st.ax = px + (ex - px) * run;
                        st.az = pz + (ez - pz) * run;
                        st.bx = px + (ex - px) * end;
                        st.bz = pz + (ez - pz) * end;
                        st.nx = nx;
                        st.nz = nz;
                        st.w = w;
                        st.y = q.top + LIFT;
                        st.block = q.block;
                        out.add(st);
                    }
                    run = -1.0D;
                }
            }
        }
    }
}
