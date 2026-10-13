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
import com.rinventor.ptm2.core.properties.RoadMarkingTypes;
import com.rinventor.ptm2.core.properties.TrafficLightColors;
import com.rinventor.ptm2.dimension.virtual.types.VDIntersection;
import com.rinventor.ptm2.dimension.virtual.types.VDRoadMarking;
import com.rinventor.ptm2.dimension.virtual.types.VDTrafficLane;
import net.minecraft.core.BlockPos;
import com.rinventor.ptm2.dimension.physical.VehicleUtils;
import com.rinventor.ptm2.objects.entities.pedestrian.Pedestrian;
import com.rinventor.ptm2.objects.entities.vehicle.Transport;
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

    // ======================================================================
    //  ИИ движения: красный после поворота, «терпение» при взаимной блокировке,
    //  объезд долго стоящих машин, диагностика застрявших.
    // ======================================================================

    /** Состояние ИИ одной машины (ключ: identityHashCode объекта Vehicle). */
    static final class Drv {
        volatile long seen;
        long stillSince;
        long blockedSince;
        long forcedAt;
        boolean forced;
        boolean candidate;
        boolean committed;
        double zoneEntry = Double.NaN;
        int failed;
        long lastLog;
        long firstBlocked;
        boolean backing;
        double backLeft;
        long holdUntil;
        long exitCheck;
        boolean exitHold;
        long exitHoldSince;
    }

    static final Map<Integer, Drv> DRV = new ConcurrentHashMap<>();
    static long lastPrune = 0L;
    static long logWindowStart = 0L;
    static int logCount = 0;
    /** Сколько миллисекунд застрявшая без видимой причины машина ждёт, прежде чем ей разрешат ехать. */
    static final long PATIENCE_MS = 8000L;
    /** Долго стоящая попутная машина считается препятствием для объезда. */
    static final long OBSTACLE_STILL_MS = 12000L;

    static Drv drv(Vehicle v) {
        return DRV.computeIfAbsent(System.identityHashCode(v), k -> new Drv());
    }

    private static void prune(long now) {
        if (now - lastPrune < 60000L) {
            return;
        }
        lastPrune = now;
        DRV.entrySet().removeIf(e -> now - e.getValue().seen > 60000L);
    }

    private static void drvLog(Drv d, long now, String text) {
        if (now - d.lastLog < 15000L) {
            return;
        }
        if (now - logWindowStart > 60000L) {
            logWindowStart = now;
            logCount = 0;
        }
        if (logCount >= 25) {
            return;
        }
        logCount++;
        d.lastLog = now;
        PTMLogger.info("PTM2 ИИ: " + text);
    }

    /**
     * Замена VehicleUtils.forbiddingTrafficLightAhead в Vehicle.canMove.
     * Машина, которая уже въехала на перекрёсток на зелёный, больше не смотрит на светофоры:
     * раньше при повороте она выбирала светофор нового направления (красный для поперечной дороги)
     * и ждала второй раз прямо посреди перекрёстка.
     */
    public static boolean trafficRed(Vehicle v) {
        boolean red = VehicleUtils.forbiddingTrafficLightAhead(v);
        try {
            if (PTMWorld.isClient(v)) {
                return red;
            }
            Drv d = drv(v);
            boolean inside = VDUtils.inZone(v, VDMemoryIDs.INTERSECTION);
            if (!inside) {
                d.committed = false;
                d.zoneEntry = Double.NaN;
                return red;
            }
            if (d.committed) {
                return false;
            }
            if (Double.isNaN(d.zoneEntry)) {
                d.zoneEntry = v.pathProgress;
            }
            // «Прошла стоп-линию» = проехала не менее 1.5 блока внутри зоны перекрёстка при зелёном
            if (!red && Math.abs(v.speed) >= 1.0F && v.pathProgress - d.zoneEntry >= 1.5D) {
                d.committed = true;
                return false;
            }
        } catch (Exception e) {
            PTMLogger.error(e);
        }
        return red;
    }

    private static double wrap180(double a) {
        a = (a + 540.0D) % 360.0D;
        return a - 180.0D;
    }

    /** Есть ли на пути пешеход/игрок (с запасом по ширине). Тогда ехать нельзя ни при каких условиях. */
    private static boolean livingAhead(Vehicle v, Level level) {
        double yaw = Math.toRadians(PTMEntity.getYaw(v));
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double x = PTMEntity.getX(v);
        double y = PTMEntity.getY(v);
        double z = PTMEntity.getZ(v);
        double half = Math.max(0.0F, v.length) / 2.0D;
        for (double dist = 1.5D; dist <= 9.0D; dist += 1.5D) {
            double cx = x + fx * (half + dist);
            double cz = z + fz * (half + dist);
            if (PTMEntity.exists(Pedestrian.class, 1.4D, level, cx, y, cz)
                || PTMEntity.exists(Player.class, 1.4D, level, cx, y, cz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Обёртка Vehicle.canMove(). orig - решение оригинального ИИ.
     * 1) Если машина стоит без видимой причины (не красный, не пешеход, не очередь в своей полосе),
     *    после PATIENCE_MS ей разрешается ехать; физический запрет столкновения (clearanceBlockerAhead) остаётся.
     *    При нескольких претендентах едет тот, кто ждёт дольше.
     * 2) Если проезд разрешён, но машины физически упёрлись друг в друга, та, что ждала меньше, отъезжает назад.
     */
    public static boolean canMove(Vehicle v, boolean orig, Level level) {
        try {
            if (PTMWorld.isClient(v)) {
                return orig;
            }
            long now = System.currentTimeMillis();
            prune(now);
            Drv d = drv(v);
            d.seen = now;
            boolean still = Math.abs(v.speed) < 0.3F;
            if (!still) {
                d.stillSince = 0L;
            } else if (d.stillSince == 0L) {
                d.stillSince = now;
            }
            if (!v.ai || v.handBrake || v.crashed || v.requiresTracks) {
                d.blockedSince = 0L;
                d.firstBlocked = 0L;
                d.forced = false;
                d.backing = false;
                d.candidate = false;
                return orig;
            }
            // Отъезд назад (уступаем более «старому» ожидающему)
            if (d.backing) {
                if (d.backLeft <= 0.0D || !still || !spaceBehind(v, level) || !stepBack(v, 0.15D)) {
                    d.backing = false;
                    d.holdUntil = now + 6000L;
                    d.blockedSince = now;
                    return false;
                }
                d.backLeft -= 0.15D;
                return false;
            }
            if (now < d.holdUntil) {
                return false;
            }
            if (orig) {
                d.blockedSince = 0L;
                d.firstBlocked = 0L;
                d.forced = false;
                d.candidate = false;
                d.failed = 0;
                // Не въезжаем на перекрёсток, если за ним нет места (но не дольше 12 с)
                if (now - d.exitCheck > 300L) {
                    d.exitCheck = now;
                    d.exitHold = exitBlocked(v, level);
                }
                if (d.exitHold) {
                    if (d.exitHoldSince == 0L) {
                        d.exitHoldSince = now;
                        drvLog(d, now, "ждёт места за перекрёстком " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v));
                    }
                    if (now - d.exitHoldSince < 12000L) {
                        return false;
                    }
                } else {
                    d.exitHoldSince = 0L;
                }
                return true;
            }
            if (d.forced) {
                // Разрешение действует, только пока нет красного, шлагбаума или пешехода
                String hr = hardReason(v, level);
                if (hr != null) {
                    d.forced = false;
                    d.candidate = false;
                    d.blockedSince = now;
                    return false;
                }
                if (!pathFree(v, 9.0D, false, 1.5D)) {
                    // Чужой кузов на пути: ждём (проигравший отъедет)
                    if (still && d.stillSince > 0L && now - d.stillSince > 3000L && now - d.forcedAt > 3000L) {
                        d.forced = false;
                        d.failed++;
                        d.candidate = false;
                        if (shouldYield(v, d, level)) {
                            d.backing = true;
                            d.backLeft = 7.0D;
                            drvLog(d, now, "отъезжает назад, уступая " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v));
                        } else {
                            d.blockedSince = now - PATIENCE_MS;
                        }
                    }
                    return false;
                }
                if (still && d.stillSince > 0L && now - d.stillSince > 3000L && now - d.forcedAt > 3000L) {
                    // Физически не пускают: либо уступаем и отъезжаем, либо повторяем попытку
                    d.forced = false;
                    d.failed++;
                    d.candidate = false;
                    if (shouldYield(v, d, level)) {
                        d.backing = true;
                        d.backLeft = 7.0D;
                        drvLog(d, now, "отъезжает назад, уступая " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v));
                    } else {
                        d.blockedSince = now - PATIENCE_MS;
                    }
                    return false;
                }
                return true;
            }
            if (!still) {
                return false;
            }
            if (d.blockedSince == 0L) {
                d.blockedSince = now;
            }
            if (d.firstBlocked == 0L) {
                d.firstBlocked = now;
            }
            long waited = now - d.blockedSince;
            if (waited < PATIENCE_MS) {
                return false;
            }
            // Автомобиль без пассажирского рейса, безнадёжно застрявший: убираем, если рядом нет игроков
            if (!(v instanceof Transport) && d.failed >= 3 && d.stillSince > 0L && now - d.stillSince > 120000L) {
                drvLog(d, now, "убрана застрявшая машина " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v));
                VehicleUtils.despawn(v, 48);
                return false;
            }
            String reason = hardReason(v, level);
            if (reason != null) {
                d.candidate = false;
                d.firstBlocked = 0L;
                return false;
            }
            d.candidate = true;
            // Приоритет: едет тот, кто ждёт дольше; при равенстве - по номеру
            for (Vehicle o : PTMEntity.getDifferentVehicles(30.0D, v)) {
                if (o == v) {
                    continue;
                }
                Drv od = DRV.get(System.identityHashCode(o));
                if (od != null && od.candidate && od.blockedSince > 0L && now - od.seen < 2000L && !od.forced
                    && (od.blockedSince < d.blockedSince
                        || (od.blockedSince == d.blockedSince && System.identityHashCode(o) < System.identityHashCode(v)))) {
                    drvLog(d, now, "ждёт очереди " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v) + " ждёт " + (waited / 1000L) + " с");
                    return false;
                }
            }
            if (!pathFree(v, 9.0D, false, 1.5D)) {
                // Путь занят чужим кузовом: не лезем; уступит младший
                if (shouldYield(v, d, level)) {
                    d.failed++;
                    d.backing = true;
                    d.backLeft = 7.0D;
                    drvLog(d, now, "отъезжает назад, уступая " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v));
                }
                return false;
            }
            d.forced = true;
            d.forcedAt = now;
            drvLog(d, now, "разрешён проезд " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v) + " ждал " + (waited / 1000L) + " с, попытка " + (d.failed + 1)
                + (v instanceof Transport ? " (транспорт)" : ""));
            return true;
        } catch (Exception e) {
            PTMLogger.error(e);
            return orig;
        }
    }

    // ---------------------------------------------------------------- геометрия пути и перекрёстков

    /** Кэш зон перекрёстков: [minX, minZ, maxX, maxZ]. */
    static final class Zones {
        long t;
        int[][] boxes = new int[0][];
    }

    static final Map<Integer, Zones> ZONES = new ConcurrentHashMap<>();

    static boolean inIntersectionAt(Level level, double x, double z) {
        long now = System.currentTimeMillis();
        int key = System.identityHashCode(level);
        Zones zc = ZONES.get(key);
        if (zc == null || now - zc.t > 5000L) {
            Zones nz = new Zones();
            nz.t = now;
            List<int[]> list = new java.util.ArrayList<>();
            for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.INTERSECTION).entrySet()) {
                VDIntersection zi = new VDIntersection(e.getValue());
                list.add(new int[] {Math.min(zi.sbX, zi.fbX), Math.min(zi.sbZ, zi.fbZ), Math.max(zi.sbX, zi.fbX), Math.max(zi.sbZ, zi.fbZ)});
            }
            nz.boxes = list.toArray(new int[0][]);
            ZONES.put(key, nz);
            zc = nz;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (int[] b : zc.boxes) {
            if (bx >= b[0] && bx <= b[2] && bz >= b[1] && bz <= b[3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Точки пути вперёд от текущего положения: n точек с шагом step блоков.
     * Результат: {dx, dz, fx, fz} для каждой точки (смещение от текущей позиции и направление).
     */
    static double[][] pathAhead(Vehicle v, double step, int n) {
        initRefl();
        if (!reflOk || v.currentPath == null || v.currentPath.isEmpty()) {
            return null;
        }
        try {
            Map<Integer, List<?>> cache = new java.util.HashMap<>();
            int lane = v.pathLaneIndex;
            int seg = fSeg.getInt(v);
            double off = fOff.getDouble(v);
            double[][] out = new double[n][];
            double p0x = 0.0D;
            double p0z = 0.0D;
            boolean have0 = false;
            double acc = 0.0D;
            int idx = 0;
            int guard = 0;
            double lastFx = 0.0D;
            double lastFz = 1.0D;
            while (idx < n && guard++ < 400) {
                if (lane >= v.currentPath.size()) {
                    break;
                }
                List<?> pts = cache.get(lane);
                if (pts == null) {
                    pts = (List<?>) mPts.invoke(v, v.currentPath.get(lane));
                    cache.put(lane, pts);
                }
                if (pts.size() < 2 || seg >= pts.size() - 1) {
                    lane++;
                    seg = 0;
                    off = 0.0D;
                    continue;
                }
                double len = segLen(pts, seg);
                if (len < 1.0E-6D) {
                    seg++;
                    off = 0.0D;
                    continue;
                }
                Object a = pts.get(seg);
                Object b = pts.get(seg + 1);
                double fx = (vc(b, 0) - vc(a, 0)) / len;
                double fz = (vc(b, 2) - vc(a, 2)) / len;
                double px = vc(a, 0) + fx * off;
                double pz = vc(a, 2) + fz * off;
                if (!have0) {
                    p0x = px;
                    p0z = pz;
                    have0 = true;
                    out[idx++] = new double[] {0.0D, 0.0D, fx, fz};
                    acc = 0.0D;
                }
                lastFx = fx;
                lastFz = fz;
                double need = step - acc;
                double room = len - off;
                if (need <= room) {
                    off += need;
                    acc = 0.0D;
                    double nx = vc(a, 0) + fx * off;
                    double nz = vc(a, 2) + fz * off;
                    if (idx < n) {
                        out[idx++] = new double[] {nx - p0x, nz - p0z, fx, fz};
                    }
                } else {
                    acc += room;
                    seg++;
                    off = 0.0D;
                }
            }
            if (!have0) {
                return null;
            }
            int cnt = idx;
            double[][] res = new double[cnt][];
            System.arraycopy(out, 0, res, 0, cnt);
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Пересекаются ли два прямоугольника (центр, курс, полудлина/полуширина). */
    static boolean rectOverlap(double x1, double z1, double fx1, double fz1, double hl1, double hw1,
                               double x2, double z2, double fx2, double fz2, double hl2, double hw2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double[][] axes = {{fx1, fz1}, {-fz1, fx1}, {fx2, fz2}, {-fz2, fx2}};
        for (double[] ax : axes) {
            double r1 = hl1 * Math.abs(fx1 * ax[0] + fz1 * ax[1]) + hw1 * Math.abs(-fz1 * ax[0] + fx1 * ax[1]);
            double r2 = hl2 * Math.abs(fx2 * ax[0] + fz2 * ax[1]) + hw2 * Math.abs(-fz2 * ax[0] + fx2 * ax[1]);
            if (Math.abs(dx * ax[0] + dz * ax[1]) > r1 + r2) {
                return false;
            }
        }
        return true;
    }

    private static double[] headingOf(Vehicle o) {
        double y = Math.toRadians(PTMEntity.getYaw(o));
        return new double[] {-Math.sin(y), Math.cos(y)};
    }

    /** Свободен ли путь на aheadDist блоков вперёд (машина с запасом не заденет чужие кузова)? */
    static boolean pathFree(Vehicle v, double aheadDist, boolean stationaryOnly, double fromDist) {
        double step = 1.5D;
        int n = (int) Math.ceil(aheadDist / step) + 1;
        double[][] pa = pathAhead(v, step, n);
        if (pa == null) {
            return true;
        }
        double x = PTMEntity.getX(v);
        double z = PTMEntity.getZ(v);
        double hl = v.length / 2.0D + 0.2D;
        double hw = v.width / 2.0D + 0.2D;
        double[] h0 = headingOf(v);
        for (Vehicle o : PTMEntity.getDifferentVehicles(2.0D * (aheadDist + 12.0D), v)) {
            if (stationaryOnly && Math.abs(o.speed) >= 0.5F) {
                continue;
            }
            double[] ho = headingOf(o);
            double ox = PTMEntity.getX(o);
            double oz = PTMEntity.getZ(o);
            double ohl = o.length / 2.0D;
            double ohw = o.width / 2.0D;
            // Уже пересекаемся сейчас (стоим впритык) - такую машину не учитываем
            if (rectOverlap(x, z, h0[0], h0[1], hl, hw, ox, oz, ho[0], ho[1], ohl, ohw)) {
                continue;
            }
            for (int i = 1; i < pa.length; i++) {
                if (i * step < fromDist) {
                    continue;
                }
                if (rectOverlap(x + pa[i][0], z + pa[i][1], pa[i][2], pa[i][3], hl, hw, ox, oz, ho[0], ho[1], ohl, ohw)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Нельзя въезжать на перекрёсток, если за ним нет места для машины («не блокировать перекрёсток»):
     * путь входит в зону перекрёстка в ближайшие ~10 блоков, а после выезда из зоны на длину кузова стоит другая машина.
     */
    static boolean exitBlocked(Vehicle v, Level level) {
        if (inIntersectionAt(level, PTMEntity.getX(v), PTMEntity.getZ(v))) {
            return false;
        }
        double step = 1.5D;
        double[][] pa = pathAhead(v, step, 32);
        if (pa == null) {
            return false;
        }
        double x = PTMEntity.getX(v);
        double z = PTMEntity.getZ(v);
        int firstIn = -1;
        int firstOutAfter = -1;
        for (int i = 0; i < pa.length; i++) {
            boolean in = inIntersectionAt(level, x + pa[i][0], z + pa[i][1]);
            if (firstIn < 0) {
                if (in) {
                    firstIn = i;
                    if (firstIn > 7) {
                        return false;
                    }
                }
            } else if (!in) {
                firstOutAfter = i;
                break;
            }
        }
        if (firstIn < 0 || firstOutAfter < 0) {
            return false;
        }
        double need = v.length + 3.0D;
        double hl = v.length / 2.0D;
        double hw = v.width / 2.0D;
        for (Vehicle o : PTMEntity.getDifferentVehicles(80.0D, v)) {
            if (Math.abs(o.speed) >= 0.5F) {
                continue;
            }
            double[] ho = headingOf(o);
            double ox = PTMEntity.getX(o);
            double oz = PTMEntity.getZ(o);
            if (inIntersectionAt(level, ox, oz)) {
                continue;
            }
            for (int i = firstOutAfter; i < pa.length && (i - firstOutAfter) * step <= need; i++) {
                if (rectOverlap(x + pa[i][0], z + pa[i][1], pa[i][2], pa[i][3], hl, hw, ox, oz, ho[0], ho[1], o.length / 2.0D, o.width / 2.0D)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Контекст для перестроения (машина, для которой сейчас вызван laneChangeCheck). */
    private static final ThreadLocal<Vehicle> CTX = new ThreadLocal<>();

    public static void setCtx(Vehicle v) {
        CTX.set(v);
    }

    /**
     * Замена PTMEntity.exists(Player...) в laneChangeCheck: игрок на дороге впереди (3-16 блоков),
     * а не только ровно в 15 блоках. Объезжаем, когда машина уже почти стоит.
     */
    public static boolean playerAhead(Class<? extends net.minecraft.world.entity.Entity> c, double r, net.minecraft.world.level.LevelAccessor level,
                                      double x, double y, double z) {
        boolean orig = PTMEntity.exists(c, r, level, x, y, z);
        if (orig) {
            return true;
        }
        try {
            Vehicle v = CTX.get();
            if (v == null || Math.abs(v.speed) > 2.5F) {
                return false;
            }
            double yaw = Math.toRadians(PTMEntity.getYaw(v));
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            double vx = PTMEntity.getX(v);
            double vy = PTMEntity.getY(v);
            double vz = PTMEntity.getZ(v);
            for (double t = 3.0D; t <= 16.0D; t += 1.5D) {
                if (PTMEntity.exists(c, 2.4D, level, vx + fx * t, vy, vz + fz * t)) {
                    return true;
                }
            }
        } catch (Exception e) {
            PTMLogger.error(e);
        }
        return false;
    }

    // ---------------------------------------------------------------- отъезд назад

    private static boolean reflInit = false;
    private static boolean reflOk = false;
    private static java.lang.reflect.Field fSeg;
    private static java.lang.reflect.Field fOff;
    private static java.lang.reflect.Method mPts;
    private static java.lang.reflect.Method mMove;
    private static java.lang.reflect.Field[] vecF;
    private static java.lang.reflect.Constructor<?> bpCtor;

    private static void initRefl() {
        if (reflInit) {
            return;
        }
        reflInit = true;
        try {
            Class<?> c = Vehicle.class;
            fSeg = c.getDeclaredField("pathSegmentIndex");
            fSeg.setAccessible(true);
            fOff = c.getDeclaredField("pathSegmentOffset");
            fOff.setAccessible(true);
            mPts = c.getDeclaredMethod("getLanePoints", VDTrafficLane.class);
            mPts.setAccessible(true);
            mMove = c.getDeclaredMethod("moveAlongPath", double.class, double.class);
            mMove.setAccessible(true);
            bpCtor = BlockPos.class.getConstructor(int.class, int.class, int.class);
            reflOk = true;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: рефлексия недоступна, отъезд назад и проверка разметки отключены: " + t);
        }
    }

    private static double vc(Object vec, int i) throws IllegalAccessException {
        if (vecF == null) {
            java.lang.reflect.Field[] f = new java.lang.reflect.Field[3];
            String[][] names = {{"f_82479_", "x"}, {"f_82480_", "y"}, {"f_82481_", "z"}};
            for (int k = 0; k < 3; k++) {
                for (String n : names[k]) {
                    try {
                        f[k] = vec.getClass().getField(n);
                        break;
                    } catch (NoSuchFieldException ignored) {
                        // пробуем следующее имя
                    }
                }
                if (f[k] == null) {
                    throw new IllegalAccessException("Vec3 field " + k);
                }
            }
            vecF = f;
        }
        return vecF[i].getDouble(vec);
    }

    private static double segLen(List<?> pts, int i) throws IllegalAccessException {
        Object a = pts.get(i);
        Object b = pts.get(i + 1);
        double dx = vc(a, 0) - vc(b, 0);
        double dy = vc(a, 1) - vc(b, 1);
        double dz = vc(a, 2) - vc(b, 2);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Сдвигает машину назад по её пути на d блоков. false - дальше назад ехать некуда. */
    static boolean stepBack(Vehicle v, double d) {
        initRefl();
        if (!reflOk) {
            return false;
        }
        try {
            if (v.currentPath == null || v.currentPath.isEmpty() || v.pathLaneIndex >= v.currentPath.size()) {
                return false;
            }
            if (v.pathProgress < d + 1.0D) {
                return false;
            }
            int lane = v.pathLaneIndex;
            int seg = fSeg.getInt(v);
            double off = fOff.getDouble(v);
            double prog = v.pathProgress;
            double rem = d;
            int guard = 0;
            while (rem > 1.0E-9D && guard++ < 64) {
                if (off > 1.0E-6D) {
                    double step = Math.min(rem, off);
                    off -= step;
                    rem -= step;
                    prog -= step;
                } else if (seg > 0) {
                    seg--;
                    List<?> pts = (List<?>) mPts.invoke(v, v.currentPath.get(lane));
                    off = segLen(pts, seg);
                } else if (lane > 0) {
                    lane--;
                    List<?> pts = (List<?>) mPts.invoke(v, v.currentPath.get(lane));
                    seg = pts.size() - 2;
                    if (seg < 0) {
                        return false;
                    }
                    off = segLen(pts, seg);
                } else {
                    break;
                }
            }
            if (rem > d * 0.5D) {
                return false;
            }
            v.pathLaneIndex = lane;
            fSeg.setInt(v, seg);
            fOff.setDouble(v, off);
            v.pathProgress = prog;
            mMove.invoke(v, 0.0D, (double) PTMEntity.getYaw(v));
            return true;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: ошибка заднего хода: " + t);
            return false;
        }
    }

    /** Позади свободно (нет машин и людей)? */
    private static boolean spaceBehind(Vehicle v, Level level) {
        double yaw = Math.toRadians(PTMEntity.getYaw(v));
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double x = PTMEntity.getX(v);
        double y = PTMEntity.getY(v);
        double z = PTMEntity.getZ(v);
        for (Vehicle o : PTMEntity.getDifferentVehicles(24.0D, v)) {
            double dx = PTMEntity.getX(o) - x;
            double dz = PTMEntity.getZ(o) - z;
            double along = dx * fx + dz * fz;
            double lat = Math.abs(-dx * fz + dz * fx);
            if (along < 0.0D && along > -(v.length / 2.0D + o.length / 2.0D + 2.0D)
                && lat < v.width / 2.0D + o.width / 2.0D + 0.8D) {
                return false;
            }
        }
        double half = v.length / 2.0D;
        for (double dist = 1.5D; dist <= 4.5D; dist += 1.5D) {
            double cx = x - fx * (half + dist);
            double cz = z - fz * (half + dist);
            if (PTMEntity.exists(Pedestrian.class, 1.4D, level, cx, y, cz) || PTMEntity.exists(Player.class, 1.4D, level, cx, y, cz)) {
                return false;
            }
        }
        return true;
    }

    /** Ближайшая спереди машина, с которой мы упёрлись (стоит и сама ждёт). */
    private static Vehicle blockerAhead(Vehicle v) {
        double yaw = Math.toRadians(PTMEntity.getYaw(v));
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double x = PTMEntity.getX(v);
        double z = PTMEntity.getZ(v);
        Vehicle best = null;
        double bestAlong = 1.0E9D;
        for (Vehicle o : PTMEntity.getDifferentVehicles(28.0D, v)) {
            double dx = PTMEntity.getX(o) - x;
            double dz = PTMEntity.getZ(o) - z;
            double along = dx * fx + dz * fz;
            double lat = Math.abs(-dx * fz + dz * fx);
            if (along > 0.0D && along < v.length / 2.0D + o.length / 2.0D + 7.0D
                && lat < v.width / 2.0D + o.width / 2.0D + 2.0D && along < bestAlong) {
                best = o;
                bestAlong = along;
            }
        }
        return best;
    }

    /** Должна ли эта машина уступить (отъехать): блокирует такая же ждущая машина, которая ждёт дольше. */
    private static boolean shouldYield(Vehicle v, Drv d, Level level) {
        Vehicle b = blockerAhead(v);
        if (b == null || Math.abs(b.speed) >= 0.3F || !spaceBehind(v, level)) {
            return false;
        }
        Drv bd = DRV.get(System.identityHashCode(b));
        if (bd == null || bd.firstBlocked == 0L || d.firstBlocked == 0L) {
            return false;
        }
        if (bd.firstBlocked != d.firstBlocked) {
            return d.firstBlocked > bd.firstBlocked;
        }
        return System.identityHashCode(v) > System.identityHashCode(b);
    }

    // ---------------------------------------------------------------- перестроение

    /**
     * Одинарная линия (LINE) считается сплошной, если подряд занимает больше LINE_SOLID_RUN блоков
     * (на перегоне она «3 через 3», у перекрёстка растягивается в сплошную).
     */
    static final int LINE_SOLID_RUN = 5;

    private static boolean forbiddenMarking(RoadMarkingTypes t) {
        return t == RoadMarkingTypes.DOUBLE_LINE || t == RoadMarkingTypes.PLATFORM_EDGE
            || t == RoadMarkingTypes.RESTRICTED || t == RoadMarkingTypes.ZIG_ZAG;
    }

    /** Тип разметки в блоке (проверяем слой пола и соседние по высоте) или null. */
    private static RoadMarkingTypes markingAt(Level level, int x, int y, int z) throws ReflectiveOperationException {
        for (int dy = -1; dy <= 1; dy++) {
            String data = VDStorageHandler.get(level, (BlockPos) bpCtor.newInstance(x, y + dy, z), VDMemoryIDs.ROAD_MARKING);
            if (data != null && !data.isEmpty()) {
                VDRoadMarking m = new VDRoadMarking(data);
                if (m.type != null) {
                    return m.type;
                }
            }
        }
        return null;
    }

    /** Сколько блоков одинарной линии подряд проходит через (x,z) вдоль курса (fx,fz)? */
    private static int lineRun(Level level, int x, int y, int z, double fx, double fz) throws ReflectiveOperationException {
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL));
        for (int dir = -1; dir <= 1; dir += 2) {
            for (double t = 0.5D; t <= 8.0D; t += 0.5D) {
                int bx = (int) Math.floor(x + 0.5D + fx * t * dir);
                int bz = (int) Math.floor(z + 0.5D + fz * t * dir);
                long key = ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
                if (seen.contains(key)) {
                    continue;
                }
                if (markingAt(level, bx, y, bz) == RoadMarkingTypes.LINE) {
                    seen.add(key);
                } else {
                    break;
                }
            }
        }
        return seen.size();
    }

    /**
     * Расширение VehicleUtils.mergeLaneHasObstacle: перестроение запрещено, если на пути стоит
     * сплошная/двойная и подобная разметка или в соседней полосе (спереди или сзади) есть машина.
     */
    public static boolean mergeBlocked(Vehicle v, VDTrafficLane lane, boolean orig, Level level) {
        if (orig) {
            return true;
        }
        try {
            if (lane == null || lane.getStart() == null || lane.getEnd() == null) {
                return orig;
            }
            initRefl();
            if (!reflOk) {
                return orig;
            }
            Object st = lane.getStart();
            Object en = lane.getEnd();
            double sx = vc(st, 0);
            double sy = vc(st, 1);
            double sz = vc(st, 2);
            double ex = vc(en, 0);
            double ez = vc(en, 2);
            double len = Math.sqrt((ex - sx) * (ex - sx) + (ez - sz) * (ez - sz));
            if (len < 0.01D) {
                return orig;
            }
            double ux = (ex - sx) / len;
            double uz = (ez - sz) / len;
            // 1) разметка на пути перестроения
            double hyaw = Math.toRadians(PTMEntity.getYaw(v));
            double hfx = -Math.sin(hyaw);
            double hfz = Math.cos(hyaw);
            int by = (int) Math.floor(sy);
            java.util.HashSet<Long> checked = new java.util.HashSet<>();
            for (double t = 0.0D; t <= len; t += 0.5D) {
                int bx = (int) Math.floor(sx + ux * t);
                int bz = (int) Math.floor(sz + uz * t);
                if (!checked.add(((long) bx << 32) ^ (bz & 0xFFFFFFFFL))) {
                    continue;
                }
                RoadMarkingTypes mt = markingAt(level, bx, by, bz);
                if (mt == null) {
                    continue;
                }
                if (forbiddenMarking(mt)) {
                    return true;
                }
                if (mt == RoadMarkingTypes.LINE && lineRun(level, bx, by, bz, hfx, hfz) > LINE_SOLID_RUN) {
                    return true;
                }
            }
            // 2) машины в целевой полосе: ось полосы проходит через конец траектории перестроения
            //    параллельно текущему курсу; смотрим 12 блоков назад и 14 вперёд от этой точки
            double yaw = Math.toRadians(PTMEntity.getYaw(v));
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            List<Vehicle> others = PTMEntity.getDifferentVehicles(40.0D, v);
            for (double t = -12.0D; t <= 14.0D; t += 1.5D) {
                double px = ex + fx * t;
                double pz = ez + fz * t;
                for (Vehicle o : others) {
                    double oy = Math.toRadians(PTMEntity.getYaw(o));
                    double ofx = -Math.sin(oy);
                    double ofz = Math.cos(oy);
                    double dx = px - PTMEntity.getX(o);
                    double dz = pz - PTMEntity.getZ(o);
                    double along = Math.abs(dx * ofx + dz * ofz);
                    double lat = Math.abs(-dx * ofz + dz * ofx);
                    if (along < o.length / 2.0D + 1.5D && lat < o.width / 2.0D + v.width / 2.0D) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: ошибка проверки перестроения: " + t);
        }
        return orig;
    }

    /** Причина, по которой машине НЕЛЬЗЯ разрешать ехать (null - можно). */
    private static String hardReason(Vehicle v, Level level) {
        if (trafficRed(v)) {
            return "светофор";
        }
        if (VehicleUtils.loweredEntryBarrierAhead(v) || VehicleUtils.closedRailwayCrossingAhead(v)) {
            return "шлагбаум";
        }
        if (livingAhead(v, level)) {
            return "пешеход";
        }
        double forward = Math.max(v.speed, 4.0F) + Math.max(0.0D, v.length / 1.5D);
        Vehicle front = VehicleUtils.vehicleInFront(v, forward);
        if (front != null) {
            if (v.vehicleType != VehicleTypes.TRAM && front.vehicleType == VehicleTypes.TRAM) {
                return "трамвай";
            }
            double diff = Math.abs(wrap180(PTMEntity.getYaw(v) - PTMEntity.getYaw(front)));
            if (diff <= 50.0D) {
                return "очередь";
            }
        }
        return null;
    }

    /**
     * Подмена чтения Vehicle.handBrake в VehicleUtils.laneChangeCheck:
     * машина впереди считается препятствием для объезда, если она на ручнике
     * или стоит без движения дольше OBSTACLE_STILL_MS (не на красном).
     */
    public static boolean obstacleStill(Vehicle a) {
        if (a.handBrake) {
            return true;
        }
        try {
            Drv d = DRV.get(System.identityHashCode(a));
            if (d == null || d.stillSince == 0L) {
                return false;
            }
            long now = System.currentTimeMillis();
            if (now - d.seen > 3000L || now - d.stillSince < OBSTACLE_STILL_MS) {
                return false;
            }
            if (a.trafficLight != null && (a.trafficLight.state == TrafficLightColors.RED
                || a.trafficLight.state == TrafficLightColors.YELLOW)) {
                return false;
            }
            return !VDUtils.inZone(a, VDMemoryIDs.INTERSECTION);
        } catch (Exception e) {
            return false;
        }
    }
}
