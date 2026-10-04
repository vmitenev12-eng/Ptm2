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
    static final String VSH = "com/rinventor/ptm2/dimension/virtual/storage/VDStorageHandler";
    static final String PTE = "com/rinventor/ptm2/engine/base/PTMEntity";
    static final String TRN = "com/rinventor/ptm2/objects/entities/vehicle/Transport";

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
            // В некоторых архивах имена записей начинаются с "/", из-за чего классы не находятся.
            // Нормализуем имена (убираем ведущие "/"), манифест пишем первым.
            java.util.List<ZipEntry> entries = new java.util.ArrayList<>();
            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                entries.add(en.nextElement());
            }
            entries.sort((a, b) -> Boolean.compare(!normalize(a.getName()).equals("META-INF/MANIFEST.MF"),
                                                   !normalize(b.getName()).equals("META-INF/MANIFEST.MF")));
            int fixedNames = 0;
            for (ZipEntry e : entries) {
                String name = normalize(e.getName());
                if (!name.equals(e.getName())) {
                    fixedNames++;
                }
                if (name.isEmpty() || written.contains(name)) {
                    continue;
                }
                byte[] data = new byte[0];
                if (!e.isDirectory()) {
                    try (InputStream in = zin.getInputStream(e)) {
                        data = in.readAllBytes();
                    }
                    if (name.endsWith(".class")) {
                        data = patchClass(name.substring(0, name.length() - 6), data);
                    }
                }
                ZipEntry ne = new ZipEntry(name);
                ne.setTime(e.getTime());
                zout.putNextEntry(ne);
                zout.write(data);
                zout.closeEntry();
                written.add(name);
            }
            System.out.println("entries written: " + written.size() + ", names normalized: " + fixedNames);
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
        String[] required = {"hook-tick", "atm-amount", "value-update-filter", "diag-hook", "remove-hook", "despawn-hook", "driver-delay"};
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

    static String normalize(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        return n;
    }

    static byte[] patchClass(String name, byte[] data) {
        boolean target = name.equals(OPD) || name.equals(ATM) || name.equals(VU) || name.equals(VSH) || name.equals(PTE) || name.equals(TRN);
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
        if (name.equals(OPD)) {
            // Диагностика: перед каждым возвратом из collectEntries(int) вызываем PtmFixes.diag(level, companyID, entries)
            for (MethodNode m : cn.methods) {
                if (m.name.equals("collectEntries") && m.desc.equals("(I)Ljava/util/List;")) {
                    int hooks = 0;
                    for (AbstractInsnNode insn : m.instructions.toArray()) {
                        if (insn.getOpcode() == ARETURN) {
                            InsnList l = new InsnList();
                            l.add(new VarInsnNode(ALOAD, 0));
                            l.add(new FieldInsnNode(GETFIELD, cn.name, "level", "Lnet/minecraft/world/level/Level;"));
                            l.add(new VarInsnNode(ILOAD, 1));
                            l.add(new VarInsnNode(ALOAD, 2));
                            l.add(new MethodInsnNode(INVOKESTATIC, FIXES, "diag", "(Lnet/minecraft/world/level/Level;ILjava/util/List;)V", false));
                            m.instructions.insertBefore(insn, l);
                            hooks++;
                        }
                    }
                    if (hooks > 0) {
                        done.add("diag-hook");
                        changed = true;
                    }
                }
            }
        }
        if (name.equals(VSH)) {
            InsnList l = new InsnList();
            l.add(new VarInsnNode(ALOAD, 0));
            l.add(new VarInsnNode(ALOAD, 1));
            l.add(new VarInsnNode(ALOAD, 2));
            l.add(new MethodInsnNode(INVOKESTATIC, FIXES, "onRemove",
                "(Lnet/minecraft/world/level/Level;Ljava/lang/String;Lcom/rinventor/ptm2/core/properties/VDMemoryIDs;)V", false));
            if (insertAtStart(cn, "remove",
                "(Lnet/minecraft/world/level/Level;Ljava/lang/String;Lcom/rinventor/ptm2/core/properties/VDMemoryIDs;)V", l)) {
                done.add("remove-hook");
                changed = true;
            }
        }
        if (name.equals(PTE)) {
            InsnList l = new InsnList();
            l.add(new VarInsnNode(ALOAD, 0));
            l.add(new VarInsnNode(ALOAD, 1));
            l.add(new MethodInsnNode(INVOKESTATIC, FIXES, "onDespawn",
                "(Ljava/lang/String;Lnet/minecraft/world/entity/Entity;)V", false));
            if (insertAtStart(cn, "syncedDespawn", "(Ljava/lang/String;Lnet/minecraft/world/entity/Entity;)V", l)) {
                done.add("despawn-hook");
                changed = true;
            }
        }
        if (name.equals(TRN)) {
            // Возврат автоматического водителя после ухода игрока: 30 с -> 5 с
            for (MethodNode m : cn.methods) {
                if (m.name.equals("updateAutomaticDriverHandoff") && m.desc.equals("()V")) {
                    for (AbstractInsnNode insn : m.instructions.toArray()) {
                        if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
                            org.objectweb.asm.tree.LdcInsnNode ldc = (org.objectweb.asm.tree.LdcInsnNode) insn;
                            if (ldc.cst instanceof Double && ((Double) ldc.cst).doubleValue() == 30.0D) {
                                ldc.cst = Double.valueOf(5.0D);
                                done.add("driver-delay");
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        if (name.equals(ATM)) {
            // Вызываем PtmFixes.atmDeposit(player, level), затем обнуляем сумму: исходный код ничего не делает.
            InsnList list = new InsnList();
            list.add(new VarInsnNode(ALOAD, 0));
            list.add(new VarInsnNode(ALOAD, 0));
            list.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/entity/player/Player", "m_9236_", "()Lnet/minecraft/world/level/Level;", false));
            list.add(new MethodInsnNode(INVOKESTATIC, FIXES, "atmDeposit", "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;)V", false));
            list.add(new InsnNode(DCONST_0));
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
