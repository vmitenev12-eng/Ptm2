import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Второй проход патчера (запускается после Patcher):
 * 1) подмена результата PTMBlock.getBlock для ячеек LittleTiles, чтобы миниблоки с частями-дорогой и частями-тротуаром
 *    считались дорогой и тротуаром (LittleBridge);
 * 2) добавление «Разметочного карандаша» во вкладку знаков (ModTabs.addCreative -> LineMarkerItem.creativeExtra);
 * 3) при установке блока разметки (RoadMarking.onPlace) — LittleBridge.onMarkingPlaced: подгонка по верху миниблоков;
 * 4) RoadMarking.connectableTypeAt — LittleBridge.connectable: линии разметки соединяются с наклонными плитками.
 * Usage: Patcher2 in.jar out.jar
 */
public class Patcher2 implements Opcodes {
    static final String PTMBLOCK = "com/rinventor/ptm2/engine/base/PTMBlock";
    static final String MODTABS = "com/rinventor/ptm2/core/init/ModTabs";
    static final String ROADMARKING = "com/rinventor/ptm2/objects/blocks/RoadMarking";
    static final String LB = "com/rinventor/ptm2/fixes/board/LittleBridge";
    static final String LMI = "com/rinventor/ptm2/fixes/board/LineMarkerItem";
    static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
    static final String LEVEL = "Lnet/minecraft/world/level/LevelAccessor;";
    static final String BSTATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    static final String WLEVEL = "Lnet/minecraft/world/level/Level;";
    static final String BPOS = "Lnet/minecraft/core/BlockPos;";
    static final int EXPECTED = 5;

    public static void main(String[] args) throws Exception {
        int patched = 0;
        try (ZipFile in = new ZipFile(args[0]); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            Enumeration<? extends ZipEntry> en = in.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                byte[] data;
                try (InputStream is = in.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                if (e.getName().equals(PTMBLOCK + ".class")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, 0);
                    patched += hook(cn, "getBlock", "(" + LEVEL + "DDD)" + BLOCK, LB, "mapD", "(" + BLOCK + LEVEL + "DDD)" + BLOCK, 0);
                    patched += hook(cn, "getBlock", "(" + LEVEL + BPOS + ")" + BLOCK, LB, "mapP", "(" + BLOCK + LEVEL + BPOS + ")" + BLOCK, 1);
                    data = write(cn);
                } else if (e.getName().equals(MODTABS + ".class")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, 0);
                    patched += hook(cn, "addCreative",
                        "(Lnet/minecraft/world/item/CreativeModeTab;Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Consumer;)V",
                        LMI, "creativeExtra", "(Ljava/lang/Object;Ljava/lang/Object;)V", 2);
                    data = write(cn);
                } else if (e.getName().equals(ROADMARKING + ".class")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, 0);
                    patched += hook(cn, "m_6807_", "(" + BSTATE + WLEVEL + BPOS + BSTATE + "Z)V",
                        LB, "onMarkingPlaced", "(Ljava/lang/Object;" + BSTATE + WLEVEL + BPOS + ")V", 3);
                    patched += hook(cn, "connectableTypeAt", "(" + WLEVEL + BPOS + BSTATE + "Ljava/lang/Boolean;)Z",
                        LB, "connectable", "(Z" + WLEVEL + BPOS + BSTATE + "Ljava/lang/Boolean;)Z", 4);
                    data = write(cn);
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("Patcher2: hooks = " + patched);
        if (patched != EXPECTED) {
            System.err.println("ERROR: expected " + EXPECTED + " hooks (PTMBlock.getBlock x2, ModTabs.addCreative, RoadMarking.onPlace, RoadMarking.connectableTypeAt), got " + patched);
            System.exit(1);
        }
    }

    static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * kind 0: getBlock(level, x, y, z) - результат (ARETURN) пропускается через target(block, level, x, y, z);
     * kind 1: getBlock(level, pos) - через target(block, level, pos);
     * kind 2: addCreative(tab, key, consumer) - перед RETURN вызывается target(tab, consumer);
     * kind 3: в начале метода onPlace(state, level, pos, old, moving) вызывается target(this, state, level, pos);
     * kind 4: connectableTypeAt(level, pos, state, temporary) - результат (IRETURN) пропускается через target(result, level, pos, state, temporary).
     */
    static int hook(ClassNode cn, String method, String desc, String owner, String target, String targetDesc, int kind) {
        for (MethodNode m : cn.methods) {
            if (!m.name.equals(method) || !m.desc.equals(desc)) {
                continue;
            }
            if (kind == 3) {
                InsnList l = new InsnList();
                l.add(new VarInsnNode(ALOAD, 0));
                l.add(new VarInsnNode(ALOAD, 1));
                l.add(new VarInsnNode(ALOAD, 2));
                l.add(new VarInsnNode(ALOAD, 3));
                l.add(new MethodInsnNode(INVOKESTATIC, owner, target, targetDesc, false));
                m.instructions.insert(l);
                return 1;
            }
            int n = 0;
            int op = kind == 2 ? RETURN : (kind == 4 ? IRETURN : ARETURN);
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in.getOpcode() != op) {
                    continue;
                }
                InsnList l = new InsnList();
                if (kind == 0) {
                    l.add(new VarInsnNode(ALOAD, 0));
                    l.add(new VarInsnNode(DLOAD, 1));
                    l.add(new VarInsnNode(DLOAD, 3));
                    l.add(new VarInsnNode(DLOAD, 5));
                } else if (kind == 1) {
                    l.add(new VarInsnNode(ALOAD, 0));
                    l.add(new VarInsnNode(ALOAD, 1));
                } else if (kind == 4) {
                    l.add(new VarInsnNode(ALOAD, 0));
                    l.add(new VarInsnNode(ALOAD, 1));
                    l.add(new VarInsnNode(ALOAD, 2));
                    l.add(new VarInsnNode(ALOAD, 3));
                } else {
                    l.add(new VarInsnNode(ALOAD, 0));
                    l.add(new VarInsnNode(ALOAD, 2));
                }
                l.add(new MethodInsnNode(INVOKESTATIC, owner, target, targetDesc, false));
                m.instructions.insertBefore(in, l);
                n++;
            }
            return n > 0 ? 1 : 0;
        }
        return 0;
    }
}
