package com.rinventor.ptm2.fixes;

import com.rinventor.ptm2.PTM;
import com.rinventor.ptm2.core.data.WorldSavedData;
import com.rinventor.ptm2.core.properties.VDMemoryIDs;
import com.rinventor.ptm2.core.properties.VehicleTypes;
import com.rinventor.ptm2.dimension.virtual.types.VDRoute;
import com.rinventor.ptm2.dimension.virtual.types.VDTrackableVehicle;
import com.rinventor.ptm2.dimension.virtual.types.VDTransportLine;
import com.rinventor.ptm2.dimension.virtual.util.ScheduleUtils;
import com.rinventor.ptm2.dimension.virtual.util.VDErrors;
import com.rinventor.ptm2.engine.data.OperationalSeverity;
import com.rinventor.ptm2.dimension.virtual.types.VDOperationalData;
import com.rinventor.ptm2.engine.util.TimeHelper;
import com.rinventor.ptm2.dimension.physical.TransportModelData;
import com.rinventor.ptm2.engine.bezier.utility.Pair;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import com.rinventor.ptm2.dimension.virtual.storage.VDStorageHandler;
import com.rinventor.ptm2.dimension.virtual.types.VDPlatform;
import com.rinventor.ptm2.dimension.virtual.types.VDStation;
import com.rinventor.ptm2.dimension.virtual.util.PaymentUtils;
import com.rinventor.ptm2.dimension.virtual.util.VDUtils;
import com.rinventor.ptm2.engine.base.PTMChat;
import com.rinventor.ptm2.engine.base.PTMEntity;
import com.rinventor.ptm2.engine.base.PTMLogger;
import com.rinventor.ptm2.engine.base.PTMScreen;
import com.rinventor.ptm2.engine.base.PTMWorld;
import com.rinventor.ptm2.engine.util.Translation;
import com.rinventor.ptm2.objects.blockentities.atm.ATMMenu;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.player.Player;
import com.rinventor.ptm2.objects.entities.vehicle.Vehicle;
import com.rinventor.ptm2.objects.items.TransportCard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Исправления для PTM2, подключаются к моду патчером (Patcher.java).
 * Здесь нет вызовов методов Minecraft, только API самого мода.
 */
public final class PtmFixes {
    private static final int STATION_NAME = 5;
    private static final int PLATFORM_STATION_ID = 6;

    private PtmFixes() {
    }

    /**
     * Вызывается каждые несколько секунд на серверном потоке (из OperationalDataUpdater.run).
     * 1) даёт название станциям, у которых его нет;
     * 2) привязывает каждую платформу к станции, в зоне которой она находится.
     */
    public static final String BUILD = "fixes-2026-10-13-lamp-cap";
    private static boolean announced = false;

    public static void tick(Level level) {
        try {
            if (level == null || PTMWorld.isClient(level)) {
                return;
            }
            if (!announced) {
                announced = true;
                PTMLogger.info("PTM2 fixes loaded: " + BUILD);
            }
            linkStationsAndPlatforms(level);
        } catch (Exception e) {
            PTMLogger.error(e);
        }
    }

    private static void linkStationsAndPlatforms(Level level) {
        Map<Integer, VDStation> stations = new HashMap<>();
        for (Map.Entry<Integer, String> entry : VDStorageHandler.getAll(level, VDMemoryIDs.STATION).entrySet()) {
            try {
                VDStation station = new VDStation(entry.getValue());
                stations.put(entry.getKey(), station);
                if (station.name == null || station.name.trim().isEmpty()) {
                    String fallback = "\u0421\u0442\u0430\u043d\u0446\u0438\u044f " + entry.getKey();
                    station.name = fallback;
                    VDStorageHandler.updateVal(level, VDMemoryIDs.STATION, entry.getKey(), STATION_NAME, fallback);
                }
            } catch (Exception e) {
                PTMLogger.error(e);
            }
        }
        if (stations.isEmpty()) {
            return;
        }

        for (Map.Entry<Integer, String> entry : VDStorageHandler.getAll(level, VDMemoryIDs.PLATFORM).entrySet()) {
            try {
                VDPlatform platform = new VDPlatform(entry.getValue());
                int cx = platform.getCenterX();
                int cz = platform.getCenterZ();
                int bestId = 0;
                long bestArea = Long.MAX_VALUE;
                for (Map.Entry<Integer, VDStation> st : stations.entrySet()) {
                    VDStation station = st.getValue();
                    if (station.isInside(cx, cz)) {
                        long area = Math.abs((long) (station.fbX - station.sbX)) * Math.abs((long) (station.fbZ - station.sbZ));
                        if (area < bestArea) {
                            bestArea = area;
                            bestId = st.getKey();
                        }
                    }
                }
                if (bestId != 0) {
                    if (platform.stationID != bestId) {
                        VDStorageHandler.updateVal(level, VDMemoryIDs.PLATFORM, entry.getKey(), PLATFORM_STATION_ID, bestId);
                    }
                } else if (platform.stationID != 0 && !stations.containsKey(platform.stationID)) {
                    VDStorageHandler.updateVal(level, VDMemoryIDs.PLATFORM, entry.getKey(), PLATFORM_STATION_ID, 0);
                }
            } catch (Exception e) {
                PTMLogger.error(e);
            }
        }
    }


    /**
     * Вклад в банкомат. Вызывается из ATMButtonServerboundPacket.handle(Player, double) вместо
     * исходной логики (исходная после этого получает сумму 0 и ничего не делает).
     * Работает только на сервере: сумма считается по предметам в слотах, результат пишется в чат.
     */
    public static void atmDeposit(Player player, Level level) {
        try {
            if (player == null || level == null || PTMWorld.isClient(player)) {
                return;
            }
            boolean hasInput = !PTMScreen.isSlotEmpty(0, player) || !PTMScreen.isSlotEmpty(1, player);
            double amount = ATMMenu.getTotatlValue(player);
            double x = PTMEntity.getX(player);
            double y = PTMEntity.getY(player);
            double z = PTMEntity.getZ(player);
            String ok = "green";
            String bad = "red";
            if (!hasInput) {
                PTMChat.msgNearest("ATM: \u0441\u043b\u043e\u0442\u044b \u043f\u0443\u0441\u0442\u044b", bad, level, x, y, z);
                return;
            }
            if (!(amount > 0.0D)) {
                PTMChat.msgNearest("ATM: \u0443 \u043f\u0440\u0435\u0434\u043c\u0435\u0442\u043e\u0432 \u043d\u0435\u0442 \u0446\u0435\u043d\u044b (\u0441\u0443\u043c\u043c\u0430 0)", bad, level, x, y, z);
                return;
            }
            var before = VDUtils.getWorker(player);
            if (before == null) {
                PTMChat.msgNearest("ATM: \u0441\u0447\u0451\u0442 \u0438\u0433\u0440\u043e\u043a\u0430 \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d", bad, level, x, y, z);
                return;
            }
            double oldMoney = before.getSecond().money;
            PaymentUtils.add(player, amount, Translation.get("ptm2.banking.description.atm_deposit"));
            PTMScreen.setSlotEmpty(0, player);
            PTMScreen.setSlotEmpty(1, player);
            var after = VDUtils.getWorker(player);
            double newMoney = after == null ? -1.0D : after.getSecond().money;
            PTMChat.msgNearest("ATM: +" + amount + " | \u0431\u0430\u043b\u0430\u043d\u0441 " + oldMoney + " -> " + newMoney,
                newMoney > oldMoney ? ok : bad, level, x, y, z);
        } catch (Exception e) {
            PTMLogger.error(e);
        }
    }



    private static final ArrayDeque<String> REMOVALS = new ArrayDeque<>();

    private static void remember(String line) {
        synchronized (REMOVALS) {
            REMOVALS.addFirst(line);
            while (REMOVALS.size() > 8) {
                REMOVALS.removeLast();
            }
        }
    }

    private static String caller() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int found = 0;
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if (c.endsWith("PtmFixes") || c.endsWith("VDStorageHandler") || c.endsWith("PTMEntity")) {
                continue;
            }
            String simple = c.substring(c.lastIndexOf('.') + 1);
            if (found > 0) {
                sb.append(" < ");
            }
            sb.append(simple).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (++found >= 2) {
                break;
            }
        }
        return sb.toString();
    }

    /** Вызывается перед VDStorageHandler.remove(Level, String, VDMemoryIDs): запоминаем, кто удалил автобус. */
    public static void onRemove(Level level, String id, VDMemoryIDs mapID) {
        try {
            if (level == null || mapID != VDMemoryIDs.TRACKABLE_VEHICLE || PTMWorld.isClient(level)) {
                return;
            }
            String data = VDStorageHandler.get(level, id, VDMemoryIDs.TRACKABLE_VEHICLE);
            if (data == null || data.isEmpty()) {
                return; // служебная очистка записей секций: пропускаем
            }
            String info;
            {
                VDTrackableVehicle v = new VDTrackableVehicle(data);
                info = " \u043b\u0438\u043d\u0438\u044f=" + v.lineID + " \u0440\u0435\u0439\u0441=" + v.routeID
                    + " \u043f\u043b\u0430\u0442\u0444\u043e\u0440\u043c\u0430=" + v.nextPlatformID
                    + (v.virtual ? " \u0432\u0438\u0440\u0442." : " \u0444\u0438\u0437.");
            }
            remember(String.format("%02d:%02d:%02d", TimeHelper.hour(level), TimeHelper.minute(level),
                (int) ((System.currentTimeMillis() / 1000L) % 60L)) + " " + caller() + info);
        } catch (Exception e) {
            // диагностика не должна ломать игру
        }
    }

    /** Вызывается в начале PTMEntity.syncedDespawn(String, Entity). */
    public static void onDespawn(String reason, Entity entity) {
        try {
            if (entity == null || reason == null || reason.isEmpty()) {
                return;
            }
            if (PTMWorld.isClient(entity)) {
                return;
            }
            String cls = entity.getClass().getSimpleName();
            if (cls.contains("Transport") || cls.contains("Bus") || cls.contains("Tram") || cls.contains("Driver")
                || entity.getClass().getName().contains("vehicle")) {
                remember("\u0441\u0443\u0449\u043d\u043e\u0441\u0442\u044c " + cls + ": " + reason + " < " + caller());
            }
        } catch (Exception e) {
            // диагностика не должна ломать игру
        }
    }

    private static void removalLines(Level level, int companyID, List<VDOperationalData> entries) {
        List<String> copy;
        synchronized (REMOVALS) {
            copy = new ArrayList<>(REMOVALS);
        }
        if (copy.isEmpty()) {
            entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO,
                "\u0414\u0418\u0410\u0413: \u0443\u0434\u0430\u043b\u0435\u043d\u0438\u0439 \u0430\u0432\u0442\u043e\u0431\u0443\u0441\u043e\u0432 \u043d\u0435 \u0431\u044b\u043b\u043e"));
            return;
        }
        for (String c : copy) {
            entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO, "\u0414\u0418\u0410\u0413: \u0443\u0434\u0430\u043b\u0435\u043d " + c));
        }
    }

    private static void workerLines(Level level, int companyID, List<VDOperationalData> entries) {
        StringBuilder sb = new StringBuilder("\u0414\u0418\u0410\u0413: \u0441\u0447\u0435\u0442\u0430 \u0438\u0433\u0440\u043e\u043a\u043e\u0432 (\u0441\u0435\u0440\u0432\u0435\u0440): ");
        int n = 0;
        for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.WORKER).entrySet()) {
            if (n++ >= 6) {
                break;
            }
            com.rinventor.ptm2.dimension.virtual.types.VDWorker w = new com.rinventor.ptm2.dimension.virtual.types.VDWorker(e.getValue());
            sb.append('#').append(e.getKey()).append(' ').append(w.name).append('=').append(w.money).append("; ");
        }
        entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO, sb.toString()));
    }


    private static final ConcurrentHashMap<String, Long> PAID = new ConcurrentHashMap<>();
    private static final long TRANSFER_WINDOW_MS = 5L * 60L * 1000L;

    private static int readLineId(Object transport) {
        try {
            java.lang.reflect.Field f = transport.getClass().getDeclaredField("lineID");
            f.setAccessible(true);
            return f.getInt(transport);
        } catch (Exception e) {
            try {
                java.lang.reflect.Field f = transport.getClass().getSuperclass().getDeclaredField("lineID");
                f.setAccessible(true);
                return f.getInt(transport);
            } catch (Exception e2) {
                return -1;
            }
        }
    }

    /**
     * Оплата проезда при посадке игрока в автобус на линии. Вызывается из обёртки Vehicle.startRiding.
     * Порядок: место водителя и не игроки пропускаются; линия бесплатная или не задана - можно;
     * в течение 5 минут после оплаты пересадка бесплатна; иначе поездка с транспортной карты;
     * иначе цена билета со счёта игрока на счёт компании линии; иначе посадка запрещена.
     */
    public static boolean allowBoard(Vehicle vehicle, Entity entity, int seat, Level level) {
        try {
            if (!(entity instanceof Player) || seat == 0 || level == null || PTMWorld.isClient(level)) {
                return true;
            }
            Vehicle main = vehicle.getMainVehicle(vehicle);
            if (main == null) {
                main = vehicle;
            }
            int lineID = readLineId(main);
            if (lineID == -1) {
                return true;
            }
            String lineRaw = VDStorageHandler.get(level, lineID, VDMemoryIDs.TRANSPORT_LINE);
            if (lineRaw == null || lineRaw.isEmpty()) {
                return true;
            }
            VDTransportLine line = new VDTransportLine(lineRaw);
            if (line.free) {
                return true;
            }
            Player player = (Player) entity;
            String key = PTMEntity.getName(player) + "@" + System.identityHashCode(main);
            long now = System.currentTimeMillis();
            Long last = PAID.get(key);
            if (last != null && now - last < TRANSFER_WINDOW_MS) {
                return true;
            }
            double x = PTMEntity.getX(player);
            double y = PTMEntity.getY(player);
            double z = PTMEntity.getZ(player);
            if (TransportCard.useCard(player, 1)) {
                PAID.put(key, now);
                PTMChat.msgNearest("\u041f\u0440\u043e\u0435\u0437\u0434 \u043e\u043f\u043b\u0430\u0447\u0435\u043d \u0442\u0440\u0430\u043d\u0441\u043f\u043e\u0440\u0442\u043d\u043e\u0439 \u043a\u0430\u0440\u0442\u043e\u0439", "green", level, x, y, z);
                return true;
            }
            double price = ((WorldSavedData) PTM.DATA.get(level)).PRC_TICKET;
            String companyRaw = VDStorageHandler.get(level, line.companyID, VDMemoryIDs.TRANSPORT_COMPANY);
            if (companyRaw == null || companyRaw.isEmpty()) {
                return true;
            }
            String companyName = new com.rinventor.ptm2.dimension.virtual.types.VDTransportCompany(companyRaw).name;
            if (PaymentUtils.getBalance(player) >= price) {
                PaymentUtils.transactionP2C(player, companyName, price, "\u041f\u0440\u043e\u0435\u0437\u0434");
                PAID.put(key, now);
                PTMChat.msgNearest("\u0421\u043f\u0438\u0441\u0430\u043d\u043e \u0437\u0430 \u043f\u0440\u043e\u0435\u0437\u0434: " + price, "green", level, x, y, z);
                return true;
            }
            PTMChat.msgNearest("\u041f\u0440\u043e\u0435\u0437\u0434 \u043d\u0435 \u043e\u043f\u043b\u0430\u0447\u0435\u043d: \u043d\u0435\u0442 \u043a\u0430\u0440\u0442\u044b \u0438 \u043d\u0435\u0434\u043e\u0441\u0442\u0430\u0442\u043e\u0447\u043d\u043e \u0434\u0435\u043d\u0435\u0433 (" + price + ")", "red", level, x, y, z);
            return false;
        } catch (Throwable t) {
            PTMLogger.error(String.valueOf(t));
            return true;
        }
    }


    private static final ConcurrentHashMap<Integer, Long> SLOW = new ConcurrentHashMap<>();

    /**
     * Допуск при проверке «автобус внутри зоны платформы»: считаем внутри и точку в пределах 4 блоков от зоны.
     * Вызывается вместо VDPlatform.isInside внутри TransportManager.updateVehicleDestination.
     */
    public static boolean insideTol(VDPlatform p, double x, double z) {
        try {
            if (p.isInside(x, z)) {
                return true;
            }
            double minX = Math.min(p.sbX, p.fbX);
            double maxX = Math.max(p.sbX, p.fbX) + 1.0D;
            double minZ = Math.min(p.sbZ, p.fbZ);
            double maxZ = Math.max(p.sbZ, p.fbZ) + 1.0D;
            double dx = Math.max(Math.max(minX - x, 0.0D), x - maxX);
            double dz = Math.max(Math.max(minZ - z, 0.0D), z - maxZ);
            return dx * dx + dz * dz <= 16.0D;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Радиус «прибытия» к точке остановки вместо константы 2.0: если автобус стоит (скорость около нуля)
     * дольше 4 секунд в пределах 14 блоков от точки остановки, считаем, что он прибыл (открываем двери, ждём, едем дальше).
     */
    public static double reachFor(VDTrackableVehicle v, Vehicle ve, double distance) {
        try {
            if (ve == null) {
                return 2.0D;
            }
            int key = System.identityHashCode(ve);
            long now = System.currentTimeMillis();
            if (distance < 14.0D && Math.abs(ve.speed) < 0.5F) {
                Long first = SLOW.get(key);
                if (first == null) {
                    if (SLOW.size() > 500) {
                        SLOW.clear();
                    }
                    SLOW.put(key, now);
                } else if (now - first >= 4000L) {
                    return 14.0D;
                }
            } else {
                SLOW.remove(key);
            }
        } catch (Throwable t) {
            // не ломаем игру
        }
        return 2.0D;
    }

    /**
     * Замена HashSet.contains в поиске группы ламп (TickableBlocksManager.switchLampAt):
     * когда набор достиг 2048 блоков, считаем все остальные уже посещёнными, и обход заканчивается.
     */
    public static boolean visitedOrFull(java.util.HashSet<?> set, Object o) {
        return set.size() >= 2048 || set.contains(o);
    }

    private static String yn(boolean v) {
        return v ? "\u0434\u0430" : "\u041d\u0415\u0422";
    }

    /**
     * Диагностика для экрана «Операционная информация»: добавляет информационные строки с
     * состоянием цепочки линия -> рейс -> расписание -> автобус. Вызывается из
     * OperationalDataUpdater.collectEntries перед возвратом списка.
     */
    public static void diag(Level level, int companyID, List<VDOperationalData> entries) {
        try {
            if (level == null || entries == null) {
                return;
            }
            WorldSavedData data = (WorldSavedData) PTM.DATA.get(level);
            int hour = TimeHelper.hour(level);
            int minute = TimeHelper.minute(level);
            int nowMin = hour * 60 + minute;
            int day = Math.floorMod(TimeHelper.weekday(level) - 1, 7);
            entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO,
                "\u0414\u0418\u0410\u0413: \u0440\u0435\u0430\u043b\u044c\u043d\u044b\u0439 \u0446\u0438\u043a\u043b=" + yn(data.REAL_DAYLIGHT)
                + ", \u0441\u043f\u0430\u0432\u043d \u0442\u0440\u0430\u043d\u0441\u043f\u043e\u0440\u0442\u0430=" + yn(data.SPAWN_TRANSPORT)
                + ", \u0432\u0440\u0435\u043c\u044f \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d\u043e=" + yn(TimeHelper.isTimeStopped(level))
                + ", \u0432\u0440\u0435\u043c\u044f " + String.format("%02d:%02d", hour, minute) + ", \u0434\u0435\u043d\u044c " + day));

            Map<Integer, VDTransportLine> lines = VDUtils.loadLinesForCompany(level, companyID);
            Map<Integer, String> routes = VDStorageHandler.getAll(level, VDMemoryIDs.ROUTE);
            Map<String, String> stored = VDStorageHandler.getStrs(level, VDMemoryIDs.TRACKABLE_VEHICLE);
            List<VDTrackableVehicle> vehicles = new ArrayList<>();
            for (String v : stored.values()) {
                try {
                    vehicles.add(new VDTrackableVehicle(v));
                } catch (Exception ignored) {
                    // пропускаем повреждённые записи
                }
            }

            for (Map.Entry<Integer, VDTransportLine> le : lines.entrySet()) {
                int lineId = le.getKey();
                VDTransportLine line = le.getValue();
                String number = line.number == null || line.number.isEmpty() || line.number.equals("null") ? "?" : line.number;
                int routeCount = 0;
                for (String r : routes.values()) {
                    if (r != null && !r.isEmpty() && new VDRoute(r).lineID == lineId) {
                        routeCount++;
                    }
                }
                List<Pair<Integer, Pair<Integer, String>>> sched = ScheduleUtils.getSimpleScheduleEntriesFor(level, lineId, day);
                int entriesToday = sched == null ? 0 : sched.size();
                int next = -1;
                if (sched != null) {
                    for (Pair<Integer, Pair<Integer, String>> e : sched) {
                        int m = TimeHelper.parseTime(e.getSecond().getSecond());
                        if (m >= nowMin && (next == -1 || m < next)) {
                            next = m;
                        }
                    }
                }
                int models = TransportModelData.models(VehicleTypes.fromID(line.type)).size();
                int active = 0;
                int noAi = 0;
                StringBuilder noAiInfo = new StringBuilder();
                for (VDTrackableVehicle v : vehicles) {
                    if (v.lineID == lineId && v.isTransport()) {
                        active++;
                        if (!v.ai && !v.virtual) {
                            noAi++;
                            if (noAi <= 3) {
                                noAiInfo.append(" [\u043f\u043b\u0430\u0442\u0444\u043e\u0440\u043c\u0430 ").append(v.nextPlatformID)
                                    .append(" \u0440\u0443\u0447\u043d\u0438\u043a=").append(v.handBrake ? "\u0434\u0430" : "\u043d\u0435\u0442").append("]");
                            }
                        }
                    }
                }
                entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO,
                    "\u0414\u0418\u0410\u0413: \u043b\u0438\u043d\u0438\u044f " + number
                    + ": \u0440\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u0435=" + (line.scheduleType == 0 ? "\u0443\u043f\u0440\u043e\u0449." : "\u0440\u0435\u0430\u043b.")
                    + (line.suspended ? ", \u041f\u0420\u0418\u041e\u0421\u0422\u0410\u041d\u041e\u0412\u041b\u0415\u041d\u0410" : "")
                    + ", \u0440\u0435\u0439\u0441\u043e\u0432=" + routeCount
                    + ", \u043e\u0442\u043f\u0440\u0430\u0432\u043b\u0435\u043d\u0438\u0439 \u0441\u0435\u0433\u043e\u0434\u043d\u044f=" + entriesToday
                    + (next >= 0 ? ", \u0431\u043b\u0438\u0436\u0430\u0439\u0448\u0435\u0435 " + String.format("%02d:%02d", next / 60, next % 60) : ", \u0431\u043b\u0438\u0436\u0430\u0439\u0448\u0438\u0445 \u043d\u0435\u0442")
                    + ", \u043c\u043e\u0434\u0435\u043b\u0435\u0439 \u0442\u0440\u0430\u043d\u0441\u043f\u043e\u0440\u0442\u0430=" + models
                    + ", \u043c\u0430\u0448\u0438\u043d \u0432 \u0438\u0433\u0440\u0435=" + active
                    + (noAi > 0 ? ", \u0411\u0415\u0417 \u0418\u0418 (\u0441\u0442\u043e\u044f\u0442)=" + noAi + noAiInfo : "")
                    + ", \u0434\u0435\u043f\u043e=" + (line.depotID == -1 ? "\u043d\u0435\u0442" : String.valueOf(line.depotID))));

                int shown = 0;
                for (Map.Entry<Integer, String> re : routes.entrySet()) {
                    if (shown >= 6) {
                        break;
                    }
                    String raw = re.getValue();
                    if (raw == null || raw.isEmpty()) {
                        continue;
                    }
                    VDRoute route = new VDRoute(raw);
                    if (route.lineID != lineId) {
                        continue;
                    }
                    shown++;
                    List<Integer> stops = VDUtils.parseStops(route.stops);
                    int closed = 0;
                    int missing = 0;
                    for (int platformId : stops) {
                        VDPlatform platform = VDUtils.getPlatform(level, platformId);
                        if (platform == null) {
                            missing++;
                        } else if (platform.closed) {
                            closed++;
                        }
                    }
                    List<String> noPath = VDErrors.hasPathBetweenStops(level, re.getKey(), route);
                    entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO,
                        "\u0414\u0418\u0410\u0413: \u0440\u0435\u0439\u0441 " + route.letter + " \u043b\u0438\u043d\u0438\u0438 " + number
                        + ": \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043e\u043a=" + stops.size()
                        + ", \u043d\u0435\u0442 \u043f\u043b\u0430\u0442\u0444\u043e\u0440\u043c\u044b=" + missing
                        + ", \u0437\u0430\u043a\u0440\u044b\u0442\u043e=" + closed
                        + ", \u043f\u0443\u0442\u044c \u043c\u0435\u0436\u0434\u0443 \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043a\u0430\u043c\u0438="
                        + (noPath == null || noPath.isEmpty() ? "\u0435\u0441\u0442\u044c" : "\u041d\u0415\u0422 (" + noPath.size() + ")")));
                }
            }
            removalLines(level, companyID, entries);
            workerLines(level, companyID, entries);
        } catch (Exception e) {
            PTMLogger.error(e);
        }
    }

    /**
     * Защита финансов. Клиент не должен сам менять деньги игрока и баланс компании через
     * универсальный пакет ValueUpdateServerboundPacket: возвращает неизвестный номер свойства,
     * и сервер такую запись игнорирует. Серверные операции (банкомат, автоматы, переводы)
     * вызывают handle(Level) напрямую и этим не затрагиваются.
     */
    public static int safeProp(int type, int prop) {
        if (type == VDMemoryIDs.WORKER.getID() && prop == 2) {
            return -1;
        }
        if (type == VDMemoryIDs.TRANSPORT_COMPANY.getID() && prop == 5) {
            return -1;
        }
        return prop;
    }
}
