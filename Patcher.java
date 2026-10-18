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
import org.objectweb.asm.tree.TypeInsnNode;
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
    static final String GATE_IN = "com/rinventor/ptm2/objects/blockentities/gate/GateIn";
    static final String VEH = "com/rinventor/ptm2/objects/entities/vehicle/Vehicle";
    static final String GATE_EXIT = "com/rinventor/ptm2/objects/blockentities/gate/GateExit";
    static final String TM = "com/rinventor/ptm2/dimension/physical/TransportManager";
    static final String VUT = "com/rinventor/ptm2/dimension/physical/VehicleUtils";
    static final String TLU = "com/rinventor/ptm2/dimension/virtual/systems/TrafficLanesUpdater";
    static final String RPATH = "com/rinventor/ptm2/dimension/virtual/util/RoadPath";
    static final String LES = "com/rinventor/ptm2/dimension/digital/screens/LineEditScreen";
    static final String TBM = "com/rinventor/ptm2/dimension/physical/TickableBlocksManager";
    static final Set<String> refs = new HashSet<>();
    static final Set<String> fixesMethods = new HashSet<>();

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
                if (name.equals(FIXES + ".class")) {
                    ClassNode fcn = new ClassNode();
                    new ClassReader(data).accept(fcn, 0);
                    for (MethodNode fm : fcn.methods) {
                        fixesMethods.add(fm.name + fm.desc);
                    }
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
        boolean mismatch = false;
        for (String r : refs) {
            if (!fixesMethods.contains(r)) {
                System.err.println("ERROR: PtmFixes has no method " + r + " (PtmFixes.java in the repo is outdated?)");
                mismatch = true;
            }
        }
        if (mismatch) {
            System.exit(1);
        }
        String[] required = {"hook-tick", "atm-amount", "value-update-filter", "diag-hook", "remove-hook", "despawn-hook", "driver-delay", "gate-bankcard", "boarding-fare", "gate-exit-radius", "gate-exit-auto", "gate-in-radius", "terminal-cap", "arrival-reach", "arrival-inside", "lamp-cap", "red-commit", "patience", "bypass-still", "merge-check", "player-ahead", "merge-width", "parallel-width", "auto-links", "link-revision", "bus-priority", "validator", "wheel-filter", "hull", "depot-ui", "depot-select", "depot-order", "depot-spawn", "depot-spawn2", "depot-park", "depot-return", "terminal-pax", "depot-clear", "depot-crash", "gravity-reset"};
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
        boolean target = name.equals(OPD) || name.equals(ATM) || name.equals(VU) || name.equals(VSH) || name.equals(PTE) || name.equals(TRN) || name.equals(GATE_IN) || name.equals(VEH) || name.equals(GATE_EXIT) || name.equals(TM) || name.equals(TBM) || name.equals(VUT) || name.equals(TLU) || name.equals(RPATH) || name.equals(LES);
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
        if (name.equals(GATE_IN)) {
            // Банковская карта на входном турникете: вместо списания «поездок» (TransportCard.useCard)
            // списываем цену билета со счёта игрока в пользу компании, как для смартфона.
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("m_6227_")) {
                    continue;
                }
                AbstractInsnNode[] all = m.instructions.toArray();
                for (int i = 0; i < all.length; i++) {
                    AbstractInsnNode insn = all[i];
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.getOpcode() == INVOKESTATIC && mi.name.equals("useCard")
                            && mi.owner.equals("com/rinventor/ptm2/objects/items/TransportCard")
                            && i >= 2 && all[i - 1].getOpcode() == ICONST_1 && all[i - 2].getOpcode() == ALOAD) {
                            InsnList repl = new InsnList();
                            repl.add(new VarInsnNode(ALOAD, 0));
                            repl.add(new VarInsnNode(ALOAD, 2));
                            repl.add(new VarInsnNode(ALOAD, 3));
                            repl.add(new VarInsnNode(ALOAD, 4));
                            repl.add(new VarInsnNode(ALOAD, 1));
                            repl.add(new FieldInsnNode(GETSTATIC, GATE_IN, "COMPANY", "Lnet/minecraft/world/level/block/state/properties/IntegerProperty;"));
                            repl.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState", "m_61143_",
                                "(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;", false));
                            repl.add(new org.objectweb.asm.tree.TypeInsnNode(CHECKCAST, "java/lang/Integer"));
                            repl.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                            repl.add(new MethodInsnNode(INVOKEVIRTUAL, GATE_IN, "openWithSmartphone",
                                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;I)Z", false));
                            m.instructions.insertBefore(all[i - 2], repl);
                            m.instructions.remove(all[i - 2]);
                            m.instructions.remove(all[i - 1]);
                            m.instructions.remove(insn);
                            done.add("gate-bankcard");
                            changed = true;
                            break; // только первое вхождение: ветка банковской карты
                        }
                    }
                }
            }
        }
        if (name.equals(VEH)) {
            // Оплата проезда при посадке: оборачиваем Vehicle.startRiding(Entity,int).
            // Оригинал переименовываем в ptmStartRiding, новый startRiding сначала спрашивает PtmFixes.allowBoard.
            final String desc = "(Lnet/minecraft/world/entity/Entity;I)V";
            MethodNode orig = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("startRiding") && m.desc.equals(desc)) {
                    orig = m;
                }
            }
            if (orig != null) {
                orig.name = "ptmStartRiding";
                MethodNode w = new MethodNode(ACC_PUBLIC, "startRiding", desc, null, null);
                org.objectweb.asm.tree.LabelNode end = new org.objectweb.asm.tree.LabelNode();
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new VarInsnNode(ILOAD, 2));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "m_9236_",
                    "()Lnet/minecraft/world/level/Level;", false));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "allowBoard",
                    "(L" + VEH + ";Lnet/minecraft/world/entity/Entity;ILnet/minecraft/world/level/Level;)Z", false));
                w.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(IFEQ, end));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new VarInsnNode(ILOAD, 2));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, VEH, "ptmStartRiding", desc, false));
                w.instructions.add(end);
                w.instructions.add(new org.objectweb.asm.tree.FrameNode(F_SAME, 0, null, 0, null));
                w.instructions.add(new InsnNode(RETURN));
                w.maxStack = 5;
                w.maxLocals = 3;
                cn.methods.add(w);
                done.add("boarding-fare");
                changed = true;
            }
        }
        if (name.equals(VEH)) {
            // Автобус исчезает: игрокам рядом сбрасываем флаг «ходьба по полу» (иначе гравитация остаётся выключенной)
            for (MethodNode m : cn.methods) {
                if (m.name.equals("removeOrGoVirtual") && m.desc.equals("()V")) {
                    InsnList goneInit = new InsnList();
                    goneInit.add(new VarInsnNode(ALOAD, 0));
                    goneInit.add(new MethodInsnNode(INVOKESTATIC, FIXES, "vehicleGone", "(L" + VEH + ";)V", false));
                    m.instructions.insert(goneInit);
                    done.add("gravity-reset");
                    changed = true;
                }
            }
            // Корпус автобуса и контроль оплаты: вызов в начале тика Vehicle
            for (MethodNode m : cn.methods) {
                if (m.name.equals("m_8119_") && m.desc.equals("()V")) {
                    InsnList tickInit = new InsnList();
                    tickInit.add(new VarInsnNode(ALOAD, 0));
                    tickInit.add(new MethodInsnNode(INVOKESTATIC, FIXES, "vehicleTick", "(L" + VEH + ";)V", false));
                    m.instructions.insert(tickInit);
                    done.add("hull");
                    changed = true;
                }
            }
            // Валидаторы: перехват правого клика по валидатору (кнопка мыши заменяется на -1, если клик обработан)
            MethodNode origIb = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("interactionBy") && m.desc.equals("(Lnet/minecraft/world/entity/player/Player;DDDIZZ)V")) {
                    origIb = m;
                }
            }
            if (origIb != null) {
                origIb.name = "ptmInteractOrig";
                MethodNode w = new MethodNode(ACC_PUBLIC, "interactionBy", origIb.desc, null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new VarInsnNode(DLOAD, 2));
                w.instructions.add(new VarInsnNode(DLOAD, 4));
                w.instructions.add(new VarInsnNode(DLOAD, 6));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new VarInsnNode(ILOAD, 8));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "validatorClick",
                    "(L" + VEH + ";Lnet/minecraft/world/entity/player/Player;I)I", false));
                w.instructions.add(new VarInsnNode(ILOAD, 9));
                w.instructions.add(new VarInsnNode(ILOAD, 10));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, VEH, "ptmInteractOrig", origIb.desc, false));
                w.instructions.add(new InsnNode(RETURN));
                w.maxStack = 14;
                w.maxLocals = 11;
                cn.methods.add(w);
                done.add("validator");
                changed = true;
            }
            // Фильтр скачков высоты колёс: контекст машины и замена updateLocation колеса в updateSection
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("updateSection")) {
                    continue;
                }
                boolean any = false;
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.getOpcode() == INVOKEVIRTUAL && mi.owner.equals("com/rinventor/ptm2/engine/vehicle/WheelLocation")
                            && mi.name.equals("updateLocation") && mi.desc.equals("(Lnet/minecraft/world/level/Level;DDDD)V")) {
                            m.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, FIXES, "wheelUpdate",
                                "(Lcom/rinventor/ptm2/engine/vehicle/WheelLocation;Lnet/minecraft/world/level/Level;DDDD)V", false));
                            any = true;
                        }
                    }
                }
                if (any) {
                    InsnList init = new InsnList();
                    init.add(new VarInsnNode(ALOAD, 0));
                    init.add(new MethodInsnNode(INVOKESTATIC, FIXES, "setCtx", "(L" + VEH + ";)V", false));
                    m.instructions.insert(init);
                    done.add("wheel-filter");
                    changed = true;
                }
            }
        }
        if (name.equals(VEH)) {
            // ИИ движения: canMove() -> обёртка с «терпением»; красный после въезда на перекрёсток не повторяется
            MethodNode origCm = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("canMove") && m.desc.equals("()Z")) {
                    origCm = m;
                }
            }
            if (origCm != null) {
                origCm.name = "ptmCanMoveOrig";
                for (AbstractInsnNode insn : origCm.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.getOpcode() == INVOKESTATIC && mi.owner.equals(VUT) && mi.name.equals("forbiddingTrafficLightAhead")) {
                            origCm.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, FIXES, "trafficRed",
                                "(L" + VEH + ";)Z", false));
                            done.add("red-commit");
                        }
                    }
                }
                MethodNode w = new MethodNode(ACC_PUBLIC, "canMove", "()Z", null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, VEH, "ptmCanMoveOrig", "()Z", false));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "m_9236_",
                    "()Lnet/minecraft/world/level/Level;", false));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "canMove",
                    "(L" + VEH + ";ZLnet/minecraft/world/level/Level;)Z", false));
                w.instructions.add(new InsnNode(IRETURN));
                w.maxStack = 4;
                w.maxLocals = 1;
                cn.methods.add(w);
                done.add("patience");
                changed = true;
            }
        }
        if (name.equals(LES)) {
            // Выбор депо вручную: обработчик списка записывал в линию номер зоны, а открытие и сохранение экрана
            // работают с порядковым номером в списке. Теперь обработчик тоже записывает порядковый номер.
            for (MethodNode m : cn.methods) {
                if (!m.name.startsWith("lambda$")) {
                    continue;
                }
                AbstractInsnNode[] all = m.instructions.toArray();
                for (int i = 1; i < all.length; i++) {
                    if (all[i].getOpcode() == PUTFIELD && all[i] instanceof org.objectweb.asm.tree.FieldInsnNode
                        && ((org.objectweb.asm.tree.FieldInsnNode) all[i]).name.equals("depotID")
                        && all[i - 1] instanceof MethodInsnNode
                        && ((MethodInsnNode) all[i - 1]).name.equals("intValue")
                        && ((MethodInsnNode) all[i - 1]).owner.equals("java/lang/Integer")) {
                        InsnList repl = new InsnList();
                        repl.add(new InsnNode(POP));
                        repl.add(new VarInsnNode(ALOAD, 0));
                        repl.add(new org.objectweb.asm.tree.FieldInsnNode(GETFIELD, LES, "CBDepot", "Lcom/rinventor/ptm2/engine/component/ComboBox;"));
                        repl.add(new MethodInsnNode(INVOKEVIRTUAL, "com/rinventor/ptm2/engine/component/ComboBox", "getSelectedIndex", "()I", false));
                        m.instructions.insert(all[i - 1], repl);
                        m.instructions.remove(all[i - 1]);
                        done.add("depot-select");
                        changed = true;
                    }
                }
            }
            // Экран линии: выбор депо работает (сброс «в разработке» отключён), список депо в стабильном порядке по номеру
            boolean firstSel = true;
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (!(insn instanceof MethodInsnNode)) {
                        continue;
                    }
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if (m.name.equals("m_88315_") && firstSel && mi.getOpcode() == INVOKEVIRTUAL
                        && mi.owner.equals("com/rinventor/ptm2/engine/component/ComboBox") && mi.name.equals("getSelectedIndex")) {
                        firstSel = false;
                        InsnList repl = new InsnList();
                        repl.add(new InsnNode(POP));
                        repl.add(new InsnNode(ICONST_M1));
                        m.instructions.insert(insn, repl);
                        m.instructions.remove(insn);
                        done.add("depot-ui");
                        changed = true;
                    } else if (mi.getOpcode() == INVOKEVIRTUAL && mi.owner.equals("java/util/concurrent/ConcurrentHashMap")
                        && mi.name.equals("entrySet")) {
                        AbstractInsnNode prev = insn.getPrevious();
                        while (prev != null && prev.getOpcode() < 0) {
                            prev = prev.getPrevious();
                        }
                        AbstractInsnNode prev2 = prev == null ? null : prev.getPrevious();
                        while (prev2 != null && prev2.getOpcode() < 0) {
                            prev2 = prev2.getPrevious();
                        }
                        if (prev2 instanceof FieldInsnNode && ((FieldInsnNode) prev2).name.equals("DEPOT")) {
                            m.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, FIXES, "sortedEntries",
                                "(Ljava/util/concurrent/ConcurrentHashMap;)Ljava/util/Set;", false));
                            done.add("depot-order");
                            changed = true;
                        }
                    }
                }
            }
        }
        if (name.equals(TM)) {
            // Депо: точка появления, возврат в депо, пассажиры на конечной остановке
            for (MethodNode m : cn.methods) {
                if (m.name.equals("createVirtualVehicle") && m.desc.startsWith("(Lnet/minecraft/world/level/Level;Lcom/rinventor/ptm2/dimension/virtual/types/VDTransportLine;IIILcom/rinventor/ptm2/engine/computing/DirectionalPos;")) {
                    InsnList l = new InsnList();
                    l.add(new VarInsnNode(ALOAD, 5));
                    l.add(new VarInsnNode(ALOAD, 0));
                    l.add(new VarInsnNode(ALOAD, 1));
                    l.add(new VarInsnNode(ILOAD, 3));
                    l.add(new VarInsnNode(ILOAD, 4));
                    l.add(new MethodInsnNode(INVOKESTATIC, FIXES, "spawnFix",
                        "(Lcom/rinventor/ptm2/engine/computing/DirectionalPos;Lnet/minecraft/world/level/Level;Lcom/rinventor/ptm2/dimension/virtual/types/VDTransportLine;II)I", false));
                    l.add(new VarInsnNode(ISTORE, 4));
                    m.instructions.insert(l);
                    done.add("depot-spawn2");
                    changed = true;
                }
                if (m.name.equals("trackRoutesAndSchedules")) {
                    for (AbstractInsnNode insn : m.instructions.toArray()) {
                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode mi = (MethodInsnNode) insn;
                            if (mi.getOpcode() == INVOKESTATIC && mi.name.equals("getRoute") && mi.owner.equals(TM)) {
                                InsnList l = new InsnList();
                                l.add(new InsnNode(DUP));
                                l.add(new MethodInsnNode(INVOKESTATIC, FIXES, "noteRoute",
                                    "(Lcom/rinventor/ptm2/dimension/virtual/types/VDRoute;)V", false));
                                m.instructions.insert(insn, l);
                                done.add("depot-ctx");
                            } else if (mi.getOpcode() == INVOKESTATIC && mi.name.equals("getLine") && mi.owner.equals(TM)) {
                                InsnList l = new InsnList();
                                l.add(new InsnNode(DUP));
                                l.add(new MethodInsnNode(INVOKESTATIC, FIXES, "noteLine",
                                    "(Lcom/rinventor/ptm2/dimension/virtual/types/VDTransportLine;)V", false));
                                m.instructions.insert(insn, l);
                            } else if (mi.getOpcode() == INVOKESPECIAL && mi.owner.equals("com/rinventor/ptm2/engine/computing/DirectionalPos")
                                && mi.name.equals("<init>") && mi.desc.equals("(DDDF)V")) {
                                InsnList l = new InsnList();
                                l.add(new InsnNode(DUP));
                                l.add(new VarInsnNode(ALOAD, 0));
                                l.add(new MethodInsnNode(INVOKESTATIC, FIXES, "spawnAdjust",
                                    "(Lcom/rinventor/ptm2/engine/computing/DirectionalPos;Lnet/minecraft/world/level/Level;)V", false));
                                m.instructions.insert(insn, l);
                                done.add("depot-spawn");
                                changed = true;
                            }
                        }
                    }
                }
            }
            MethodNode origDa = null;
            MethodNode origPc = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("getDriveAwayDestination")) {
                    origDa = m;
                } else if (m.name.equals("passengersChange") && m.desc.startsWith("(Lnet/minecraft/world/level/Level;IIII)")) {
                    origPc = m;
                }
            }
            if (origDa != null) {
                String d = origDa.desc;
                origDa.name = "ptmDriveAwayOrig";
                MethodNode w = new MethodNode(origDa.access, "getDriveAwayDestination", d, null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, TM, "ptmDriveAwayOrig", d, false));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "driveAway",
                    "(Lnet/minecraft/world/level/Level;Lcom/rinventor/ptm2/dimension/virtual/types/VDTrackableVehicle;Ljava/lang/Object;)Ljava/lang/Object;", false));
                w.instructions.add(new TypeInsnNode(CHECKCAST, "net/minecraft/world/phys/Vec3"));
                w.instructions.add(new InsnNode(ARETURN));
                w.maxStack = 6;
                w.maxLocals = 2;
                cn.methods.add(w);
                done.add("depot-return");
                changed = true;
            }
            if (origPc != null) {
                String d = origPc.desc;
                String ret = d.substring(d.indexOf(')') + 1);
                origPc.name = "ptmPaxOrig";
                MethodNode w = new MethodNode(origPc.access, "passengersChange", d, null, null);
                for (int i = 0; i < 5; i++) {
                    w.instructions.add(new VarInsnNode(i == 0 ? ALOAD : ILOAD, i));
                }
                for (int i = 0; i < 5; i++) {
                    w.instructions.add(new VarInsnNode(i == 0 ? ALOAD : ILOAD, i));
                }
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, TM, "ptmPaxOrig", d, false));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "passengers",
                    "(Lnet/minecraft/world/level/Level;IIII" + ret + ")" + ret, false));
                w.instructions.add(new InsnNode(ARETURN));
                w.maxStack = 12;
                w.maxLocals = 5;
                cn.methods.add(w);
                done.add("terminal-pax");
                changed = true;
            }
        }
        if (name.equals(VUT)) {
            // Внутри депо транспорт не упирается в другой и не «разбивается» (clearance и аварии)
            MethodNode origCl = null;
            MethodNode origCr = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("clearanceBlockerAhead") && m.desc.equals("(L" + VEH + ";D)L" + VEH + ";")) {
                    origCl = m;
                } else if (m.name.equals("crashedIntoAnotherVehicle") && m.desc.equals("(L" + VEH + ";)Z")) {
                    origCr = m;
                }
            }
            if (origCl != null) {
                origCl.name = "ptmClearOrig";
                MethodNode w = new MethodNode(ACC_PUBLIC | ACC_STATIC, "clearanceBlockerAhead", origCl.desc, null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(DLOAD, 1));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(DLOAD, 1));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, VUT, "ptmClearOrig", origCl.desc, false));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "m_9236_",
                    "()Lnet/minecraft/world/level/Level;", false));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "clearance",
                    "(L" + VEH + ";DL" + VEH + ";Lnet/minecraft/world/level/Level;)L" + VEH + ";", false));
                w.instructions.add(new InsnNode(ARETURN));
                w.maxStack = 8;
                w.maxLocals = 3;
                cn.methods.add(w);
                done.add("depot-clear");
                changed = true;
            }
            if (origCr != null) {
                origCr.name = "ptmCrashOrig";
                MethodNode w = new MethodNode(ACC_PUBLIC | ACC_STATIC, "crashedIntoAnotherVehicle", origCr.desc, null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, VUT, "ptmCrashOrig", origCr.desc, false));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "m_9236_",
                    "()Lnet/minecraft/world/level/Level;", false));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "noCrash",
                    "(L" + VEH + ";ZLnet/minecraft/world/level/Level;)Z", false));
                w.instructions.add(new InsnNode(IRETURN));
                w.maxStack = 5;
                w.maxLocals = 1;
                cn.methods.add(w);
                done.add("depot-crash");
                changed = true;
            }
        }
        if (name.equals(RPATH)) {
            // Автосвязки перестроения в графе маршрутов + ревизия графа
            for (MethodNode m : cn.methods) {
                if (m.name.equals("createGraphLanes")) {
                    for (AbstractInsnNode insn : m.instructions.toArray()) {
                        if (insn instanceof MethodInsnNode && insn.getOpcode() == INVOKEVIRTUAL) {
                            MethodInsnNode mi = (MethodInsnNode) insn;
                            if (mi.owner.equals("java/util/concurrent/ConcurrentHashMap") && mi.name.equals("values")) {
                                AbstractInsnNode prev = insn.getPrevious();
                                while (prev != null && prev.getOpcode() < 0) {
                                    prev = prev.getPrevious();
                                }
                                AbstractInsnNode prev2 = prev == null ? null : prev.getPrevious();
                                while (prev2 != null && prev2.getOpcode() < 0) {
                                    prev2 = prev2.getPrevious();
                                }
                                if (prev2 instanceof FieldInsnNode && ((FieldInsnNode) prev2).name.equals("TRAFFIC_LANE")) {
                                    InsnList repl = new InsnList();
                                    repl.add(new VarInsnNode(ALOAD, 0));
                                    repl.add(new FieldInsnNode(GETFIELD, RPATH, "level", "Lnet/minecraft/world/level/Level;"));
                                    repl.add(new MethodInsnNode(INVOKESTATIC, FIXES, "lanesWithLinks",
                                        "(Ljava/util/concurrent/ConcurrentHashMap;Lnet/minecraft/world/level/Level;)Ljava/util/Collection;", false));
                                    m.instructions.insert(insn, repl);
                                    m.instructions.remove(insn);
                                    done.add("auto-links");
                                    changed = true;
                                }
                            }
                        }
                    }
                }
            }
            MethodNode origRev = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("graphRevision") && m.desc.equals("(Z)J")) {
                    origRev = m;
                }
            }
            if (origRev != null) {
                origRev.name = "ptmGraphRevOrig";
                origRev.access = (origRev.access & ~ACC_PRIVATE) & ~ACC_PROTECTED;
                MethodNode w = new MethodNode(ACC_PRIVATE, "graphRevision", "(Z)J", null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ILOAD, 1));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, RPATH, "ptmGraphRevOrig", "(Z)J", false));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new FieldInsnNode(GETFIELD, RPATH, "level", "Lnet/minecraft/world/level/Level;"));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "linkRevision", "(JLnet/minecraft/world/level/Level;)J", false));
                w.instructions.add(new InsnNode(LRETURN));
                w.maxStack = 5;
                w.maxLocals = 2;
                cn.methods.add(w);
                done.add("link-revision");
                changed = true;
            }
        }
        if (name.equals(TLU)) {
            // Соседние (параллельные) полосы искались на расстоянии 4-7 блоков; у нас полосы шириной 3-5
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("getParallelLanes")) {
                    continue;
                }
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
                        org.objectweb.asm.tree.LdcInsnNode ld = (org.objectweb.asm.tree.LdcInsnNode) insn;
                        if (ld.cst instanceof Double && ((Double) ld.cst) == 4.0D) {
                            ld.cst = Double.valueOf(3.0D);
                            done.add("parallel-width");
                            changed = true;
                        }
                    }
                }
            }
        }
        if (name.equals(VUT)) {
            // Объезд: в laneChangeCheck «машина впереди на ручнике» -> PtmFixes.obstacleStill (ручник или долго стоит)
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("laneChangeCheck")) {
                    continue;
                }
                // контекст машины для playerAhead
                InsnList ctxInit = new InsnList();
                ctxInit.add(new VarInsnNode(ALOAD, 0));
                ctxInit.add(new MethodInsnNode(INVOKESTATIC, FIXES, "setCtx", "(L" + VEH + ";)V", false));
                m.instructions.insert(ctxInit);
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.getOpcode() == INVOKESTATIC && mi.owner.equals("com/rinventor/ptm2/engine/base/PTMEntity")
                            && mi.name.equals("exists")) {
                            m.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, FIXES, "playerAhead", mi.desc, false));
                            done.add("player-ahead");
                        }
                    } else if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
                        org.objectweb.asm.tree.LdcInsnNode ld = (org.objectweb.asm.tree.LdcInsnNode) insn;
                        // ширина полос 3-5 блоков: поиск целевой полосы начинаем с 3 блоков, а не с 4
                        if (ld.cst instanceof Double && ((Double) ld.cst) == 4.0D) {
                            ld.cst = Double.valueOf(3.0D);
                            done.add("merge-width");
                        }
                    }
                }
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof org.objectweb.asm.tree.FieldInsnNode) {
                        org.objectweb.asm.tree.FieldInsnNode fi = (org.objectweb.asm.tree.FieldInsnNode) insn;
                        if (fi.getOpcode() == GETFIELD && fi.owner.equals(VEH) && fi.name.equals("handBrake")) {
                            m.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, FIXES, "obstacleStill",
                                "(L" + VEH + ";)Z", false));
                            done.add("bypass-still");
                            changed = true;
                        }
                    }
                }
            }
        }
        if (name.equals(VUT)) {
            // Приоритет автобуса, выезжающего с остановки: расширенная проверка
            MethodNode origGw = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("giveWayToBus") && m.desc.equals("(L" + VEH + ";)Z")) {
                    origGw = m;
                }
            }
            if (origGw != null) {
                origGw.name = "ptmGiveWayOrig";
                MethodNode w = new MethodNode(ACC_PUBLIC | ACC_STATIC, "giveWayToBus", origGw.desc, null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, VUT, "ptmGiveWayOrig", origGw.desc, false));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "m_9236_",
                    "()Lnet/minecraft/world/level/Level;", false));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "giveWay",
                    "(L" + VEH + ";ZLnet/minecraft/world/level/Level;)Z", false));
                w.instructions.add(new InsnNode(IRETURN));
                w.maxStack = 4;
                w.maxLocals = 1;
                cn.methods.add(w);
                done.add("bus-priority");
                changed = true;
            }
        }
        if (name.equals(VUT)) {
            // Перестроение: mergeLaneHasObstacle -> дополнительная проверка разметки и машин в целевой полосе
            MethodNode origMl = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("mergeLaneHasObstacle")
                    && m.desc.equals("(L" + VEH + ";L" + "com/rinventor/ptm2/dimension/virtual/types/VDTrafficLane;)Z")) {
                    origMl = m;
                }
            }
            if (origMl != null) {
                origMl.name = "ptmMergeOrig";
                MethodNode w = new MethodNode(ACC_PUBLIC | ACC_STATIC, "mergeLaneHasObstacle", origMl.desc, null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ALOAD, 1));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, VUT, "ptmMergeOrig", origMl.desc, false));
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "m_9236_",
                    "()Lnet/minecraft/world/level/Level;", false));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, FIXES, "mergeBlocked",
                    "(L" + VEH + ";Lcom/rinventor/ptm2/dimension/virtual/types/VDTrafficLane;ZLnet/minecraft/world/level/Level;)Z", false));
                w.instructions.add(new InsnNode(IRETURN));
                w.maxStack = 5;
                w.maxLocals = 2;
                cn.methods.add(w);
                done.add("merge-check");
                changed = true;
            }
        }
        if (name.equals(GATE_EXIT)) {
            // Выходной турникет: игрок открывает его, просто подойдя (радиус 0.55 вместо 0.3, без условия «уже открыт»);
            // правый клик тоже работает с большего расстояния.
            for (MethodNode m : cn.methods) {
                boolean isOpen = m.name.equals("gateOpen");
                boolean isWay = m.name.equals("playerCorrectWay");
                if (!isOpen && !isWay) {
                    continue;
                }
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
                        org.objectweb.asm.tree.LdcInsnNode ldc = (org.objectweb.asm.tree.LdcInsnNode) insn;
                        if (ldc.cst instanceof Double && ((Double) ldc.cst).doubleValue() == 0.3D) {
                            ldc.cst = Double.valueOf(0.55D);
                            changed = true;
                            done.add("gate-exit-radius");
                            break;
                        }
                    }
                }
                if (isOpen) {
                    AbstractInsnNode[] all = m.instructions.toArray();
                    for (int i = 0; i < all.length; i++) {
                        if (all[i] instanceof FieldInsnNode) {
                            FieldInsnNode f = (FieldInsnNode) all[i];
                            if (f.getOpcode() == GETSTATIC && f.name.equals("OPEN") && f.owner.equals(GATE_EXIT)
                                && i >= 1 && i + 3 < all.length
                                && all[i - 1].getOpcode() == ALOAD
                                && all[i + 3].getOpcode() == INVOKEVIRTUAL) {
                                m.instructions.insertBefore(all[i - 1], new InsnNode(ICONST_1));
                                for (int k = i - 1; k <= i + 3; k++) {
                                    m.instructions.remove(all[k]);
                                }
                                changed = true;
                                done.add("gate-exit-auto");
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (name.equals(GATE_IN)) {
            // Входной турникет: проверка присутствия игрока в радиусе 1.2 вместо 0.3
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
                        org.objectweb.asm.tree.LdcInsnNode ldc = (org.objectweb.asm.tree.LdcInsnNode) insn;
                        if (ldc.cst instanceof Double && ((Double) ldc.cst).doubleValue() == 0.3D) {
                            ldc.cst = Double.valueOf(1.2D);
                            changed = true;
                            done.add("gate-in-radius");
                        }
                    }
                }
            }
        }
        if (name.equals(TM)) {
            // Ожидание на конечной не дольше 30 секунд: оборачиваем scheduledTerminalWait(Level,int,int)J
            final String desc = "(Lnet/minecraft/world/level/Level;II)J";
            MethodNode orig = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("scheduledTerminalWait") && m.desc.equals(desc)) {
                    orig = m;
                }
            }
            if (orig != null) {
                orig.name = "ptmScheduledTerminalWait";
                MethodNode w = new MethodNode(ACC_PRIVATE | ACC_STATIC, "scheduledTerminalWait", desc, null, null);
                w.instructions.add(new VarInsnNode(ALOAD, 0));
                w.instructions.add(new VarInsnNode(ILOAD, 1));
                w.instructions.add(new VarInsnNode(ILOAD, 2));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, TM, "ptmScheduledTerminalWait", desc, false));
                w.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(Long.valueOf(30000L)));
                w.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false));
                w.instructions.add(new InsnNode(LRETURN));
                w.maxStack = 6;
                w.maxLocals = 3;
                cn.methods.add(w);
                done.add("terminal-cap");
                changed = true;
            }
        }
        if (name.equals(TM)) {
            // Удаление транспорта после рейса: у линий с депо порог «доехал» расширяется (PtmFixes.parkReach)
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("updateVirtualTransport")) {
                    continue;
                }
                AbstractInsnNode[] all = m.instructions.toArray();
                for (int i = 1; i + 1 < all.length; i++) {
                    if (all[i] instanceof org.objectweb.asm.tree.LdcInsnNode && all[i + 1].getOpcode() == DCMPG
                        && all[i - 1] instanceof MethodInsnNode
                        && ((MethodInsnNode) all[i - 1]).name.equals("simpleDistance")) {
                        org.objectweb.asm.tree.LdcInsnNode ldc = (org.objectweb.asm.tree.LdcInsnNode) all[i];
                        if (ldc.cst instanceof Double && ((Double) ldc.cst).doubleValue() == 2.0D) {
                            InsnList repl = new InsnList();
                            repl.add(new VarInsnNode(ALOAD, 0));
                            repl.add(new VarInsnNode(ALOAD, 8));
                            repl.add(new VarInsnNode(ALOAD, 6));
                            repl.add(new MethodInsnNode(INVOKESTATIC, FIXES, "parkReach",
                                "(Lnet/minecraft/world/level/Level;Lcom/rinventor/ptm2/dimension/virtual/types/VDTrackableVehicle;Ljava/lang/String;)D", false));
                            m.instructions.insertBefore(ldc, repl);
                            m.instructions.remove(ldc);
                            done.add("depot-park");
                            changed = true;
                        }
                    }
                }
            }
            // Прибытие на остановку: допуск по зоне платформы и по остановке на месте 4+ секунды
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("updateVehicleDestination")) {
                    continue;
                }
                AbstractInsnNode[] all = m.instructions.toArray();
                int inside = 0;
                for (int i = 0; i < all.length; i++) {
                    AbstractInsnNode insn = all[i];
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.getOpcode() == INVOKEVIRTUAL && mi.name.equals("isInside") && mi.desc.equals("(DD)Z")
                            && mi.owner.equals("com/rinventor/ptm2/dimension/virtual/types/VDPlatform")) {
                            m.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, FIXES, "insideTol",
                                "(Lcom/rinventor/ptm2/dimension/virtual/types/VDPlatform;DD)Z", false));
                            inside++;
                        }
                    }
                    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode && i + 1 < all.length
                        && all[i + 1].getOpcode() == GOTO) {
                        org.objectweb.asm.tree.LdcInsnNode ldc = (org.objectweb.asm.tree.LdcInsnNode) insn;
                        if (ldc.cst instanceof Double && ((Double) ldc.cst).doubleValue() == 2.0D) {
                            InsnList repl = new InsnList();
                            repl.add(new VarInsnNode(ALOAD, 1));
                            repl.add(new VarInsnNode(ALOAD, 2));
                            repl.add(new VarInsnNode(DLOAD, 3));
                            repl.add(new MethodInsnNode(INVOKESTATIC, FIXES, "reachFor",
                                "(Lcom/rinventor/ptm2/dimension/virtual/types/VDTrackableVehicle;Lcom/rinventor/ptm2/objects/entities/vehicle/Vehicle;D)D", false));
                            m.instructions.insertBefore(insn, repl);
                            m.instructions.remove(insn);
                            done.add("arrival-reach");
                            changed = true;
                        }
                    }
                }
                if (inside >= 2) {
                    done.add("arrival-inside");
                    changed = true;
                }
            }
        }
        if (name.equals(TBM)) {
            // Лампы: поиск группы соседних блоков не ограничен, и на «пустой» записи (блока лампы уже нет)
            // он заливает весь воздух вокруг, сервер зависает на минуту (ServerHangWatchdog).
            // Заменяем HashSet.contains на проверку с потолком 2048 блоков.
            for (MethodNode m : cn.methods) {
                if (!m.name.startsWith("lambda$switchLampAt")) {
                    continue;
                }
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.getOpcode() == INVOKEVIRTUAL && mi.owner.equals("java/util/HashSet")
                            && mi.name.equals("contains") && mi.desc.equals("(Ljava/lang/Object;)Z")) {
                            m.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, FIXES, "visitedOrFull",
                                "(Ljava/util/HashSet;Ljava/lang/Object;)Z", false));
                            done.add("lamp-cap");
                            changed = true;
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
        for (MethodNode mm : cn.methods) {
            for (AbstractInsnNode in : mm.instructions.toArray()) {
                if (in instanceof MethodInsnNode && ((MethodInsnNode) in).owner.equals(FIXES)) {
                    refs.add(((MethodInsnNode) in).name + ((MethodInsnNode) in).desc);
                }
            }
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
