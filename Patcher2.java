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
 * Второй проход патчера (запускается после Patcher): подмена результата PTMBlock.getBlock для ячеек LittleTiles,
 * чтобы миниблоки с частями-дорогой и частями-тротуаром считались дорогой и тротуаром (LittleBridge).
 * Usage: Patcher2 in.jar out.jar
 */
public class Patcher2 implements Opcodes {
    static final String PTMBLOCK = "com/rinventor/ptm2/engine/base/PTMBlock";
    static final String LB = "com/rinventor/ptm2/fixes/board/LittleBridge";
    static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
    static final String LEVEL = "Lnet/minecraft/world/level/LevelAccessor;";

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
                    patched += hook(cn, "(" + LEVEL + "DDD)" + BLOCK, "mapD", "(" + BLOCK + LEVEL + "DDD)" + BLOCK, true);
                    patched += hook(cn, "(" + LEVEL + "Lnet/minecraft/core/BlockPos;)" + BLOCK, "mapP",
                        "(" + BLOCK + LEVEL + "Lnet/minecraft/core/BlockPos;)" + BLOCK, false);
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw);
                    data = cw.toByteArray();
                }
                ZipEntry ne = new ZipEntry(e.getName());
                out.putNextEntry(ne);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("Patcher2: PTMBlock.getBlock hooks = " + patched);
        if (patched != 2) {
            System.err.println("ERROR: PTMBlock.getBlock was not patched (expected 2 hooks)");
            System.exit(1);
        }
    }

    static int hook(ClassNode cn, String desc, String target, String targetDesc, boolean coords) {
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("getBlock") || !m.desc.equals(desc)) {
                continue;
            }
            int n = 0;
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in.getOpcode() == ARETURN) {
                    InsnList l = new InsnList();
                    l.add(new VarInsnNode(ALOAD, 0));
                    if (coords) {
                        l.add(new VarInsnNode(DLOAD, 1));
                        l.add(new VarInsnNode(DLOAD, 3));
                        l.add(new VarInsnNode(DLOAD, 5));
                    } else {
                        l.add(new VarInsnNode(ALOAD, 1));
                    }
                    l.add(new MethodInsnNode(INVOKESTATIC, LB, target, targetDesc, false));
                    m.instructions.insertBefore(in, l);
                    n++;
                }
            }
            return n > 0 ? 1 : 0;
        }
        return 0;
    }
}
