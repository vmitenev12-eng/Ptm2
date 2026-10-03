import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Накладывает исправления на готовый jar мода PTM2 (байткод), ничего не пересобирая целиком.
 * Использование: java Patcher base.jar fixes.jar out.jar
 */
public class Patcher implements Opcodes {
    static final String FIXES = "com/rinventor/ptm2/fixes/PtmFixes";
    static final String OPD = "com/rinventor/ptm2/dimension/virtual/systems/OperationalDataUpdater";
    static final String ATM = "com/rinventor/ptm2/objects/blockentities/atm/ATMButtonServerboundPacket";
    static final String ATM_MENU = "com/rinventor/ptm2/objects/blockentities/atm/ATMMenu";
    static final String VU = "com/rinventor/ptm2/dimension/virtual/storage/requests/ValueUpdateServerboundPacket";
    static final String WSD = "com/rinventor/ptm2/core/data/WorldSavedData";

    static final Set<String> done = new HashSet<>();
    static int flagReplacements = 0;

    public static void main(String[] args) throws Exception {
        String base = args[0];
        String fixes = args[1];
        String out = args[2];
        Set<String> written = new HashSet<>();
        try (ZipFile zin = new ZipFile(base);
             ZipFile fz = new ZipFile(fixes);
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                byte[] data;
                try (InputStream in = zin.getInputStream(e)) {
                    data = in.readAllBytes();
                }
                String name = e.getName();
                if (name.endsWith(".class")) {
                    data = patchClass(name.substring(0, name.length() - 6), data);
                }
                ZipEntry ne = new ZipEntry(name);
                ne.setTime(e.getTime());
                zout.putNextEntry(ne);
                zout.write(data);
                zout.closeEntry();
                written.add(name);
            }
            Enumeration<? extends ZipEntry> fe = fz.entries();
            int added = 0;
            while (fe.hasMoreElements()) {
                ZipEntry e = fe.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.startsWith("com/rinventor/ptm2/fixes/") || !name.endsWith(".class")) {
                    continue;
                }
                if (written.contains(name)) {
                    throw new IllegalStateException("duplicate entry " + name);
                }
                byte[] data;
                try (InputStream in = fz.getInputStream(e)) {
                    data = in.readAllBytes();
                }
                zout.putNextEntry(new ZipEntry(name));
                zout.write(data);
                zout.closeEntry();
                added++;
                System.out.println("added class " + name);
            }
            if (added == 0) {
                throw new IllegalStateException("no fix classes found in " + fixes);
            }
        }
        System.out.println("patched: " + done + ", indefinite-flag reads replaced: " + flagReplacements);
        String[] required = {"hook-tick", "atm-amount", "value-update-filter"};
        for (String r : required) {
            if (!done.contains(r)) {
                System.err.println("ERROR: patch not applied: " + r);
                System.exit(1);
            }
        }
        if (flagReplacements == 0) {
            System.err.println("ERROR: indefinite flags were not found");
            System.exit(1);
        }
    }

    static byte[] patchClass(String name, byte[] data) {
        boolean target = name.equals(OPD) || name.equals(ATM) || name.equals(VU);
        boolean flags = contains(data, "INDEFINITE_BANK_ACCOUNT") || contains(data, "INDEFINITE_TRANSPORT_CARD");
        if (!target && !flags) {
            return data;
        }
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);
        boolean changed = false;

        if (name.equals(OPD)) {
            InsnList list = new InsnList();
            list.add(new VarInsnNode(ALOAD, 0));
            list.add(new FieldInsnNode(GETFIELD, cn.name, "level", "Lnet/minecraft/world/level/Level;"));
            list.add(new MethodInsnNode(INVOKESTATIC, FIXES, "tick", "(Lnet/minecraft/world/level/Level;)V", false));
            if (insertAtStart(cn, "run", "()V", list)) {
                done.add("hook-tick");
                changed = true;
            }
        }
        if (name.equals(ATM)) {
            InsnList list = new InsnList();
            list.add(new VarInsnNode(ALOAD, 0));
            list.add(new MethodInsnNode(INVOKESTATIC, ATM_MENU, "getTotatlValue", "(Lnet/minecraft/world/entity/Entity;)D", false));
            list.add(new VarInsnNode(DSTORE, 1));
            if (insertAtStart(cn, "handle", "(Lnet/minecraft/world/entity/player/Player;D)V", list)) {
                done.add("atm-amount");
                changed = true;
            }
        }
        if (name.equals(VU)) {
            InsnList list = new InsnList();
            list.add(new VarInsnNode(ALOAD, 0));
            list.add(new VarInsnNode(ALOAD, 0));
            list.add(new FieldInsnNode(GETFIELD, cn.name, "type", "I"));
            list.add(new VarInsnNode(ALOAD, 0));
            list.add(new FieldInsnNode(GETFIELD, cn.name, "propertyID", "I"));
            list.add(new MethodInsnNode(INVOKESTATIC, FIXES, "safeProp", "(II)I", false));
            list.add(new FieldInsnNode(PUTFIELD, cn.name, "propertyID", "I"));
            boolean unlocked = false;
            for (FieldNode f : cn.fields) {
                if (f.name.equals("propertyID")) {
                    f.access = f.access & ~ACC_FINAL;
                    unlocked = true;
                }
            }
            if (unlocked && insertAtStart(cn, "handle", "(Lnet/minecraft/server/level/ServerPlayer;)V", list)) {
                done.add("value-update-filter");
                changed = true;
            }
        }
        if (flags) {
            for (MethodNode m : cn.methods) {
                AbstractInsnNode[] insns = m.instructions.toArray();
                for (AbstractInsnNode insn : insns) {
                    if (insn.getOpcode() == GETFIELD && insn instanceof FieldInsnNode) {
                        FieldInsnNode f = (FieldInsnNode) insn;
                        if (f.owner.equals(WSD) && f.desc.equals("Z")
                            && (f.name.equals("INDEFINITE_BANK_ACCOUNT") || f.name.equals("INDEFINITE_TRANSPORT_CARD"))) {
                            InsnList repl = new InsnList();
                            repl.add(new InsnNode(POP));
                            repl.add(new InsnNode(ICONST_0));
                            m.instructions.insertBefore(insn, repl);
                            m.instructions.remove(insn);
                            flagReplacements++;
                            changed = true;
                        }
                    }
                }
            }
        }
        if (!changed) {
            return data;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static boolean insertAtStart(ClassNode cn, String method, String desc, InsnList list) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(method) && m.desc.equals(desc)) {
                m.instructions.insert(list);
                return true;
            }
        }
        System.err.println("method not found: " + cn.name + "." + method + desc);
        return false;
    }

    static boolean contains(byte[] data, String text) {
        byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
