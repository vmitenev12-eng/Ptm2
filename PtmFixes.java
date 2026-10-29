package com.rinventor.ptm2.fixes;

import com.rinventor.ptm2.PTM;
import com.rinventor.ptm2.dimension.virtual.types.VDDepot;
import com.rinventor.ptm2.dimension.virtual.types.VDPlatform;
import com.rinventor.ptm2.dimension.virtual.types.VDRoute;
import com.rinventor.ptm2.dimension.virtual.types.VDTrackableVehicle;
import com.rinventor.ptm2.engine.bezier.utility.Pair;
import com.rinventor.ptm2.engine.computing.DirectionalPos;
import com.rinventor.ptm2.core.init.ModItems;
import com.rinventor.ptm2.core.init.ModSounds;
import com.rinventor.ptm2.engine.vehicle.ValidatorLocation;
import com.rinventor.ptm2.engine.vehicle.WheelLocation;
import com.rinventor.ptm2.packets.sync.VehicleSeatClientboundPacket;
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
import com.rinventor.ptm2.PTM;
import com.rinventor.ptm2.core.data.WorldSavedData;
import com.rinventor.ptm2.core.properties.RoadMarkingTypes;
import com.rinventor.ptm2.dimension.virtual.storage.VDStorage;
import com.rinventor.ptm2.engine.computing.Rotation8;
import com.rinventor.ptm2.objects.entities.vehicle.bus.Bus;
import com.rinventor.ptm2.core.properties.TrafficLightColors;
import com.rinventor.ptm2.dimension.virtual.types.VDIntersection;
import com.rinventor.ptm2.dimension.virtual.types.VDRoadMarking;
import com.rinventor.ptm2.dimension.virtual.types.VDTrafficLane;
import net.minecraft.core.BlockPos;
import com.rinventor.ptm2.dimension.physical.VehicleUtils;
import com.rinventor.ptm2.dimension.virtual.types.VDTransportCompany;
import com.rinventor.ptm2.dimension.virtual.types.VDSchedule;
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
import com.rinventor.ptm2.dimension.digital.rows.TimetableRowLayout;
import com.rinventor.ptm2.engine.component.LayoutListBox;
import com.rinventor.ptm2.engine.component.IconButton;
import com.rinventor.ptm2.engine.component.TimeBox;
import com.rinventor.ptm2.engine.component.ComboBox;
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
    public static final String BUILD = "fixes-2026-10-29-zones";
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
            watchVehicles(level);
            summaryLog(level);
            loadTimetableFile(level);
        } catch (Exception e) {
            PTMLogger.error(e);
        }
    }

    private static final Map<Integer, Long> NO_SLOT_LOG = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------------------------------
    // Ручное (точное) расписание линий с «реалистичным» типом.
    // Хранится в поле «временная шкала» расписания: для каждого гаражного номера список пар
    // (время, рейс), где время = день недели * 1440 + минута суток (понедельник = 0), рейс = номер записи рейса.
    // Если в шкале есть хотя бы одно время, расписание линии строится из неё (по дням, точно), число автобусов
    // задаётся самой таблицей и не зависит от рекомендаций. Иначе работает автоматическое расписание по интервалам.
    // Место под будущую кнопку «добавить автобус из инвентаря в парк компании»: список номеров парка здесь же.
    // ---------------------------------------------------------------------------------------------

    private static java.lang.reflect.Method schedGroups;
    private static java.lang.reflect.Method schedStopTimes;
    private static java.lang.reflect.Method msPerMinute;

    private static VDSchedule scheduleOf(Level level, int lineID, int[] keyOut) {
        VDSchedule best = null;
        int bestKey = Integer.MAX_VALUE;
        boolean bestManual = false;
        for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.SCHEDULE).entrySet()) {
            VDSchedule sc = new VDSchedule(e.getValue());
            if (sc.lineID != lineID) {
                continue;
            }
            boolean manual = hasManualTimes(sc);
            if (best == null || (manual && !bestManual) || (manual == bestManual && e.getKey() < bestKey)) {
                best = sc;
                bestKey = e.getKey();
                bestManual = manual;
            }
        }
        if (best != null && keyOut != null) {
            keyOut[0] = bestKey;
        }
        return best;
    }

    private static boolean hasManualTimes(VDSchedule sc) {
        if (sc == null || sc.timeline == null) {
            return false;
        }
        for (Pair<Integer, List<Pair<Integer, Integer>>> v : sc.timeline) {
            if (v.getSecond() != null && !v.getSecond().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Записи расписания линии на день из ручной таблицы или null, если у линии автоматическое расписание. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List manualEntries(Level level, int lineID, int day) {
        try {
            String lineData = VDStorageHandler.get(level, lineID, VDMemoryIDs.TRANSPORT_LINE);
            if (lineData == null || lineData.isEmpty() || new VDTransportLine(lineData).scheduleType != 1) {
                return null;
            }
            VDSchedule sc = scheduleOf(level, lineID, null);
            if (!hasManualTimes(sc)) {
                return null;
            }
            Map<Integer, VDRoute> routes = new HashMap<>();
            List<Pair<Integer, List<Integer>>> byRoute = new ArrayList<>();
            for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.ROUTE).entrySet()) {
                VDRoute r = new VDRoute(e.getValue());
                if (r.lineID == lineID && r.stops != null && r.stops.length != 0) {
                    List<Integer> stops = com.rinventor.ptm2.engine.computing.Converter.toIntList(r.stops);
                    if (stops.size() >= 2) {
                        routes.put(e.getKey(), r);
                        byRoute.add(Pair.of(e.getKey(), stops));
                    }
                }
            }
            List result = new ArrayList();
            if (byRoute.isEmpty()) {
                return result;
            }
            byRoute.sort(java.util.Comparator.comparingInt(Pair::getFirst));
            Class<?> su = Class.forName("com.rinventor.ptm2.dimension.virtual.util.ScheduleUtils");
            if (schedGroups == null) {
                schedGroups = su.getDeclaredMethod("buildRouteStopGroups", List.class);
                schedGroups.setAccessible(true);
                schedStopTimes = su.getDeclaredMethod("buildStopTimes", List.class, Map.class, int.class, int.class);
                schedStopTimes.setAccessible(true);
            }
            List<List<Pair<Integer, Integer>>> groups = (List<List<Pair<Integer, Integer>>>) schedGroups.invoke(null, byRoute);
            for (Pair<Integer, List<Pair<Integer, Integer>>> veh : sc.timeline) {
                for (Pair<Integer, Integer> tr : veh.getSecond()) {
                    int t = tr.getFirst();
                    if (Math.floorDiv(t, 1440) != day) {
                        continue;
                    }
                    int minute = Math.floorMod(t, 1440);
                    int routeID = tr.getSecond();
                    List<Pair<Integer, Integer>> group = null;
                    for (List<Pair<Integer, Integer>> g : groups) {
                        if (!g.isEmpty() && g.get(0).getFirst() == routeID) {
                            group = g;
                            break;
                        }
                    }
                    if (group == null) {
                        continue;
                    }
                    List<Pair<Integer, Pair<Integer, Integer>>> st = (List<Pair<Integer, Pair<Integer, Integer>>>) schedStopTimes.invoke(null, group, routes, day, minute);
                    if (st == null || st.isEmpty()) {
                        continue;
                    }
                    st.remove(st.size() - 1);
                    for (Pair<Integer, Pair<Integer, Integer>> x : st) {
                        result.add(Pair.of(x.getFirst(), Pair.of(x.getSecond().getFirst(), TimeHelper.formatTime(Math.floorMod(x.getSecond().getSecond(), 1440)))));
                    }
                }
            }
            return result;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: manualEntries " + t);
            return null;
        }
    }

    /** Гаражный номер из ручной таблицы для выезда (рейс, время): ищется по дню недели и минуте с допуском 1 минута. */
    static int manualVehicle(Level level, VDSchedule sc, int routeID, long routeSet) {
        try {
            if (msPerMinute == null) {
                msPerMinute = Class.forName("com.rinventor.ptm2.dimension.physical.TransportManager").getDeclaredMethod("millisPerGameMinute", Level.class);
                msPerMinute.setAccessible(true);
            }
            long ms = (Long) msPerMinute.invoke(null, level);
            int nowMin = TimeHelper.hour(level) * 60 + TimeHelper.minute(level);
            int day = Math.floorMod(TimeHelper.weekday(level) - 1, 7);
            long diff = Math.round((routeSet - TimeHelper.now()) / (double) ms);
            int dep = nowMin + (int) diff;
            int dDay = Math.floorMod(day + Math.floorDiv(dep, 1440), 7);
            int dMin = Math.floorMod(dep, 1440);
            for (Pair<Integer, List<Pair<Integer, Integer>>> v : sc.timeline) {
                for (Pair<Integer, Integer> tr : v.getSecond()) {
                    int t = tr.getFirst();
                    if (Math.floorDiv(t, 1440) == dDay && tr.getSecond() == routeID && Math.abs(Math.floorMod(t, 1440) - dMin) <= 1) {
                        return v.getFirst();
                    }
                }
            }
        } catch (Throwable t) {
            // не критично
        }
        return -1;
    }

    private static long lastTimetableFile = 0L;
    private static int timetableHash = 0;

    private static final String[] DAY_NAMES = {"\u043f\u043d", "\u0432\u0442", "\u0441\u0440", "\u0447\u0442", "\u043f\u0442", "\u0441\u0431", "\u0432\u0441"};

    /**
     * Загрузка ручной таблицы из файла config/ptm2-timetable.txt (раз в 30 секунд, при изменении файла). Строка файла:
     *   линия 6: 1764 пн,ср,пт 09:00 13:00 17:00 рейс 1
     * (номер линии как в её настройках; гаражный номер; дни через запятую или «все»; времена выезда; «рейс» необязателен,
     * по умолчанию первый). Строки с тем же номером линии объединяются, начинаются с # — комментарии. Для линии,
     * которая есть в файле, старая таблица заменяется целиком. Это временный способ ввода, пока нет экрана редактирования.
     */
    private static void loadTimetableFile(Level level) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastTimetableFile < 30000L) {
            return;
        }
        lastTimetableFile = nowMs;
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("config", "ptm2-timetable.txt");
            if (!java.nio.file.Files.exists(path)) {
                return;
            }
            String text = new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            int hash = text.hashCode();
            if (timetableHash == 0) {
                java.nio.file.Path hp = java.nio.file.Paths.get("config", "ptm2-timetable.applied");
                if (java.nio.file.Files.exists(hp)) {
                    try {
                        timetableHash = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(hp), java.nio.charset.StandardCharsets.UTF_8).trim());
                    } catch (NumberFormatException ignored) {
                        timetableHash = 0;
                    }
                }
            }
            if (hash == timetableHash) {
                return;
            }
            timetableHash = hash;
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("config", "ptm2-timetable.applied"), String.valueOf(hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException ignored) {
                // не критично
            }
            Map<String, Map<Integer, List<int[]>>> byLine = new java.util.LinkedHashMap<>();
            int bad = 0;
            for (String raw : text.split("\\r?\\n")) {
                String ln = raw.trim();
                if (ln.isEmpty() || ln.startsWith("#")) {
                    continue;
                }
                try {
                    int colon = ln.indexOf(':');
                    String head = ln.substring(0, colon).trim();
                    if (!head.toLowerCase().startsWith("\u043b\u0438\u043d\u0438\u044f")) {
                        throw new IllegalArgumentException("head");
                    }
                    String lineNum = head.substring(5).trim();
                    String[] tok = ln.substring(colon + 1).trim().split("\\s+");
                    int garage = Integer.parseInt(tok[0]);
                    java.util.Set<Integer> days = new java.util.TreeSet<>();
                    for (String d : tok[1].toLowerCase().split(",")) {
                        if (d.equals("\u0432\u0441\u0435")) {
                            for (int i = 0; i < 7; i++) {
                                days.add(i);
                            }
                        } else {
                            int idx = java.util.Arrays.asList(DAY_NAMES).indexOf(d);
                            if (idx < 0) {
                                throw new IllegalArgumentException("day " + d);
                            }
                            days.add(idx);
                        }
                    }
                    int routeIdx = 1;
                    List<Integer> times = new ArrayList<>();
                    for (int i = 2; i < tok.length; i++) {
                        if (tok[i].equalsIgnoreCase("\u0440\u0435\u0439\u0441")) {
                            routeIdx = Integer.parseInt(tok[++i]);
                        } else {
                            int m = (int) TimeHelper.parseTime(tok[i]);
                            if (m < 0) {
                                throw new IllegalArgumentException("time " + tok[i]);
                            }
                            times.add(m);
                        }
                    }
                    List<int[]> list = byLine.computeIfAbsent(lineNum, k -> new java.util.LinkedHashMap<Integer, List<int[]>>()).computeIfAbsent(garage, k -> new ArrayList<>());
                    for (int d : days) {
                        for (int m : times) {
                            list.add(new int[] {d * 1440 + m, routeIdx});
                        }
                    }
                } catch (Exception ex) {
                    bad++;
                    PTMLogger.warn("PTM2 \u0440\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u0435: \u0441\u0442\u0440\u043e\u043a\u0430 \u0444\u0430\u0439\u043b\u0430 \u043d\u0435 \u0440\u0430\u0437\u043e\u0431\u0440\u0430\u043d\u0430: " + ln + " (" + ex.getMessage() + ")");
                }
            }
            for (Map.Entry<Integer, String> le : VDStorageHandler.getAll(level, VDMemoryIDs.TRANSPORT_LINE).entrySet()) {
                VDTransportLine line = new VDTransportLine(le.getValue());
                Map<Integer, List<int[]>> vehs = byLine.get(String.valueOf(line.number).trim());
                if (vehs == null) {
                    continue;
                }
                int[] key = new int[1];
                VDSchedule sc = scheduleOf(level, le.getKey(), key);
                if (sc == null) {
                    PTMLogger.warn("PTM2 \u0440\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u0435: \u0443 \u043b\u0438\u043d\u0438\u0438 " + line.number + " \u043d\u0435\u0442 \u0437\u0430\u043f\u0438\u0441\u0438 \u0440\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u044f, \u0441\u043d\u0430\u0447\u0430\u043b\u0430 \u0441\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u0435 \u0435\u0451 \u0432 \u0440\u0435\u0434\u0430\u043a\u0442\u043e\u0440\u0435");
                    continue;
                }
                List<Integer> routeIds = new ArrayList<>();
                for (Map.Entry<Integer, String> re : VDStorageHandler.getAll(level, VDMemoryIDs.ROUTE).entrySet()) {
                    VDRoute r = new VDRoute(re.getValue());
                    if (r.lineID == le.getKey() && r.stops != null && r.stops.length >= 2) {
                        routeIds.add(re.getKey());
                    }
                }
                java.util.Collections.sort(routeIds);
                List<Pair<Integer, List<Pair<Integer, Integer>>>> tl = new ArrayList<>();
                int trips = 0;
                for (Map.Entry<Integer, List<int[]>> ve : vehs.entrySet()) {
                    List<Pair<Integer, Integer>> items = new ArrayList<>();
                    for (int[] it : ve.getValue()) {
                        if (it[1] >= 1 && it[1] <= routeIds.size()) {
                            items.add(Pair.of(it[0], routeIds.get(it[1] - 1)));
                            trips++;
                        }
                    }
                    items.sort(java.util.Comparator.comparingInt(Pair::getFirst));
                    tl.add(Pair.of(ve.getKey(), items));
                }
                sc.timeline = tl;
                VDStorageHandler.update(level, key[0], sc.toString());
                SCHEDULE_CACHE.clear();
                PTMLogger.info("PTM2 \u0440\u0430\u0441\u043f\u0438\u0441\u0430\u043d\u0438\u0435: \u043b\u0438\u043d\u0438\u044f " + line.number + " \u0437\u0430\u0433\u0440\u0443\u0436\u0435\u043d\u0430 \u0438\u0437 \u0444\u0430\u0439\u043b\u0430: \u0430\u0432\u0442\u043e\u0431\u0443\u0441\u043e\u0432 " + tl.size() + ", \u0432\u044b\u0435\u0437\u0434\u043e\u0432 \u0432 \u043d\u0435\u0434\u0435\u043b\u044e " + trips + (bad > 0 ? ", \u043e\u0448\u0438\u0431\u043e\u043a " + bad : ""));
            }
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: loadTimetableFile " + t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Экран редактирования ручного расписания (ScheduleEditScreen, заготовка автора): патчи в Patcher подменяют
    // пустые методы экрана вызовами ниже. День недели выбирается в списке «вариант», рейс — в списке рейса,
    // строки списка — времена выезда. Выезды, добавленные в экране, хранятся под номером транспорта -1
    // («номер назначается автоматически»); номера, заданные файлом, сохраняются как есть.
    // ---------------------------------------------------------------------------------------------

    private static final Map<Object, VDSchedule> EDIT_STATE = new java.util.WeakHashMap<>();
    private static final Map<Object, Integer> EDIT_KEYS = new java.util.WeakHashMap<>();
    private static final Map<Object, Boolean> EDIT_LABELED = new java.util.WeakHashMap<>();

    private static Object screenField(Object screen, String name) throws Exception {
        Class<?> c = screen.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(screen);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static VDSchedule editSchedule(Object screen) throws Exception {
        VDSchedule sc = EDIT_STATE.get(screen);
        if (sc == null) {
            Level level = (Level) screenField(screen, "level");
            int id = (Integer) screenField(screen, "scheduleID");
            int lineIdOfScreen = (Integer) screenField(screen, "lineID");
            int[] kh = new int[1];
            VDSchedule same = scheduleOf(level, lineIdOfScreen, kh);
            if (same != null) {
                sc = same;
                EDIT_KEYS.put(screen, kh[0]);
            } else {
                sc = new VDSchedule(VDStorageHandler.get(level, id, VDMemoryIDs.SCHEDULE));
            }
            EDIT_STATE.put(screen, sc);
        }
        return sc;
    }

    private static void editSave(Object screen, VDSchedule sc) throws Exception {
        Level level = (Level) screenField(screen, "level");
        Integer k = EDIT_KEYS.get(screen);
        int id = k != null ? k : (Integer) screenField(screen, "scheduleID");
        VDStorageHandler.update(level, id, sc.toString());
    }

    private static int editDay(Object screen) throws Exception {
        int d = ((ComboBox) screenField(screen, "CBOption")).getSelectedIndex();
        return d < 0 ? 0 : d;
    }

    private static int editRoute(Object screen, int which) throws Exception {
        Object v = screenField(screen, which == 1 ? "routeID1" : "routeID2");
        return v == null ? -1 : (Integer) v;
    }

    private static String fmtMin(int m) {
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private static int parseHM(String s) {
        if (s == null || s.length() != 5 || s.charAt(2) != ':') {
            return -1;
        }
        try {
            int h = Integer.parseInt(s.substring(0, 2));
            int m = Integer.parseInt(s.substring(3, 5));
            return h < 24 && m < 60 ? h * 60 + m : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Маршруты линии, с которых начинается цепочка рейсов (на них строятся выезды). */
    @SuppressWarnings("unchecked")
    private static java.util.Set<Integer> groupFirstRoutes(Level level, int lineID) {
        java.util.Set<Integer> res = new java.util.HashSet<>();
        try {
            List<Pair<Integer, List<Integer>>> byRoute = new ArrayList<>();
            for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.ROUTE).entrySet()) {
                VDRoute r = new VDRoute(e.getValue());
                if (r.lineID == lineID && r.stops != null && r.stops.length != 0) {
                    List<Integer> stops = com.rinventor.ptm2.engine.computing.Converter.toIntList(r.stops);
                    if (stops.size() >= 2) {
                        byRoute.add(Pair.of(e.getKey(), stops));
                    }
                }
            }
            byRoute.sort(java.util.Comparator.comparingInt(Pair::getFirst));
            if (schedGroups == null) {
                Class<?> su = Class.forName("com.rinventor.ptm2.dimension.virtual.util.ScheduleUtils");
                schedGroups = su.getDeclaredMethod("buildRouteStopGroups", List.class);
                schedGroups.setAccessible(true);
                schedStopTimes = su.getDeclaredMethod("buildStopTimes", List.class, Map.class, int.class, int.class);
                schedStopTimes.setAccessible(true);
            }
            for (List<Pair<Integer, Integer>> g : (List<List<Pair<Integer, Integer>>>) schedGroups.invoke(null, byRoute)) {
                if (!g.isEmpty()) {
                    res.add(g.get(0).getFirst());
                }
            }
        } catch (Throwable t) {
            // не критично
        }
        return res;
    }

    private static List<Pair<Integer, Integer>> autoVehicleList(VDSchedule sc) {
        for (Pair<Integer, List<Pair<Integer, Integer>>> v : sc.timeline) {
            if (v.getFirst() == -1) {
                return v.getSecond();
            }
        }
        List<Pair<Integer, Integer>> items = new ArrayList<>();
        sc.timeline.add(Pair.of(-1, items));
        return items;
    }

    /** Список «вариант» = дни недели; пустая таблица заполняется из текущего автоматического расписания. */
    public static void editorOptions(Object screen) {
        try {
            ComboBox cb = (ComboBox) screenField(screen, "CBOption");
            int selected = cb.getSelectedIndex();
            List<String> names = new ArrayList<>(Translation.getList("ptm2.schedule.interval_options"));
            if (names.size() != 7) {
                names = new ArrayList<>(java.util.Arrays.asList("\u041f\u043d", "\u0412\u0442", "\u0421\u0440", "\u0427\u0442", "\u041f\u0442", "\u0421\u0431", "\u0412\u0441"));
            }
            cb.setValues(names);
            cb.setSelectedIndex(selected < 0 || selected > 6 ? 0 : selected);
            VDSchedule sc = editSchedule(screen);
            if (!hasManualTimes(sc)) {
                Level level = (Level) screenField(screen, "level");
                int lineID = (Integer) screenField(screen, "lineID");
                java.util.Set<Integer> firsts = groupFirstRoutes(level, lineID);
                Map<Integer, Integer> firstPlatform = new HashMap<>();
                for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.ROUTE).entrySet()) {
                    VDRoute r = new VDRoute(e.getValue());
                    if (r.lineID == lineID && firsts.contains(e.getKey()) && r.stops != null && r.stops.length != 0) {
                        firstPlatform.put(e.getKey(), com.rinventor.ptm2.engine.computing.Converter.toIntList(r.stops).get(0));
                    }
                }
                List<Pair<Integer, Integer>> items = autoVehicleList(sc);
                for (int d = 0; d < 7; d++) {
                    for (Object o : ScheduleUtils.getSimpleScheduleEntriesFor(level, lineID, d)) {
                        Pair<Integer, Pair<Integer, String>> en = (Pair<Integer, Pair<Integer, String>>) o;
                        Integer fp = firstPlatform.get(en.getFirst());
                        if (fp != null && fp.intValue() == en.getSecond().getFirst()) {
                            int m = (int) TimeHelper.parseTime(en.getSecond().getSecond());
                            if (m >= 0) {
                                items.add(Pair.of(d * 1440 + m, en.getFirst()));
                            }
                        }
                    }
                }
                if (!items.isEmpty()) {
                    items.sort(java.util.Comparator.comparingInt(Pair::getFirst));
                    editSave(screen, sc);
                }
            }
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: editorOptions " + t);
        }
    }

    /** Подписи рейсов в списках: номер, названия конечных остановок и число остановок (чтобы рейсы различались). */
    private static void relabelRoutes(Object screen) {
        try {
            if (EDIT_LABELED.containsKey(screen)) {
                return;
            }
            Level level = (Level) screenField(screen, "level");
            Map<?, ?> idx = (Map<?, ?>) screenField(screen, "routeIDs");
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < idx.size(); i++) {
                Object rid = idx.get(i);
                if (rid == null) {
                    continue;
                }
                VDRoute r = new VDRoute(VDStorageHandler.get(level, (Integer) rid, VDMemoryIDs.ROUTE));
                labels.add((i + 1) + ". " + r.getDisplayName(level) + " (" + r.stops.length + ")");
            }
            for (String f : new String[] {"CBRoute1", "CBRoute2"}) {
                ComboBox cb = (ComboBox) screenField(screen, f);
                int sel = cb.getSelectedIndex();
                cb.setValues(new ArrayList<>(labels));
                cb.setSelectedIndex(sel);
            }
            EDIT_LABELED.put(screen, Boolean.TRUE);
        } catch (Throwable t) {
            // подписи по умолчанию
        }
    }

    /** Время прихода на конечную для выезда в заданную минуту (или -1). */
    @SuppressWarnings("unchecked")
    private static int tripEnd(Level level, int lineID, int day, int minute, int routeID) {
        try {
            Map<Integer, VDRoute> routes = new HashMap<>();
            List<Pair<Integer, List<Integer>>> byRoute = new ArrayList<>();
            for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.ROUTE).entrySet()) {
                VDRoute r = new VDRoute(e.getValue());
                if (r.lineID == lineID && r.stops != null && r.stops.length != 0) {
                    List<Integer> stops = com.rinventor.ptm2.engine.computing.Converter.toIntList(r.stops);
                    if (stops.size() >= 2) {
                        routes.put(e.getKey(), r);
                        byRoute.add(Pair.of(e.getKey(), stops));
                    }
                }
            }
            byRoute.sort(java.util.Comparator.comparingInt(Pair::getFirst));
            groupFirstRoutes(level, lineID);
            for (List<Pair<Integer, Integer>> g : (List<List<Pair<Integer, Integer>>>) schedGroups.invoke(null, byRoute)) {
                if (!g.isEmpty() && g.get(0).getFirst() == routeID) {
                    List<Pair<Integer, Pair<Integer, Integer>>> st = (List<Pair<Integer, Pair<Integer, Integer>>>) schedStopTimes.invoke(null, g, routes, day, minute);
                    if (st != null && !st.isEmpty()) {
                        return Math.floorMod(st.get(st.size() - 1).getSecond().getSecond(), 1440);
                    }
                }
            }
        } catch (Throwable t) {
            // не критично
        }
        return -1;
    }

    /** Обновляет список выездов выбранного рейса (which = 1 или 2) на выбранный день. */
    public static void editorRefresh(Object screen, int which) {
        relabelRoutes(screen);
        try {
            int day = editDay(screen);
            int route = editRoute(screen, which);
            List<TimetableRowLayout> rows = new ArrayList<>();
            if (route != -1) {
                List<Integer> mins = new ArrayList<>();
                for (Pair<Integer, List<Pair<Integer, Integer>>> v : editSchedule(screen).timeline) {
                    for (Pair<Integer, Integer> tr : v.getSecond()) {
                        if (Math.floorDiv(tr.getFirst(), 1440) == day && tr.getSecond() == route) {
                            mins.add(Math.floorMod(tr.getFirst(), 1440));
                        }
                    }
                }
                java.util.Collections.sort(mins);
                Level lv = (Level) screenField(screen, "level");
                int lineIdOfScreen = (Integer) screenField(screen, "lineID");
                for (int m : mins) {
                    int endMin = tripEnd(lv, lineIdOfScreen, day, m, route);
                    Object row = editorRow(screen, fmtMin(m) + "-" + fmtMin(endMin < 0 ? m : endMin), which);
                    if (row != null) {
                        rows.add((TimetableRowLayout) row);
                    }
                }
            }
            LayoutListBox box = (LayoutListBox) screenField(screen, which == 1 ? "LBTimetable1" : "LBTimetable2");
            box.setRows(rows);
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: editorRefresh " + t);
        }
    }

    /** Строка списка: время выезда, показ того же времени справа и кнопка удаления. */
    public static Object editorRow(Object screen, String time, int which) {
        try {
            Level level = (Level) screenField(screen, "level");
            final String[] cur = {time.substring(0, 5)};
            int width = 150;
            LayoutListBox box = (LayoutListBox) screenField(screen, which == 1 ? "LBTimetable1" : "LBTimetable2");
            try {
                Object w = box.getClass().getMethod("m_5711_").invoke(box);
                if (w instanceof Integer && (Integer) w > 20) {
                    width = (Integer) w;
                }
            } catch (Throwable ignored) {
                // ширина по умолчанию
            }
            IconButton delete = new IconButton(0, 0, 12, "icon_delete", (e) -> {
                editorDelete(screen, which, cur[0]);
                editorRefresh(screen, 1);
                editorRefresh(screen, 2);
            });
            TimeBox start = new TimeBox(0, 0, 50, 12, cur[0], level, (e) -> {
                if (e instanceof TimeBox) {
                    String nv = ((TimeBox) e).getValue();
                    if (parseHM(nv) >= 0 && !nv.equals(cur[0])) {
                        if (editorMove(screen, which, cur[0], nv)) {
                            cur[0] = nv;
                        }
                    }
                }
            });
            final String endText = time.length() >= 11 ? time.substring(6, 11) : cur[0];
            TimeBox end = new TimeBox(0, 0, 50, 12, endText, level, (e) -> { });
            return new TimetableRowLayout(width, start, end, delete);
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: editorRow " + t);
            return null;
        }
    }

    private static Pair<Integer, Integer> findItem(VDSchedule sc, int day, int route, int minute, List<Pair<Integer, Integer>>[] ownerOut) {
        for (Pair<Integer, List<Pair<Integer, Integer>>> v : sc.timeline) {
            for (Pair<Integer, Integer> tr : v.getSecond()) {
                if (Math.floorDiv(tr.getFirst(), 1440) == day && tr.getSecond() == route && Math.floorMod(tr.getFirst(), 1440) == minute) {
                    ownerOut[0] = v.getSecond();
                    return tr;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void editorDelete(Object screen, int which, String time) {
        try {
            VDSchedule sc = editSchedule(screen);
            List<Pair<Integer, Integer>>[] owner = new List[1];
            Pair<Integer, Integer> it = findItem(sc, editDay(screen), editRoute(screen, which), parseHM(time), owner);
            if (it != null) {
                owner[0].remove(it);
                editSave(screen, sc);
            }
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: editorDelete " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean editorMove(Object screen, int which, String oldTime, String newTime) {
        try {
            VDSchedule sc = editSchedule(screen);
            int day = editDay(screen);
            int route = editRoute(screen, which);
            List<Pair<Integer, Integer>>[] owner = new List[1];
            Pair<Integer, Integer> it = findItem(sc, day, route, parseHM(oldTime), owner);
            if (it == null) {
                return false;
            }
            owner[0].remove(it);
            owner[0].add(Pair.of(day * 1440 + parseHM(newTime), route));
            owner[0].sort(java.util.Comparator.comparingInt(Pair::getFirst));
            editSave(screen, sc);
            synchronized (EDIT_DIRTY) {
                EDIT_DIRTY.put(screen, System.currentTimeMillis());
            }
            return true;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: editorMove " + t);
            return false;
        }
    }

    /** Кнопка добавления: новый выезд через час после последнего на этот день (или в 12:00). */
    public static void editorAdd(Object screen, int which) {
        try {
            int route = editRoute(screen, which);
            if (route == -1) {
                return;
            }
            VDSchedule sc = editSchedule(screen);
            int day = editDay(screen);
            int last = -1;
            for (Pair<Integer, List<Pair<Integer, Integer>>> v : sc.timeline) {
                for (Pair<Integer, Integer> tr : v.getSecond()) {
                    if (Math.floorDiv(tr.getFirst(), 1440) == day && tr.getSecond() == route) {
                        last = Math.max(last, Math.floorMod(tr.getFirst(), 1440));
                    }
                }
            }
            int minute = last < 0 ? 12 * 60 : Math.min(last + 60, 23 * 60 + 59);
            List<Pair<Integer, Integer>> items = autoVehicleList(sc);
            items.add(Pair.of(day * 1440 + minute, route));
            items.sort(java.util.Comparator.comparingInt(Pair::getFirst));
            editSave(screen, sc);
            editorRefresh(screen, 1);
            editorRefresh(screen, 2);
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: editorAdd " + t);
        }
    }

    private static java.lang.reflect.Method dayOptionsOrig;
    private static java.lang.reflect.Method arrivingOrig;

    /** Дни недели с выездами для ручной таблицы: по одному варианту на день (ключи 1..7), иначе стандартные группы дней. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static java.util.HashMap dayOptions(Level level, int lineID) {
        try {
            String ld = VDStorageHandler.get(level, lineID, VDMemoryIDs.TRANSPORT_LINE);
            if (ld != null && !ld.isEmpty() && new VDTransportLine(ld).scheduleType == 1) {
                VDSchedule sc = scheduleOf(level, lineID, null);
                if (hasManualTimes(sc)) {
                    java.util.HashMap<Integer, List<Integer>> m = new java.util.HashMap<>();
                    for (Pair<Integer, List<Pair<Integer, Integer>>> v : sc.timeline) {
                        for (Pair<Integer, Integer> tr : v.getSecond()) {
                            int d = Math.floorDiv(tr.getFirst(), 1440);
                            if (d >= 0 && d <= 6 && !m.containsKey(d + 1)) {
                                m.put(d + 1, new ArrayList<>(java.util.Collections.singletonList(d)));
                            }
                        }
                    }
                    return m;
                }
            }
            if (dayOptionsOrig == null) {
                dayOptionsOrig = Class.forName("com.rinventor.ptm2.dimension.virtual.util.ScheduleUtils")
                    .getMethod("ptmDayOptions", Level.class, int.class);
            }
            return (java.util.HashMap) dayOptionsOrig.invoke(null, level, lineID);
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: dayOptions " + t);
            return new java.util.HashMap();
        }
    }

    /**
     * Табло остановки: автобус «прибывает» на платформу, только если эта платформа его ближайшая остановка. Раньше
     * автобус, который просто проезжал по платформе (из депо к первой остановке или в депо после рейса), на несколько
     * секунд попадал в табло как прибывший.
     */
    public static boolean arriving(Level level, VDTrackableVehicle vehicle, int platformID) {
        try {
            if (vehicle.nextPlatformID != platformID) {
                return false;
            }
            if (arrivingOrig == null) {
                arrivingOrig = Class.forName("com.rinventor.ptm2.dimension.virtual.util.Departures")
                    .getDeclaredMethod("ptmArriving", Level.class, VDTrackableVehicle.class, int.class);
                arrivingOrig.setAccessible(true);
            }
            return (Boolean) arrivingOrig.invoke(null, level, vehicle, platformID);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final Map<Object, Long> EDIT_DIRTY = new java.util.WeakHashMap<>();

    /** Тик экрана редактирования: через 1,5 секунды после правки времени списки перестраиваются и сортируются. */
    public static void editorTick(Object screen) {
        try {
            Long ts;
            synchronized (EDIT_DIRTY) {
                ts = EDIT_DIRTY.get(screen);
                if (ts == null || System.currentTimeMillis() - ts < 1500L) {
                    return;
                }
                EDIT_DIRTY.remove(screen);
            }
            editorRefresh(screen, 1);
            editorRefresh(screen, 2);
        } catch (Throwable t) {
            // не критично
        }
    }

    private static final class CachedList {
        final long time;
        final List<?> list;

        CachedList(long time, List<?> list) {
            this.time = time;
            this.list = list;
        }
    }

    private static final Map<String, CachedList> SCHEDULE_CACHE = new ConcurrentHashMap<>();
    private static java.lang.reflect.Method scheduleOrig;

    /**
     * Кэш расписания линии на 5 секунд. Мод строит расписание заново при каждом вызове (на каждом тике для каждой
     * линии), а у больших линий в нём тысячи записей. Новые настройки применяются с задержкой не больше 5 секунд.
     */
    @SuppressWarnings("unchecked")
    public static List cachedEntries(Level level, int lineID, int day) {
        try {
            String key = System.identityHashCode(level) + ":" + lineID + ":" + day;
            long now = System.currentTimeMillis();
            CachedList c = SCHEDULE_CACHE.get(key);
            if (c != null && now - c.time < 5000L) {
                return new ArrayList<>(c.list);
            }
            List<?> manual = manualEntries(level, lineID, day);
            if (manual != null) {
                SCHEDULE_CACHE.put(key, new CachedList(now, manual));
                return new ArrayList<>(manual);
            }
            if (scheduleOrig == null) {
                scheduleOrig = Class.forName("com.rinventor.ptm2.dimension.virtual.util.ScheduleUtils")
                    .getMethod("ptmScheduleEntries", Level.class, int.class, int.class);
            }
            List<?> r = (List<?>) scheduleOrig.invoke(null, level, lineID, day);
            if (SCHEDULE_CACHE.size() > 300) {
                SCHEDULE_CACHE.clear();
            }
            SCHEDULE_CACHE.put(key, new CachedList(now, r));
            return new ArrayList<>(r);
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: cachedEntries " + t);
            return new ArrayList();
        }
    }

    /** Краткие настройки расписания линии для сводки в логе: время, интервалы, режим 24/7 и ночные дни. */
    private static String scheduleBrief(Level level, int lineId) {
        try {
            for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.SCHEDULE).entrySet()) {
                VDSchedule sc = new VDSchedule(e.getValue());
                if (sc.lineID == lineId) {
                    return " [" + sc.firstAfter + "-" + sc.lastBefore + ", \u0438\u043d\u0442\u0435\u0440\u0432\u0430\u043b\u044b " + sc.interval1 + "/" + sc.interval2 + "/" + sc.interval3
                        + ", \u043f\u0438\u043a " + sc.rushHourInterval + ", \u043d\u043e\u0447\u044c " + sc.nightInterval + ", 24/7=" + (sc.operating24 ? "\u0434\u0430" : "\u043d\u0435\u0442")
                        + ", \u043d\u043e\u0447\u043d\u044b\u0435 \u0434\u043d\u0438 " + sc.nightOpDays + "]";
                }
            }
        } catch (Throwable t) {
            // не критично
        }
        return "";
    }

    private static long lastSummary = 0L;

    /** Раз в 2 минуты пишет в лог сводку по линиям: время, тип расписания, число отправлений, ближайшее, автобусов в игре. */
    private static void summaryLog(Level level) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastSummary < 120000L) {
            return;
        }
        lastSummary = nowMs;
        try {
            int hour = TimeHelper.hour(level);
            int minute = TimeHelper.minute(level);
            int day = Math.floorMod(TimeHelper.weekday(level) - 1, 7);
            int nowMin = hour * 60 + minute;
            Map<String, String> stored = VDStorageHandler.getStrs(level, VDMemoryIDs.TRACKABLE_VEHICLE);
            Map<Integer, Integer> perLine = new HashMap<>();
            for (String sv : stored.values()) {
                try {
                    VDTrackableVehicle tv = new VDTrackableVehicle(sv);
                    if (tv.isTransport() && tv.lineID >= 0) {
                        perLine.merge(tv.lineID, 1, Integer::sum);
                    }
                } catch (Exception ignored) {
                    // пропускаем повреждённые записи
                }
            }
            StringBuilder b = new StringBuilder("PTM2 \u043b\u0438\u043d\u0438\u0438: \u0432\u0440\u0435\u043c\u044f " + String.format("%02d:%02d", hour, minute) + " \u0434\u0435\u043d\u044c " + day
                + (TimeHelper.isTimeStopped(level) ? " (\u0432\u0440\u0435\u043c\u044f \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d\u043e)" : ""));
            for (Map.Entry<Integer, String> le : VDStorageHandler.getAll(level, VDMemoryIDs.TRANSPORT_LINE).entrySet()) {
                VDTransportLine line = new VDTransportLine(le.getValue());
                List<Pair<Integer, Pair<Integer, String>>> sched = ScheduleUtils.getSimpleScheduleEntriesFor(level, le.getKey(), day);
                int next = -1;
                int count = sched == null ? 0 : sched.size();
                if (sched != null) {
                    for (Pair<Integer, Pair<Integer, String>> e : sched) {
                        int tm = (int) TimeHelper.parseTime(e.getSecond().getSecond());
                        if (tm >= nowMin && (next < 0 || tm < next)) {
                            next = tm;
                        }
                    }
                }
                b.append("; \u043b\u0438\u043d\u0438\u044f ").append(le.getKey()).append(" (").append(line.number).append(") \u0442\u0438\u043f ").append(line.scheduleType)
                    .append(", \u0437\u0430\u043f\u0438\u0441\u0435\u0439 ").append(count)
                    .append(", \u0431\u043b\u0438\u0436\u0430\u0439\u0448\u0435\u0435 ").append(next >= 0 ? String.format("%02d:%02d", next / 60, next % 60) : "-")
                    .append(", \u0432 \u0438\u0433\u0440\u0435 ").append(perLine.getOrDefault(le.getKey(), 0))
                    .append(line.suspended ? ", \u043f\u0440\u0438\u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d\u0430" : "")
                    .append(scheduleBrief(level, le.getKey()));
            }
            PTMLogger.info(b.toString());
        } catch (Throwable t) {
            // не критично
        }
    }

    private static final Map<String, double[]> VEH_WATCH = new HashMap<>();
    private static long lastVehWatch = 0L;

    /**
     * Раз в 5 секунд просматривает все записанные модом автобусы: если автобус с линией стоит на месте дольше
     * полутора минут и не ждёт отправления на остановке по расписанию, пишет его состояние в лог (раз в 3 минуты).
     */
    private static void watchVehicles(Level level) {
        long now = System.currentTimeMillis();
        if (now - lastVehWatch < 5000L) {
            return;
        }
        lastVehWatch = now;
        try {
            Map<String, String> stored = VDStorageHandler.getStrs(level, VDMemoryIDs.TRACKABLE_VEHICLE);
            VEH_WATCH.keySet().retainAll(stored.keySet());
            for (Map.Entry<String, String> e : stored.entrySet()) {
                VDTrackableVehicle v;
                try {
                    v = new VDTrackableVehicle(e.getValue());
                } catch (Exception ex) {
                    continue;
                }
                if (!v.isTransport() || v.lineID < 0 || v.position == null) {
                    continue;
                }
                double x = vc(v.position, 0);
                double z = vc(v.position, 2);
                double[] rec = VEH_WATCH.get(e.getKey());
                if (rec == null || Math.abs(rec[0] - x) > 2.0D || Math.abs(rec[1] - z) > 2.0D) {
                    VEH_WATCH.put(e.getKey(), new double[] {x, z, now, 0.0D});
                    continue;
                }
                boolean waitingAtStop = v.stopTime > 0L && v.timeToLeave > TimeHelper.now() - 20000L;
                if (!waitingAtStop && now - (long) rec[2] > 90000L && now - (long) rec[3] > 180000L) {
                    rec[3] = now;
                    PTMLogger.info("PTM2 \u0437\u0430\u0432\u0438\u0441: \u043b\u0438\u043d\u0438\u044f " + v.lineID + " \u0440\u0435\u0439\u0441 " + v.routeID + " \u043d\u0430 " + (int) x + " " + (int) z
                        + " " + (now - (long) rec[2]) / 1000L + " \u0441, " + vehicleDiag(level, v));
                }
            }
        } catch (Throwable t) {
            // не критично
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
        if (transport instanceof Transport) {
            return ((Transport) transport).getLineID();
        }
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
     * Посадка игрока в автобус на линии (вызывается из обёртки Vehicle.startRiding).
     * Посадка всегда разрешена. Пока проезд не оплачен, игрок числится «неоплатившим»: у него есть время
     * до отправления, чтобы приложить билет, транспортную карту, банковскую карту или смартфон к валидатору.
     * Перед отправлением автобуса проезд спишется сам (транспортная карта, затем счёт игрока),
     * а если оплатить нечем, игрок («заяц») высаживается обратно на остановку (см. fareTick).
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
            VDTransportLine line = lineOf(main, level);
            if (line == null || line.free) {
                return true;
            }
            Player player = (Player) entity;
            // Сесть можно только на остановке с открытыми дверями
            if (HULL_ENABLED && !doorsOpenAndStopped(main)) {
                PTMChat.msgNearest("\u0414\u0432\u0435\u0440\u0438 \u0437\u0430\u043a\u0440\u044b\u0442\u044b", "red", level,
                    PTMEntity.getX(player), PTMEntity.getY(player), PTMEntity.getZ(player));
                return false;
            }
            String name = PTMEntity.getName(player);
            String key = name + "@" + System.identityHashCode(main);
            long now = System.currentTimeMillis();
            Long last = PAID.get(key);
            if (last != null && now - last < TRANSFER_WINDOW_MS) {
                PENDING.remove(name);
                return true;
            }
            String companyName = companyOf(line, level);
            if (companyName == null) {
                return true;
            }
            double price = ((WorldSavedData) PTM.DATA.get(level)).PRC_TICKET;
            if (AUTO_PAY_AT_SEAT && autoPay(player, key, now, companyName, price, level)) {
                PENDING.remove(name);
                return true;
            }
            registerFare(main, player, level, true);
            return true;
        } catch (Throwable t) {
            PTMLogger.error(String.valueOf(t));
            return true;
        }
    }

    // ======================================================================
    //  Валидаторы и «зайцы»
    // ======================================================================

    /**
     * Берёт игрока на контроль оплаты (если линия платная и проезд ещё не оплачен). Возвращает true, если игрок
     * числится неоплатившим. Используется и при посадке на место, и когда игрок зашёл в салон пешком.
     */
    static boolean registerFare(Vehicle main, Player player, Level level, boolean notify) {
        VDTransportLine line = lineOf(main, level);
        if (line == null || line.free) {
            return false;
        }
        String name = PTMEntity.getName(player);
        String key = name + "@" + System.identityHashCode(main);
        long now = System.currentTimeMillis();
        Long last = PAID.get(key);
        if (last != null && now - last < TRANSFER_WINDOW_MS) {
            PENDING.remove(name);
            return false;
        }
        String companyName = companyOf(line, level);
        if (companyName == null) {
            return false;
        }
        Pending existing = PENDING.get(name);
        if (existing != null && existing.busKey == System.identityHashCode(main)) {
            return true;
        }
        Pending p = new Pending();
        p.busKey = System.identityHashCode(main);
        p.x = PTMEntity.getX(player);
        p.y = PTMEntity.getY(player);
        p.z = PTMEntity.getZ(player);
        p.yaw = (float) PTMEntity.getYaw(player);
        p.price = ((WorldSavedData) PTM.DATA.get(level)).PRC_TICKET;
        p.company = companyName;
        p.since = now;
        PENDING.put(name, p);
        PTMLogger.info("PTM2 \u043e\u043f\u043b\u0430\u0442\u0430: " + name + " \u0431\u0435\u0437 \u043e\u043f\u043b\u0430\u0442\u044b \u0432 \u0430\u0432\u0442\u043e\u0431\u0443\u0441\u0435");
        if (notify) {
            PTMChat.msgNearest("\u041e\u043f\u043b\u0430\u0442\u0438\u0442\u0435 \u043f\u0440\u043e\u0435\u0437\u0434 \u0432\u0430\u043b\u0438\u0434\u0430\u0442\u043e\u0440\u043e\u043c: \u0431\u0438\u043b\u0435\u0442, \u043a\u0430\u0440\u0442\u0430 \u0438\u043b\u0438 \u0442\u0435\u043b\u0435\u0444\u043e\u043d. \u041f\u0435\u0440\u0435\u0434 \u043e\u0442\u043f\u0440\u0430\u0432\u043b\u0435\u043d\u0438\u0435\u043c \u0441\u043f\u0438\u0448\u0435\u0442\u0441\u044f \u0430\u0432\u0442\u043e\u043c\u0430\u0442\u0438\u0447\u0435\u0441\u043a\u0438", "yellow", level,
                p.x, p.y, p.z);
        }
        return true;
    }

    /** true: списывать сразу при посадке на место; false: оплата валидатором, автоматически перед отправлением. */
    static final boolean AUTO_PAY_AT_SEAT = false;
    /** Скорость автобуса, с которой считаем, что он отправился. */
    static final float DEPART_SPEED = 1.5F;

    static final class Pending {
        int busKey;
        double x;
        double y;
        double z;
        float yaw;
        double price;
        String company;
        long since;
    }

    /** Игроки, сидящие в автобусе без оплаты: имя -> данные. */
    static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();

    private static VDTransportLine lineOf(Vehicle main, Level level) {
        int lineID = readLineId(main);
        if (lineID == -1) {
            return null;
        }
        String raw = VDStorageHandler.get(level, lineID, VDMemoryIDs.TRANSPORT_LINE);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return new VDTransportLine(raw);
    }

    private static String companyOf(VDTransportLine line, Level level) {
        String raw = VDStorageHandler.get(level, line.companyID, VDMemoryIDs.TRANSPORT_COMPANY);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return new com.rinventor.ptm2.dimension.virtual.types.VDTransportCompany(raw).name;
    }

    /** Вызов метода Minecraft по его runtime-имени (SRG): в PtmFixes прямые вызовы MC-методов недоступны. */
    private static Object srg(Object o, String name, Object... args) throws ReflectiveOperationException {
        // На классах мода (Vehicle) getMethods() тянет клиентские типы (GuiGraphics) и на сервере падает,
        // поэтому ищем метод в ближайшем предке из net.minecraft.
        Class<?> c = o.getClass();
        while (c != null && !c.getName().startsWith("net.minecraft.")) {
            c = c.getSuperclass();
        }
        if (c == null) {
            c = o.getClass();
        }
        for (java.lang.reflect.Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                return m.invoke(o, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void ticketSound(Level level, double x, double y, double z, boolean ok) {
        try {
            PTMWorld.playSoundB((ok ? ModSounds.TICKET_MACHINE_SUCCESS : ModSounds.TICKET_MACHINE_FAIL).get(), level, x, y, z);
        } catch (Throwable t) {
            // звук не критичен
        }
    }

    /** Оплата с транспортной карты, иначе со счёта игрока. */
    private static boolean autoPay(Player player, String key, long now, String company, double price, Level level) {
        double x = PTMEntity.getX(player);
        double y = PTMEntity.getY(player);
        double z = PTMEntity.getZ(player);
        if (TransportCard.useCard(player, 1)) {
            PAID.put(key, now);
            PTMChat.msgNearest("\u041f\u0440\u043e\u0435\u0437\u0434 \u043e\u043f\u043b\u0430\u0447\u0435\u043d \u0442\u0440\u0430\u043d\u0441\u043f\u043e\u0440\u0442\u043d\u043e\u0439 \u043a\u0430\u0440\u0442\u043e\u0439", "green", level, x, y, z);
            return true;
        }
        return payBalance(player, key, now, company, price, level);
    }

    private static boolean payBalance(Player player, String key, long now, String company, double price, Level level) {
        double x = PTMEntity.getX(player);
        double y = PTMEntity.getY(player);
        double z = PTMEntity.getZ(player);
        if (PaymentUtils.hasIndefiniteMoney(player) || PaymentUtils.getBalance(player) >= price) {
            PaymentUtils.transactionP2C(player, company, price, "\u041f\u0440\u043e\u0435\u0437\u0434");
            PAID.put(key, now);
            PTMChat.msgNearest("\u0421\u043f\u0438\u0441\u0430\u043d\u043e \u0437\u0430 \u043f\u0440\u043e\u0435\u0437\u0434: " + price, "green", level, x, y, z);
            return true;
        }
        return false;
    }

    /** Погасить бумажный билет в руке (как на турникете): один билет -> использованный. */
    private static boolean consumeTicket(Player player) {
        try {
            Object stack = srg(player, "m_21205_");
            int count = (Integer) srg(stack, "m_41613_");
            if (count > 1) {
                srg(stack, "m_41764_", count - 1);
                PTMEntity.giveItem(ModItems.TICKET_USED.get(), player);
            } else {
                PTMEntity.setItemInMainHand(ModItems.TICKET_USED.get(), player);
            }
            return true;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: не удалось погасить билет: " + t);
            return false;
        }
    }

    /** Попал ли взгляд игрока в валидатор. */
    private static boolean rayHits(Player player, ValidatorLocation val) {
        try {
            double eye = ((Number) srg(player, "m_20192_")).doubleValue();
            double ex = PTMEntity.getX(player);
            double ey = PTMEntity.getY(player) + eye;
            double ez = PTMEntity.getZ(player);
            double yaw = Math.toRadians(PTMEntity.getYaw(player));
            double pitch = Math.toRadians(((Number) srg(player, "m_146909_")).doubleValue());
            double lx = -Math.sin(yaw) * Math.cos(pitch);
            double ly = -Math.sin(pitch);
            double lz = Math.cos(yaw) * Math.cos(pitch);
            double vx = val.getX() - ex;
            double vy = val.getY() - ey;
            double vz = val.getZ() - ez;
            double t = vx * lx + vy * ly + vz * lz;
            if (t < 0.0D || t > 4.0D) {
                return false;
            }
            double dx = vx - lx * t;
            double dy = vy - ly * t;
            double dz = vz - lz * t;
            return dx * dx + dy * dy + dz * dz < 0.5D * 0.5D;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Обёртка Vehicle.interactionBy: правый клик по валидатору. Возвращает кнопку мыши,
     * с которой надо продолжить обычную обработку (-1, если клик по валидатору уже обработан здесь).
     */
    public static int validatorClick(Vehicle v, Player player, int button) {
        try {
            if (button != 1 || PTMWorld.isClient(v)) {
                return button;
            }
            Vehicle main = v.getMainVehicle(v);
            if (main == null) {
                main = v;
            }
            if (!(main instanceof Transport)) {
                return button;
            }
            boolean hit = false;
            for (int i = 0; i < main.totalSections && !hit; i++) {
                Vehicle sec = main.getSection(i);
                if (sec == null || !VehicleUtils.isPlayerInVehiclesRotatedAABB(player, sec)) {
                    continue;
                }
                for (ValidatorLocation val : sec.getValidators()) {
                    if (rayHits(player, val)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) {
                return button;
            }
            Level level = (Level) srg(player, "m_9236_");
            payAtValidator(main, player, level);
            return -1;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: ошибка валидатора: " + t);
            return button;
        }
    }

    private static void payAtValidator(Vehicle main, Player player, Level level) {
        double x = PTMEntity.getX(player);
        double y = PTMEntity.getY(player);
        double z = PTMEntity.getZ(player);
        VDTransportLine line = lineOf(main, level);
        if (line == null) {
            PTMChat.msgNearest("\u042d\u0442\u043e\u0442 \u0442\u0440\u0430\u043d\u0441\u043f\u043e\u0440\u0442 \u043d\u0435 \u043d\u0430 \u043b\u0438\u043d\u0438\u0438", "red", level, x, y, z);
            return;
        }
        if (line.free) {
            PTMChat.msgNearest("\u041f\u0440\u043e\u0435\u0437\u0434 \u0431\u0435\u0441\u043f\u043b\u0430\u0442\u043d\u044b\u0439", "green", level, x, y, z);
            return;
        }
        String name = PTMEntity.getName(player);
        String key = name + "@" + System.identityHashCode(main);
        long now = System.currentTimeMillis();
        Long last = PAID.get(key);
        if (last != null && now - last < TRANSFER_WINDOW_MS) {
            PENDING.remove(name);
            PTMChat.msgNearest("\u041f\u0440\u043e\u0435\u0437\u0434 \u0443\u0436\u0435 \u043e\u043f\u043b\u0430\u0447\u0435\u043d", "green", level, x, y, z);
            return;
        }
        String company = companyOf(line, level);
        if (company == null) {
            return;
        }
        double price = ((WorldSavedData) PTM.DATA.get(level)).PRC_TICKET;
        net.minecraft.world.item.Item hand = PTMEntity.itemInHand(player);
        boolean ok;
        if (hand == ModItems.TICKET_VALID.get()) {
            ok = consumeTicket(player);
            if (ok) {
                PAID.put(key, now);
            }
        } else if (hand == ModItems.TICKET_NOT_VALID.get() || hand == ModItems.TICKET_USED.get()) {
            PTMChat.msgNearest("\u0411\u0438\u043b\u0435\u0442 \u043d\u0435\u0434\u0435\u0439\u0441\u0442\u0432\u0438\u0442\u0435\u043b\u0435\u043d", "red", level, x, y, z);
            ticketSound(level, x, y, z, false);
            return;
        } else if (hand == ModItems.TRANSPORT_CARD.get()) {
            ok = TransportCard.useCard(player, 1);
            if (ok) {
                PAID.put(key, now);
            }
        } else if (hand == ModItems.SMARTPHONE.get() || hand == ModItems.BANK_CARD.get()) {
            ok = payBalance(player, key, now, company, price, level);
        } else {
            PTMChat.msgNearest("\u041f\u0440\u0438\u043b\u043e\u0436\u0438\u0442\u0435 \u0431\u0438\u043b\u0435\u0442, \u043a\u0430\u0440\u0442\u0443 \u0438\u043b\u0438 \u0442\u0435\u043b\u0435\u0444\u043e\u043d \u043a \u0432\u0430\u043b\u0438\u0434\u0430\u0442\u043e\u0440\u0443", "yellow", level, x, y, z);
            return;
        }
        if (ok) {
            PENDING.remove(name);
            ticketSound(level, x, y, z, true);
            PTMChat.msgNearest("\u041f\u0440\u043e\u0435\u0437\u0434 \u043e\u043f\u043b\u0430\u0447\u0435\u043d", "green", level, x, y, z);
        } else {
            ticketSound(level, x, y, z, false);
            PTMChat.msgNearest("\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043e\u043f\u043b\u0430\u0442\u0438\u0442\u044c \u043f\u0440\u043e\u0435\u0437\u0434", "red", level, x, y, z);
        }
    }

    /**
     * Вызывается каждый тик у автобуса с ИИ-водителем. Когда автобус тронулся, все неоплатившие пассажиры:
     * сначала пробуем автоплатёж (транспортная карта, затем счёт), иначе высаживаем обратно на остановку.
     */
    static void fareTick(Vehicle v, Level level, long now, Drv d) {
        if (PENDING.isEmpty() || !(v instanceof Transport) || v.sectionID != 0 || now - d.fareCheck < 400L) {
            return;
        }
        d.fareCheck = now;
        if (Math.abs(v.speed) < DEPART_SPEED) {
            return;
        }
        int busKey = System.identityHashCode(v);
        for (Map.Entry<String, Pending> e : PENDING.entrySet()) {
            Pending p = e.getValue();
            if (p.busKey != busKey && p.busKey != 0) {
                if (now - p.since > 600000L) {
                    PENDING.remove(e.getKey());
                }
                continue;
            }
            try {
                Player player = PaymentUtils.getPlayer(level, e.getKey());
                if (player == null) {
                    PENDING.remove(e.getKey());
                    continue;
                }
                Vehicle sec = sectionOfPlayer(v, player);
                boolean riding = sec != null;
                if (sec == null) {
                    for (int i = 0; i < v.totalSections; i++) {
                        Vehicle cand = v.getSection(i);
                        if (cand != null && VehicleUtils.isPlayerInVehiclesRotatedAABB(player, cand)) {
                            sec = cand;
                            break;
                        }
                    }
                }
                if (sec == null) {
                    PTMLogger.info("PTM2 \u043e\u043f\u043b\u0430\u0442\u0430: " + e.getKey() + " \u0443\u0436\u0435 \u043d\u0435 \u0432 \u0430\u0432\u0442\u043e\u0431\u0443\u0441\u0435, \u043a\u043e\u043d\u0442\u0440\u043e\u043b\u044c \u0441\u043d\u044f\u0442");
                    PENDING.remove(e.getKey());
                    continue;
                }
                String key = e.getKey() + "@" + busKey;
                if (autoPay(player, key, now, p.company, p.price, level)) {
                    PENDING.remove(e.getKey());
                    continue;
                }
                PENDING.remove(e.getKey());
                PTMLogger.info("PTM2 \u043e\u043f\u043b\u0430\u0442\u0430: " + e.getKey() + " \u043d\u0435 \u043e\u043f\u043b\u0430\u0442\u0438\u043b, \u0432\u044b\u0441\u0430\u0436\u0438\u0432\u0430\u0435\u043c");
                eject(player, sec, p, level, riding);
            } catch (Throwable t) {
                PENDING.remove(e.getKey());
                PTMLogger.warn("PTM2 fixes: ошибка высадки безбилетника: " + t);
            }
        }
    }

    /** Секция автобуса, в которой игрок сидит (по getVehicle), или null. */
    private static Vehicle sectionOfPlayer(Vehicle root, Player player) {
        try {
            Object ve = srg(player, "m_20202_");
            if (ve instanceof Vehicle) {
                Vehicle sv = (Vehicle) ve;
                Vehicle m = sv.getMainVehicle(sv);
                if ((m == null ? sv : m) == root) {
                    return sv;
                }
            }
        } catch (Throwable t) {
            // нет - попробуем по габаритам
        }
        return null;
    }

    /** Высадить игрока из автобуса и вернуть на остановку, где он сел. */
    private static void eject(Player player, Vehicle sec, Pending p, Level level, boolean riding) throws ReflectiveOperationException {
        Vehicle root = sec.getMainVehicle(sec);
        ejectToPlatform(player, root == null ? sec : root, sec, riding, level);
        PTMChat.msgNearest("\u0412\u044b \u043d\u0435 \u043e\u043f\u043b\u0430\u0442\u0438\u043b\u0438 \u043f\u0440\u043e\u0435\u0437\u0434, \u0430\u0432\u0442\u043e\u0431\u0443\u0441 \u0443\u0435\u0445\u0430\u043b \u0431\u0435\u0437 \u0432\u0430\u0441", "red", level, p.x, p.y, p.z);
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
    private static final Map<Integer, double[]> REACH_POS = new ConcurrentHashMap<>();

    private static final Map<Integer, long[]> STAND_LOG = new ConcurrentHashMap<>();

    /** Любой автобус с ИИ, стоящий на месте больше минуты: пишем в лог рейс, место и состояние (раз в 120 секунд). */
    private static void standLog(Transport t, int key) {
        try {
            if (!t.ai || t.getLineID() < 0) {
                STAND_LOG.remove(key);
                return;
            }
            long now = System.currentTimeMillis();
            long x = (long) PTMEntity.getX(t);
            long z = (long) PTMEntity.getZ(t);
            long[] rec = STAND_LOG.get(key);
            if (rec == null || Math.abs(rec[0] - x) > 2L || Math.abs(rec[1] - z) > 2L) {
                if (STAND_LOG.size() > 500) {
                    STAND_LOG.clear();
                }
                STAND_LOG.put(key, new long[] {x, z, now, 0L});
                return;
            }
            if (now - rec[2] > 60000L && now - rec[3] > 120000L) {
                rec[3] = now;
                PTMLogger.info("PTM2 \u0441\u0442\u043e\u0438\u0442: \u043b\u0438\u043d\u0438\u044f " + t.getLineID() + " \u0440\u0435\u0439\u0441 " + t.getRouteID() + " \u043d\u0430 " + x + " " + z
                    + " " + (now - rec[2]) / 1000L + " \u0441, \u0434\u0432\u0435\u0440\u0438=" + (t.doorsOpen ? "\u043e\u0442\u043a\u0440" : "\u0437\u0430\u043a\u0440")
                    + stateInfo(t) + obstacleInfo(key));
            }
        } catch (Throwable th) {
            // не критично
        }
    }

    private static final Map<Integer, java.lang.ref.WeakReference<Vehicle>> LAST_OBS = new ConcurrentHashMap<>();

    private static final Map<Integer, long[]> WAIT_LOG = new ConcurrentHashMap<>();

    /** Если автобус стоит на месте больше минуты, пишем в лог причину (раз в 90 секунд на автобус). */
    private static void waitLog(VDTrackableVehicle v, Vehicle ve, double distance, int key, long now) {
        try {
            long x = (long) PTMEntity.getX(ve);
            long z = (long) PTMEntity.getZ(ve);
            long[] rec = WAIT_LOG.get(key);
            if (rec == null || Math.abs(rec[0] - x) > 2L || Math.abs(rec[1] - z) > 2L) {
                if (WAIT_LOG.size() > 500) {
                    WAIT_LOG.clear();
                }
                WAIT_LOG.put(key, new long[] {x, z, now, 0L});
                return;
            }
            if (now - rec[2] > 60000L && now - rec[3] > 90000L) {
                rec[3] = now;
                PTMLogger.info("PTM2 \u0436\u0434\u0451\u0442: \u043b\u0438\u043d\u0438\u044f " + v.lineID + " \u0440\u0435\u0439\u0441 " + v.routeID + " \u043f\u043b\u0430\u0442\u0444\u043e\u0440\u043c\u0430 " + v.nextPlatformID
                    + " \u043d\u0430 " + x + " " + z + " \u0441\u0442\u043e\u0438\u0442 " + (now - rec[2]) / 1000L + " \u0441, \u0434\u043e \u0446\u0435\u043b\u0438 " + (int) distance
                    + ", \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043a\u0430=" + (v.stopTime > 0L ? "\u0434\u0430" : "\u043d\u0435\u0442")
                    + ", \u0434\u043e \u043e\u0442\u043f\u0440. " + (v.timeToLeave > 0L ? String.valueOf((v.timeToLeave - now) / 1000L) : "-")
                    + " \u0441, \u0434\u0432\u0435\u0440\u0438=" + (v.doorsOpen ? "\u043e\u0442\u043a\u0440" : "\u0437\u0430\u043a\u0440") + stateInfo(ve) + obstacleInfo(key));
            }
        } catch (Throwable t) {
            // не критично
        }
    }

    /** Состояние автобуса: поворотник, ИИ, целевая скорость, остаток пути. */
    private static String stateInfo(Vehicle ve) {
        try {
            if (!(ve instanceof Transport)) {
                return "";
            }
            Transport t = (Transport) ve;
            double len = 0.0D;
            int lanes = 0;
            if (t.currentPath != null) {
                lanes = t.currentPath.size();
            }
            return ", \u043f\u043e\u0432\u043e\u0440\u043e\u0442\u043d\u0438\u043a=" + t.getIndicators() + ", ai=" + (t.ai ? "\u0434\u0430" : "\u043d\u0435\u0442")
                + ", \u0446\u0435\u043b\u0435\u0432\u0430\u044f \u0441\u043a\u043e\u0440\u043e\u0441\u0442\u044c=" + t.targetSpeed + ", \u043f\u043e\u043b\u043e\u0441 \u0432 \u043f\u0443\u0442\u0438=" + lanes
                + ", \u043f\u0440\u043e\u0439\u0434\u0435\u043d\u043e=" + (int) t.pathProgress + ", \u0430\u0432\u0430\u0440\u0438\u044f=" + (t.crashed ? "\u0434\u0430" : "\u043d\u0435\u0442");
        } catch (Throwable th) {
            return "";
        }
    }

    private static String obstacleInfo(int key) {
        try {
            java.lang.ref.WeakReference<Vehicle> ref = LAST_OBS.get(key);
            Vehicle o = ref == null ? null : ref.get();
            if (o == null) {
                return ", \u043f\u0435\u0440\u0435\u0434 \u043d\u0438\u043c \u043f\u043e\u043c\u0435\u0445 \u043d\u0435\u0442";
            }
            String extra = o instanceof Transport ? " \u0440\u0435\u0439\u0441 " + ((Transport) o).getRouteID() : "";
            return ", \u043f\u0435\u0440\u0435\u0434 \u043d\u0438\u043c " + o.getClass().getSimpleName() + extra + " \u043d\u0430 "
                + (long) PTMEntity.getX(o) + " " + (long) PTMEntity.getZ(o) + " v=" + o.speed;
        } catch (Throwable t) {
            return "";
        }
    }

    public static double reachFor(VDTrackableVehicle v, Vehicle ve, double distance) {
        try {
            if (ve == null) {
                return 2.0D;
            }
            int key = System.identityHashCode(ve);
            long now = System.currentTimeMillis();
            waitLog(v, ve, distance, key, now);
            // автобус уже внутри платформы (сюда попадаем только в этом случае). Приоритет остаётся за доездом
            // до расчётной точки (порог 2 блока), но если автобус не сдвигается с места больше 3 секунд
            // (упёрся в конец пути, ждёт автобус впереди; скорость в записи при этом может быть ненулевой),
            // он открывает двери там, где стоит.
            double x = PTMEntity.getX(ve);
            double z = PTMEntity.getZ(ve);
            double[] rec = REACH_POS.get(key);
            if (rec == null || Math.abs(rec[0] - x) > 1.0D || Math.abs(rec[1] - z) > 1.0D) {
                if (REACH_POS.size() > 500) {
                    REACH_POS.clear();
                }
                REACH_POS.put(key, new double[] {x, z, now});
            } else if (distance < 150.0D) {
                // автобус в соседней полосе (боковое смещение от точки остановки больше 2.5 блока) может просто ждать
                // окна для перестроения: там открываем двери не раньше чем через 15 секунд
                long need = 3000L;
                try {
                    double yaw = Math.toRadians(PTMEntity.getYaw(ve));
                    double dx = vc(v.destination, 0) - x;
                    double dz = vc(v.destination, 2) - z;
                    double lat = Math.abs(-dx * Math.cos(yaw) + dz * (-Math.sin(yaw)));
                    if (lat > 2.5D) {
                        need = 15000L;
                    }
                } catch (Throwable ignored) {
                    need = 3000L;
                }
                if (now - (long) rec[2] >= need) {
                    return 150.0D;
                }
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
                    + ", \u0434\u0435\u043f\u043e=" + depotShort(level, line)));

                int[] rec = recommendedBuses(sched);
                if (rec != null) {
                    entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO,
                        "\u0414\u0418\u0410\u0413: \u043b\u0438\u043d\u0438\u044f " + number + " \u0440\u0435\u043a\u043e\u043c\u0435\u043d\u0434\u0443\u0435\u0442\u0441\u044f \u0430\u0432\u0442\u043e\u0431\u0443\u0441\u043e\u0432: " + rec[0]
                        + " (\u0440\u0435\u0439\u0441\u043e\u0432 \u0437\u0430 \u0434\u0435\u043d\u044c " + rec[1] + ", \u043e\u0431\u043e\u0440\u043e\u0442 ~" + rec[2] + " \u043c\u0438\u043d, \u0438\u043d\u0442\u0435\u0440\u0432\u0430\u043b ~" + rec[3]
                        + " \u043c\u0438\u043d, \u0441\u0435\u0439\u0447\u0430\u0441 \u0432 \u0438\u0433\u0440\u0435 " + active + ")" + fleetInfo(level, lineId)));
                }
                if (resolveDepot(level, line) != null) {
                    entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO,
                        "\u0414\u0418\u0410\u0413: \u0434\u0435\u043f\u043e \u043b\u0438\u043d\u0438\u0438 " + number + ": " + depotDiag(level, lineId, line)));
                }
                int vshown = 0;
                for (VDTrackableVehicle v : vehicles) {
                    if (v.lineID == lineId && v.isTransport() && vshown < 3) {
                        vshown++;
                        entries.add(new VDOperationalData(companyID, OperationalSeverity.INFO,
                            "\u0414\u0418\u0410\u0413: \u0430\u0432\u0442\u043e\u0431\u0443\u0441 \u043b\u0438\u043d\u0438\u0438 " + number + ": " + vehicleDiag(level, v)));
                    }
                }

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
        boolean lastOrig;
        long fareCheck;
        long depotSince;
        long termCheck;
        long exitCheck;
        boolean exitHold;
        long exitHoldSince;
        /** Машина впереди просит освободить место позади: до этого времени можно отъезжать назад. */
        long chainUntil;
        /** Когда машина начала ждать у перекрёстка с занятым выездом (0 = не ждёт). */
        long boxSince;
        /** С какого момента отъезжающая назад машина ждёт, пока освободят место задние (0 = не ждёт). */
        long backWait;
        // --- планировщик перекрёстков
        long planAt;
        boolean planHold;
        int planHoldFor;
        long planHoldSince;
        long planFreeUntil;
        long planGrantUntil;
        double planStop = -1.0D;
        Pred pred;
        long predAt;
        boolean planCreep;
        boolean planCreepOn;
        float planSavedTarget;
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
                if (!red) {
                    long nowMs = System.currentTimeMillis();
                    if (boxBlocked(v, (Level) srg(v, "m_9236_"))) {
                        if (d.boxSince == 0L) {
                            d.boxSince = nowMs;
                        }
                        // не загораживаем перекрёсток: ждём у стоп-линии, но не дольше 15 секунд подряд
                        if (nowMs - d.boxSince < 15000L) {
                            return true;
                        }
                    } else {
                        d.boxSince = 0L;
                    }
                }
                return red;
            }
            d.boxSince = 0L;
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

    /**
     * За перекрёстком по ходу движения стоит машина (выезд занят): въезжать нельзя, иначе мы встанем посреди перекрёстка
     * и перекроем поперечное движение. Проверяется только прямое направление на ближайшие 8 блоков до перекрёстка.
     */
    private static boolean boxBlocked(Vehicle v, Level level) {
        if (level == null || Math.abs(v.speed) > 12.0F) {
            return false;
        }
        double yaw = Math.toRadians(PTMEntity.getYaw(v));
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double x = PTMEntity.getX(v);
        double z = PTMEntity.getZ(v);
        double half = Math.max(0.0F, v.length) / 2.0D;
        int entry = -1;
        for (int k = 2; k <= 8; k++) {
            if (inIntersectionAt(level, x + fx * (half + k), z + fz * (half + k))) {
                entry = k;
                break;
            }
        }
        if (entry < 0) {
            return false;
        }
        int exit = -1;
        for (int k = entry + 1; k <= entry + 24; k++) {
            if (!inIntersectionAt(level, x + fx * (half + k), z + fz * (half + k))) {
                exit = k;
                break;
            }
        }
        if (exit < 0) {
            return false;
        }
        double limit = half + exit + Math.max(0.0F, v.length) + 2.0D;
        for (Vehicle o : PTMEntity.getDifferentVehicles(limit + 4.0D, v)) {
            if (Math.abs(o.speed) >= 0.5F) {
                continue;
            }
            double dx = PTMEntity.getX(o) - x;
            double dz = PTMEntity.getZ(o) - z;
            double along = dx * fx + dz * fz;
            double lat = Math.abs(-dx * fz + dz * fx);
            if (along > half + entry && along < limit && lat < v.width / 2.0D + o.width / 2.0D + 0.8D) {
                return true;
            }
        }
        return false;
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
            d.lastOrig = orig;
            fareTick(v, level, now, d);
            if (v instanceof Transport && v.ai && !v.requiresTracks && inAnyDepot(level, PTMEntity.getX(v), PTMEntity.getZ(v))) {
                return depotMove(v, d, level, now);
            }
            d.depotSince = 0L;
            // Запланированное перестроение (автосвязка между полосами): остальные должны уступать
            if (v.currentPath != null && !v.currentPath.isEmpty() && !v.changingLanes) {
                var nl = VehicleUtils.nextLanes(v);
                VDTrafficLane curLane = nl == null ? null : nl.getFirst();
                if (curLane != null && curLane.merging) {
                    v.changingLanes = true;
                }
            }
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
            // Цепочка: машина впереди не может отъехать назад, потому что позади стоим мы.
            // Если позади свободно - отъезжаем первыми; если позади тоже стоят - просим их отъехать (запрос идёт по цепочке назад)
            if (!d.backing && now < d.chainUntil && still && d.stillSince > 0L && now - d.stillSince > 800L
                && !(v instanceof Transport && ((Transport) v).doorsOpen)) {
                if (spaceBehind(v, level)) {
                    d.backing = true;
                    d.backLeft = 5.0D;
                    d.chainUntil = 0L;
                    drvLog(d, now, "отъезжает назад, освобождая место передней машине " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v));
                } else {
                    markBehind(v, now);
                }
            }
            if (d.backing) {
                boolean space = spaceBehind(v, level);
                if (!space && d.backLeft > 0.0D && still) {
                    // сзади занято: просим задних отъехать и ждём до 3.5 с
                    markBehind(v, now);
                    if (d.backWait == 0L) {
                        d.backWait = now;
                    }
                    if (now - d.backWait < 3500L) {
                        return false;
                    }
                } else {
                    d.backWait = 0L;
                }
                if (d.backLeft <= 0.0D || !still || !space || !stepBack(v, 0.15D)) {
                    d.backing = false;
                    d.backWait = 0L;
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
            // Планировщик перекрёстка: заранее согласованный проезд (уступаем тому, кто должен проехать первым)
            if ((orig || d.forced) && planHold(v, d, level, now)) {
                d.blockedSince = 0L;
                d.firstBlocked = 0L;
                d.forced = false;
                d.candidate = false;
                // подползаем к линии на малой скорости или стоим у неё
                return d.planCreep;
            }
            if (orig) {
                d.blockedSince = 0L;
                d.firstBlocked = 0L;
                d.forced = false;
                d.candidate = false;
                d.failed = 0;
                // Физически упёрлись (оригинальный ИИ разрешает ехать, но машина стоит): уступаем, если мы младшие
                if (d.stillSince > 0L && now - d.stillSince >= STALL_MS && stallYield(v, d, level, now)) {
                    return false;
                }
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
                    && (od.blockedSince - bias(o) < d.blockedSince - bias(v)
                        || (od.blockedSince - bias(o) == d.blockedSince - bias(v) && System.identityHashCode(o) < System.identityHashCode(v)))) {
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

    // ======================================================================
    //  Планировщик перекрёстков: заранее согласованный проезд.
    //  Каждая машина, подъезжающая к перекрёстку, строит прогноз: по каким точкам и в какое время она пройдёт зону.
    //  Если её прогноз пересекается с прогнозом другой машины, по единым правилам определяется, кто проезжает первым:
    //  уже внутри перекрёстка, тот кому уже дали проезд, знаки (главная дорога / уступи / стоп), поворот через встречный поток,
    //  время подъезда (общественный транспорт получает запас), правило правой руки. Проигравший заранее сбавляет скорость
    //  и останавливается у границы перекрёстка, а когда пересечение исчезает, едет дальше.
    //  Обычный ИИ остаётся страховкой (физическая проверка, отъезд назад).
    // ======================================================================

    static final boolean PLAN_ENABLED = true;
    /** Как часто машина пересчитывает прогноз. */
    static final long PLAN_PERIOD_MS = 200L;
    /** Дольше этого планировщик не держит машину подряд (дальше решает обычный ИИ). */
    static final long PLAN_MAX_HOLD_MS = 4500L;
    static final double PLAN_STEP = 1.5D;
    static final int PLAN_POINTS = 26;
    /** Запас времени общественному транспорту при определении очереди проезда, секунд. */
    static final double PLAN_PUBLIC_BONUS = 2.0D;

    /** Прогноз проезда одной машины. Точки: {x, z, fx, fz}. */
    static final class Pred {
        double[][] pts;
        double[] t;
        int in = -1;
        int out = -1;
        boolean inside;
        double v0;
        double len;
        /** Угол поворота по пути, градусы: больше нуля - вправо, меньше - влево. */
        double turn;
        double hx;
        double hz;
    }

    /** Время (с), за которое машина со скоростью v0 (м/с) проедет d блоков, разгоняясь до 6 м/с. */
    private static double eta(double d, double v0) {
        if (d <= 0.0D) {
            return 0.0D;
        }
        double a = 2.0D;
        double vc = Math.max(v0, 6.0D);
        double da = (vc * vc - v0 * v0) / (2.0D * a);
        if (d <= da) {
            return (-v0 + Math.sqrt(v0 * v0 + 2.0D * a * d)) / a;
        }
        return (vc - v0) / a + (d - da) / vc;
    }

    /** Строит прогноз для машины. rest - считать, что она стартует с места (так прогнозируется ждущая машина). */
    private static Pred makePred(Vehicle x, Level level, boolean rest) {
        double[][] pa = pathAhead(x, PLAN_STEP, PLAN_POINTS);
        double ox = PTMEntity.getX(x);
        double oz = PTMEntity.getZ(x);
        double spd = Math.abs(x.speed) * 0.2778D;
        if (pa == null) {
            // Нет известного пути (машина игрока): едет прямо, если движется
            if (spd < 1.0D) {
                return null;
            }
            double[] h = headingOf(x);
            pa = new double[PLAN_POINTS][];
            for (int i = 0; i < PLAN_POINTS; i++) {
                pa[i] = new double[] {h[0] * i * PLAN_STEP, h[1] * i * PLAN_STEP, h[0], h[1]};
            }
        }
        Pred p = new Pred();
        int n = pa.length;
        p.pts = new double[n][];
        p.t = new double[n];
        p.v0 = rest ? 0.0D : spd;
        p.len = x.length * Math.max(1, x.totalSections);
        for (int i = 0; i < n; i++) {
            p.pts[i] = new double[] {ox + pa[i][0], oz + pa[i][1], pa[i][2], pa[i][3]};
            p.t[i] = eta(i * PLAN_STEP, p.v0);
            boolean zin = inIntersectionAt(level, p.pts[i][0], p.pts[i][1]);
            if (i == 0) {
                p.inside = zin;
            }
            if (p.in < 0) {
                if (zin) {
                    p.in = i;
                }
            } else if (p.out < 0 && !zin) {
                p.out = i;
            }
        }
        if (p.in >= 0 && p.out < 0) {
            p.out = n - 1;
        }
        int e = p.out >= 0 ? p.out : n - 1;
        double[] f0 = p.pts[p.in >= 0 ? p.in : 0];
        double[] f1 = p.pts[e];
        p.hx = f0[2];
        p.hz = f0[3];
        double cross = f0[2] * f1[3] - f0[3] * f1[2];
        double dot = f0[2] * f1[2] + f0[3] * f1[3];
        p.turn = Math.toDegrees(Math.atan2(cross, dot));
        return p;
    }

    private static Pred predOf(Vehicle o, Level level, long now) {
        Drv od = drv(o);
        if (od.pred != null && now - od.predAt < PLAN_PERIOD_MS) {
            return od.pred;
        }
        Pred p = makePred(o, level, od.planHold);
        od.pred = p;
        od.predAt = now;
        return p;
    }

    /** Пересекаются ли в пространстве и времени пути двух машин в зоне перекрёстка машины a (попутные направления не считаются). */
    private static boolean pathsConflict(Pred a, Pred b, Vehicle va, Vehicle vb) {
        if (a.in < 0) {
            return false;
        }
        double hlA = va.length / 2.0D + 0.4D;
        double hwA = va.width / 2.0D + 0.4D;
        double hlB = vb.length / 2.0D + 0.4D;
        double hwB = vb.width / 2.0D + 0.4D;
        double reach = hlA + hwA + hlB + hwB;
        double gap = 1.3D + (a.len + b.len) / 10.0D;
        int from = Math.max(0, a.in - 1);
        int to = Math.min(a.pts.length - 1, a.out + 1);
        for (int i = from; i <= to; i++) {
            double[] pi = a.pts[i];
            for (int k = 0; k < b.pts.length; k++) {
                double[] pk = b.pts[k];
                double dx = pi[0] - pk[0];
                double dz = pi[1] - pk[1];
                if (dx * dx + dz * dz > reach * reach) {
                    continue;
                }
                if (pi[2] * pk[2] + pi[3] * pk[3] > 0.7D) {
                    continue;
                }
                if (Math.abs(a.t[i] - b.t[k]) > gap) {
                    continue;
                }
                if (rectOverlap(pi[0], pi[1], pi[2], pi[3], hlA, hwA, pk[0], pk[1], pk[2], pk[3], hlB, hwB)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPublic(Vehicle x) {
        return x instanceof Transport;
    }

    /** Ранг знака приоритета: чем больше, тем выше приоритет. */
    private static int signRank(Vehicle x) {
        com.rinventor.ptm2.core.properties.SignTypes s = x.prioritySign;
        if (s == com.rinventor.ptm2.core.properties.SignTypes.STOP) {
            return 0;
        }
        if (s == com.rinventor.ptm2.core.properties.SignTypes.GIVE_WAY) {
            return 1;
        }
        if (s == com.rinventor.ptm2.core.properties.SignTypes.MAIN_ROAD) {
            return 3;
        }
        return 2;
    }

    /** Время до входа в зону перекрёстка (0, если уже внутри). */
    private static double etaIn(Pred p) {
        return p.inside || p.in < 0 ? 0.0D : p.t[p.in];
    }

    /**
     * Должна ли машина v уступить машине o (их прогнозы пересекаются)? Возвращает причину или null, если v проезжает первой.
     * Правила одинаковы для обеих машин, поэтому они приходят к согласованному решению.
     */
    private static String yieldReason(Vehicle v, Drv d, Pred me, Vehicle o, Drv od, Pred po, boolean leftTraffic, long now) {
        if (o.requiresTracks || o.vehicleType == VehicleTypes.TRAM) {
            return "трамвай";
        }
        if (po.inside && !me.inside) {
            return "уже на перекрёстке";
        }
        if (me.inside && !po.inside) {
            return null;
        }
        boolean oGrant = od != null && od.planGrantUntil > now;
        boolean meGrant = d.planGrantUntil > now;
        if (oGrant != meGrant) {
            return oGrant ? "ему уже дан проезд" : null;
        }
        int sm = signRank(v);
        int so = signRank(o);
        if (sm != so) {
            return sm < so ? "знак приоритета" : null;
        }
        double opp = me.hx * po.hx + me.hz * po.hz;
        boolean mAcross = leftTraffic ? me.turn > 35.0D : me.turn < -35.0D;
        boolean oAcross = leftTraffic ? po.turn > 35.0D : po.turn < -35.0D;
        if (opp < -0.7D && mAcross != oAcross) {
            return mAcross ? "поворот через встречный поток" : null;
        }
        double tMe = etaIn(me) - (isPublic(v) ? PLAN_PUBLIC_BONUS : 0.0D);
        double tO = etaIn(po) - (isPublic(o) ? PLAN_PUBLIC_BONUS : 0.0D);
        if (Math.abs(tMe - tO) > 1.2D) {
            return tMe > tO ? "он приедет раньше" : null;
        }
        if (Math.abs(opp) < 0.7D) {
            double yaw = Math.toRadians(PTMEntity.getYaw(v));
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            double dx = PTMEntity.getX(o) - PTMEntity.getX(v);
            double dz = PTMEntity.getZ(o) - PTMEntity.getZ(v);
            double side = dx * (-fz) + dz * fx;
            if (leftTraffic) {
                side = -side;
            }
            if (Math.abs(side) > 0.5D) {
                return side > 0.0D ? "помеха справа" : null;
            }
        }
        return System.identityHashCode(o) < System.identityHashCode(v) ? "очередность" : null;
    }

    /**
     * Планировщик: true - машине нужно ждать (тормозить и стоять у границы перекрёстка), чтобы пропустить другую машину.
     * Решение пересчитывается раз в PLAN_PERIOD_MS, между пересчётами действует прежнее.
     */
    private static boolean planHold(Vehicle v, Drv d, Level level, long now) {
        boolean r = planHold0(v, d, level, now);
        if (!r) {
            d.planCreep = false;
            if (d.planCreepOn) {
                // удержание снято: возвращаем целевую скорость, чтобы машина сразу поехала
                if (d.planSavedTarget > v.targetSpeed) {
                    v.targetSpeed = d.planSavedTarget;
                }
                d.planCreepOn = false;
            }
        }
        return r;
    }

    /** Подъезд к линии: на малой скорости машина подползает к стоп-линии (canMove=true с ограниченной целевой скоростью), а у линии встаёт. */
    private static void planApply(Vehicle v, Drv d) {
        double rem = d.planStop - v.pathProgress;
        double vms = Math.abs(v.speed) * 0.2778D;
        if (rem > 1.0D && vms < 3.5D) {
            double tgt = Math.max(5.0D, Math.min(12.0D, 3.6D * Math.sqrt(2.4D * (rem - 1.0D))));
            if (!d.planCreepOn) {
                d.planSavedTarget = v.targetSpeed;
                d.planCreepOn = true;
            }
            if (v.targetSpeed > tgt) {
                v.targetSpeed = (float) tgt;
            }
            d.planCreep = true;
        } else {
            d.planCreep = false;
            v.intersectionStopProgress = d.planStop;
        }
    }

    /**
     * Расстояние (блоков вдоль пути от центра машины) до стоп-линии или зебры, лежащей на пути до входа в перекрёсток;
     * -1, если разметки нет. Как и в оригинале, машина останавливается так, чтобы перед линией был запас в 1 блок.
     */
    private static double markingStop(Vehicle v, Level level, Pred me) {
        try {
            initRefl();
            if (!reflOk || me.in < 0) {
                return -1.0D;
            }
            int y = (int) Math.floor(PTMEntity.getY(v));
            int last = Math.min(me.pts.length - 1, me.in + 1);
            boolean moving = Math.abs(v.speed) > 0.5F;
            for (int i = moving ? 1 : 0; i <= last; i++) {
                double[] pt = me.pts[i];
                double sx = -pt[3];
                double sz = pt[2];
                for (int k = -2; k <= 2; k++) {
                    RoadMarkingTypes t = markingAt(level, (int) Math.floor(pt[0] + sx * k), y, (int) Math.floor(pt[1] + sz * k));
                    if (t == RoadMarkingTypes.STOP || t == RoadMarkingTypes.CROSSING) {
                        return i * PLAN_STEP;
                    }
                }
            }
        } catch (Throwable t) {
            // нет разметки - остановимся у границы зоны
        }
        return -1.0D;
    }

    private static boolean planHold0(Vehicle v, Drv d, Level level, long now) {
        if (!PLAN_ENABLED) {
            return false;
        }
        try {
            if (v.requiresTracks || v.crashed || v.handBrake || !v.ai || (v instanceof Transport && ((Transport) v).doorsOpen)) {
                d.planHold = false;
                d.planHoldSince = 0L;
                return false;
            }
            if (now < d.planFreeUntil) {
                return false;
            }
            if (now - d.planAt < PLAN_PERIOD_MS) {
                if (d.planHold && d.planStop >= 0.0D) {
                    planApply(v, d);
                }
                return d.planHold;
            }
            d.planAt = now;
            boolean wasHold = d.planHold;
            if (!wasHold && Math.abs(v.speed) < 3.0F) {
                // Стоим или ползём: подъезд к перекрёстку здесь решает обычный ИИ
                d.planHoldSince = 0L;
                d.planAt = now;
                return false;
            }
            d.planHold = false;
            d.planHoldFor = 0;
            d.planStop = -1.0D;
            if (VDUtils.enteringRoundabout(v)) {
                d.planHoldSince = 0L;
                return false;
            }
            Pred me = makePred(v, level, wasHold);
            if (me == null || me.in < 0) {
                d.planHoldSince = 0L;
                return false;
            }
            boolean leftTraffic = ((WorldSavedData) PTM.DATA.get(level)).LEFT_TRAFFIC;
            Vehicle winner = null;
            String reason = null;
            double winnerEta = 1.0E9D;
            for (Vehicle o : PTMEntity.getDifferentVehicles(70.0D, v)) {
                if (o.sectionID != 0) {
                    continue;
                }
                Drv od = DRV.get(System.identityHashCode(o));
                // Планировщик работает на подъезде: стоящих и ползущих в очереди решает обычный ИИ (иначе машины держат друг друга)
                if (Math.abs(o.speed) < 5.0F) {
                    continue;
                }
                if (od != null && now - od.seen < 2000L) {
                    if (od.planHold) {
                        continue;
                    }
                    if (!od.lastOrig && !od.forced) {
                        continue;
                    }
                }
                Pred po = predOf(o, level, now);
                if (po == null || po.in < 0) {
                    continue;
                }
                if (!pathsConflict(me, po, v, o)) {
                    continue;
                }
                String r = yieldReason(v, d, me, o, od, po, leftTraffic, now);
                if (r != null) {
                    double e = etaIn(po);
                    if (winner == null || e < winnerEta) {
                        winner = o;
                        reason = r;
                        winnerEta = e;
                    }
                }
            }
            double vms = Math.abs(v.speed) * 0.2778D;
            double bd = Math.max(1.5D, v.maxBrakeDeceleration * 0.8D);
            double stopDist = vms * vms / (2.0D * bd) + 2.0D;
            double line = me.inside ? 0.0D : Math.max(0.0D, me.in * PLAN_STEP - v.length / 2.0D - 1.2D);
            if (winner != null && !me.inside) {
                // Останавливаемся перед стоп-линией/зеброй, а не просто у границы зоны
                double mk = markingStop(v, level, me);
                if (mk >= 0.0D) {
                    line = Math.min(line, Math.max(0.0D, mk - v.length / 2.0D - 1.0D));
                }
            }
            if (winner == null || me.inside) {
                // Проезжаем первыми: закрепляем право, когда подъехали близко
                if ((me.inside || line < 16.0D) && (d.stillSince == 0L || now - d.stillSince < 1500L)) {
                    d.planGrantUntil = now + 5000L;
                }
                d.planHoldSince = 0L;
                return false;
            }
            if (!wasHold && line < stopDist - 1.0D) {
                // Остановиться до перекрёстка уже нельзя: проезжаем, остальные пропустят
                d.planGrantUntil = now + 3000L;
                d.planHoldSince = 0L;
                return false;
            }
            if (!wasHold && line > stopDist * 1.6D + 3.0D) {
                // Решение принято, но тормозить ещё рано: едем дальше, пока конфликт не пропадёт или не придёт время тормозить
                return false;
            }
            if (d.planHoldSince == 0L) {
                d.planHoldSince = now;
                drvLog(d, now, "планировщик: уступает (" + reason + ") " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v)
                    + (isPublic(v) ? " (транспорт)" : ""));
            } else if (now - d.planHoldSince > PLAN_MAX_HOLD_MS) {
                d.planFreeUntil = now + 15000L;
                d.planHoldSince = 0L;
                drvLog(d, now, "планировщик: ждал слишком долго, отпускаю " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v));
                return false;
            }
            d.planHold = true;
            d.planHoldFor = System.identityHashCode(winner);
            d.planStop = v.pathProgress + line;
            planApply(v, d);
            return true;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: ошибка планировщика перекрёстка: " + t);
            d.planHold = false;
            return false;
        }
    }

    // ======================================================================
    //  Окно предпросмотра расписания: больше трёх дней (по одному на день недели)
    //  Показываются три дня, начиная с сегодняшнего; нажатие на расписание листает дальше по три дня.
    // ======================================================================

    private static final java.util.Map<Integer, List<Object>> PREV_ALL = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Integer, Integer> PREV_OFF = new java.util.concurrent.ConcurrentHashMap<>();

    /** Находит поле (в том числе унаследованное) по имени. */
    private static Object fieldValue(Object o, String name) {
        try {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException e) {
                    // ищем выше
                }
            }
        } catch (Throwable t) {
            // нет доступа
        }
        return null;
    }

    private static Level levelOfScreen(Object screen) {
        Object l = screen == null ? null : fieldValue(screen, "level");
        return l instanceof Level ? (Level) l : null;
    }

    /** Индекс сегодняшнего дня (понедельник = 0) в списке из семи дней, иначе 0. */
    public static int todayIndex(Object comboBox, Object screen) {
        try {
            int count = ((com.rinventor.ptm2.engine.component.ComboBox) comboBox).count();
            Level level = levelOfScreen(screen);
            if (count == 7 && level != null) {
                return Math.floorMod(TimeHelper.weekday(level) - 1, 7);
            }
        } catch (Throwable t) {
            // по умолчанию первый вариант
        }
        return 0;
    }

    /** SchedulePreviewBox.setColumns: если дней больше трёх, показываем окно из трёх колонок, начиная с сегодняшнего дня. */
    public static List<?> previewColumns(List<?> cols, Object box) {
        try {
            int id = System.identityHashCode(box);
            if (cols == null || cols.size() <= 3) {
                PREV_ALL.remove(id);
                return cols;
            }
            List<Object> all = new ArrayList<>(cols);
            if (all.size() == 7 && !PREV_ALL.containsKey(id)) {
                Level level = levelOfScreen(fieldValue(box, "this$0"));
                int today = level != null ? Math.floorMod(TimeHelper.weekday(level) - 1, 7)
                    : java.time.LocalDate.now().getDayOfWeek().getValue() - 1;
                java.util.Collections.rotate(all, -today);
                PTMLogger.info("PTM2 расписание: колонок " + cols.size() + ", сегодня день " + today + (level == null ? " (по системным часам)" : ""));
            } else if (PREV_ALL.containsKey(id) && PREV_ALL.get(id).size() == all.size()) {
                all = new ArrayList<>(PREV_ALL.get(id));
            }
            PREV_ALL.put(id, all);
            int off = PREV_OFF.getOrDefault(id, 0) % all.size();
            return previewWindow(all, off);
        } catch (Throwable t) {
            return cols;
        }
    }

    private static List<Object> previewWindow(List<Object> all, int off) {
        List<Object> r = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            r.add(all.get((off + i) % all.size()));
        }
        return r;
    }

    /** Нажатие на окно предпросмотра: следующие три дня. */
    public static void previewClick(Object box) {
        try {
            int id = System.identityHashCode(box);
            List<Object> all = PREV_ALL.get(id);
            if (all == null || all.size() <= 3) {
                return;
            }
            int off = (PREV_OFF.getOrDefault(id, 0) + 3) % all.size();
            PREV_OFF.put(id, off);
            java.lang.reflect.Field f = box.getClass().getDeclaredField("columns");
            f.setAccessible(true);
            f.set(box, new ArrayList<>(previewWindow(all, off)));
        } catch (Throwable t) {
            // не критично
        }
    }

    // ======================================================================
    //  Остановки: автобус должен заезжать в карман (полосу с разметкой «зигзаг»), а не вставать на соседней полосе
    // ======================================================================

    private static final java.util.Map<Long, Long> POCKET_LOG = new java.util.concurrent.ConcurrentHashMap<>();

    /** Типы разметки в радиусе 2 блока от точки (для диагностики). */
    private static String markingsAround(Level level, Object p) {
        try {
            int x = (int) Math.floor(vc(p, 0));
            int y = (int) Math.floor(vc(p, 1));
            int z = (int) Math.floor(vc(p, 2));
            java.util.TreeSet<String> set = new java.util.TreeSet<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    RoadMarkingTypes t = markingAt(level, x + dx, y, z + dz);
                    if (t != null) {
                        set.add(t.name());
                    }
                }
            }
            return set.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Расстояние от точки (Vec3) до ближайшей клетки разметки «зигзаг» в радиусе 3 блока; 99, если рядом её нет. */
    private static double zigzagDist(Level level, Object p) throws ReflectiveOperationException {
        double px = vc(p, 0);
        int y = (int) Math.floor(vc(p, 1));
        double pz = vc(p, 2);
        int x = (int) Math.floor(px);
        int z = (int) Math.floor(pz);
        double best = 99.0D;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (markingAt(level, x + dx, y, z + dz) == RoadMarkingTypes.ZIG_ZAG) {
                    double cx = x + dx + 0.5D - px;
                    double cz = z + dz + 0.5D - pz;
                    best = Math.min(best, Math.sqrt(cx * cx + cz * cz));
                }
            }
        }
        return best;
    }

    /**
     * RoadPath.getBestEndCandidate: при пути к платформе (endPlatform) оставляем только полосы кармана (зигзаг),
     * достижимые по графу, если такие есть неподалёку от лучшего кандидата. Иначе список не меняется.
     */
    public static List<?> pocketCandidates(Object self, List<?> cands, java.util.Map<?, ?> dist, boolean tram) {
        try {
            if (tram || cands == null || cands.size() < 2 || dist == null) {
                return cands;
            }
            if (((com.rinventor.ptm2.dimension.virtual.util.RoadPath) self).endPlatform == null) {
                return cands;
            }
            Object lv = fieldValue(self, "level");
            if (!(lv instanceof Level) || PTMWorld.isClient((Level) lv)) {
                return cands;
            }
            initRefl();
            if (!reflOk) {
                return cands;
            }
            Level level = (Level) lv;
            double min = Double.MAX_VALUE;
            Object bestPoint = null;
            for (Object o : cands) {
                com.rinventor.ptm2.engine.computing.Triad<?, ?, ?> t0 = (com.rinventor.ptm2.engine.computing.Triad<?, ?, ?>) o;
                if ((Double) t0.getFirst() < min) {
                    min = (Double) t0.getFirst();
                    Object th = t0.getThird();
                    bestPoint = th instanceof VDTrafficLane ? ((VDTrafficLane) th).getEnd() : t0.getSecond();
                }
            }
            // Среди достижимых полос выбираем ту, под которой (ближе всего к центру полосы) лежит зигзаг: это и есть карман
            List<Object> reachable = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            double bestScore = 99.0D;
            for (Object o : cands) {
                com.rinventor.ptm2.engine.computing.Triad<?, ?, ?> t = (com.rinventor.ptm2.engine.computing.Triad<?, ?, ?>) o;
                if ((Double) t.getFirst() > min + 10.0D) {
                    continue;
                }
                Object node = t.getSecond();
                Object reach = node == null ? null : dist.get(node);
                if (!(reach instanceof Double) || ((Double) reach).isInfinite()) {
                    continue;
                }
                Object third = t.getThird();
                Object pt = third instanceof VDTrafficLane ? ((VDTrafficLane) third).getEnd() : node;
                double sc = pt == null ? 99.0D : zigzagDist(level, pt);
                reachable.add(o);
                scores.add(sc);
                bestScore = Math.min(bestScore, sc);
            }
            List<Object> pocket = new ArrayList<>();
            if (bestScore <= 2.5D) {
                for (int k = 0; k < reachable.size(); k++) {
                    if (scores.get(k) <= bestScore + 0.75D) {
                        pocket.add(reachable.get(k));
                    }
                }
            }
            try {
                var plat = ((com.rinventor.ptm2.dimension.virtual.util.RoadPath) self).endPlatform;
                long key = ((long) plat.getCenterX() << 32) ^ (plat.getCenterZ() & 0xFFFFFFFFL);
                long now = System.currentTimeMillis();
                Long last = POCKET_LOG.get(key);
                if (last == null || now - last > 60000L) {
                    POCKET_LOG.put(key, now);
                    PTMLogger.info("PTM2 остановка: платформа " + plat.getCenterX() + " " + plat.getCenterZ() + ": полос-кандидатов " + cands.size()
                        + ", достижимых " + reachable.size() + ", лучший зигзаг на расстоянии " + String.format("%.1f", bestScore) + ", выбрано " + pocket.size() + (pocket.isEmpty() ? ", разметка у лучшей полосы " + (bestPoint == null ? "?" : markingsAround(level, bestPoint)) : ", карман выбран"));
                }
            } catch (Throwable t) {
                // диагностика не критична
            }
            return pocket.isEmpty() ? cands : pocket;
        } catch (Throwable t) {
            return cands;
        }
    }

    // ======================================================================
    //  Дальности игроков: у каждого игрока своя, а не минимальная на всех
    //  Оригинал берёт наименьшую дальность прорисовки среди всех игроков и применяет её ко всем: пока рядом игрок
    //  с маленькой дальностью, у остальных транспорт пропадает (уходит в виртуальный режим) слишком близко.
    //  Теперь машина существует, если хотя бы для одного игрока она в пределах ЕГО дальности.
    //  Всё считает сервер; клиент только рисует то, что сервер ему передал.
    // ======================================================================

    private static volatile Map<java.util.UUID, Object> PV_POS = new java.util.HashMap<>();
    private static volatile int LAST_T;
    private static volatile int LAST_F;
    private static volatile Object LAST_LEVEL;
    private static java.lang.reflect.Field vdField;
    private static volatile long limitsAt;
    private static volatile int limSim = -1;
    private static volatile int limView = 32;
    private static volatile boolean rangeWarned;

    /** PlayerTracker.setTrackedPlayerPositions: запоминаем позиции игроков вместе с их UUID. */
    public static void trackPositions(Map<?, ?> m) {
        Map<java.util.UUID, Object> copy = new java.util.HashMap<>();
        try {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put((java.util.UUID) e.getKey(), e.getValue());
            }
        } catch (Throwable t) {
            // не критично
        }
        PV_POS = copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<java.util.UUID, Integer> viewDistances() {
        try {
            if (vdField == null) {
                vdField = com.rinventor.ptm2.dimension.virtual.systems.PlayerTracker.class.getDeclaredField("playerViewDistances");
                vdField.setAccessible(true);
            }
            return (Map<java.util.UUID, Integer>) vdField.get(null);
        } catch (Throwable t) {
            return java.util.Collections.emptyMap();
        }
    }

    /** Серверные пределы (кэшируются на 2 секунды): дальность симуляции в блоках минус 16 и дальность прорисовки в чанках. */
    private static void refreshLimits(Object level) {
        long now = System.currentTimeMillis();
        if (now - limitsAt < 2000L) {
            return;
        }
        limitsAt = now;
        try {
            Object server = level == null ? null : srg(level, "m_7654_");
            Object list = server == null ? null : srg(server, "m_6846_");
            if (list != null) {
                limSim = ((Number) srg(list, "m_184213_")).intValue() * 16 - 16;
                limView = ((Number) srg(list, "m_11312_")).intValue();
            }
        } catch (Throwable t) {
            // остаются прежние значения
        }
    }

    private static int clampRange(Object level, int r) {
        refreshLimits(level);
        return limSim <= 0 ? r : Math.max(16, Math.min(r, limSim));
    }

    private static int transportRangeOf(Object level, java.util.UUID id) {
        Integer vd = viewDistances().get(id);
        return clampRange(level, vd == null ? 96 : vd - 10);
    }

    private static int trafficRangeOf(Object level, java.util.UUID id) {
        Integer vd = viewDistances().get(id);
        refreshLimits(level);
        return clampRange(level, (vd == null ? limView * 16 : vd) - 16);
    }

    /** PlayerTracker.physicalTransportRange: наибольшая дальность среди игроков (проверка «рядом ли игрок» делается по каждому отдельно). */
    public static int transportRange(Object level) {
        LAST_LEVEL = level;
        int r = 0;
        Map<java.util.UUID, Object> pm = PV_POS;
        if (pm.isEmpty()) {
            r = clampRange(level, 96);
        } else {
            for (java.util.UUID id : pm.keySet()) {
                r = Math.max(r, transportRangeOf(level, id));
            }
        }
        LAST_T = r;
        return r;
    }

    /** PlayerTracker.physicalTrafficRange: то же для легкового транспорта. */
    public static int trafficRange(Object level) {
        LAST_LEVEL = level;
        int r = 0;
        Map<java.util.UUID, Object> pm = PV_POS;
        if (pm.isEmpty()) {
            refreshLimits(level);
            r = clampRange(level, limView * 16 - 16);
        } else {
            for (java.util.UUID id : pm.keySet()) {
                r = Math.max(r, trafficRangeOf(level, id));
            }
        }
        LAST_F = r;
        return r;
    }

    /**
     * PlayerTracker.anyPlayerNear(Vec3, int): если радиус получен из дальностей транспорта или легковых (оригинал вызывает
     * physicalTransportRange / physicalTrafficRange, а при возрождении вычитает 4), то для каждого игрока берём его собственную дальность.
     * Для остальных вызовов (часы, объекты) работает прежний круг заданного радиуса.
     */
    public static boolean anyNear(Object pos, int radius) {
        try {
            double px = vc(pos, 0);
            double py = vc(pos, 1);
            double pz = vc(pos, 2);
            Map<java.util.UUID, Object> pm = PV_POS;
            int offT = LAST_T - radius;
            boolean tKind = LAST_T > 0 && (offT == 0 || offT == 4);
            int offF = LAST_F - radius;
            boolean fKind = !tKind && LAST_F > 0 && offF == 0;
            if (pm.isEmpty() || (!tKind && !fKind) || LAST_LEVEL == null) {
                double rr = (double) radius * (double) radius;
                for (Object pp : com.rinventor.ptm2.dimension.virtual.systems.PlayerTracker.getPlayerPositions()) {
                    double dx = vc(pp, 0) - px;
                    double dy = vc(pp, 1) - py;
                    double dz = vc(pp, 2) - pz;
                    if (dx * dx + dy * dy + dz * dz < rr) {
                        return true;
                    }
                }
                return false;
            }
            for (Map.Entry<java.util.UUID, Object> e : pm.entrySet()) {
                int r = tKind ? transportRangeOf(LAST_LEVEL, e.getKey()) - offT : trafficRangeOf(LAST_LEVEL, e.getKey()) - offF;
                double dx = vc(e.getValue(), 0) - px;
                double dy = vc(e.getValue(), 1) - py;
                double dz = vc(e.getValue(), 2) - pz;
                if (dx * dx + dy * dy + dz * dz < (double) r * (double) r) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            if (!rangeWarned) {
                rangeWarned = true;
                PTMLogger.warn("PTM2 fixes: ошибка проверки дальности игроков: " + t);
            }
            return true;
        }
    }

    // ======================================================================
    //  Типы зон парковки: «стоянка», «спавн и удаление», «оба»
    //  Тип хранится в данных зоны как поле zt (0 = стоянка, 1 = спавн и удаление, 2 = и то и другое);
    //  в редакторе карты он выбирается списком в панели зоны. Зоны разных типов могут лежать друг в друге.
    // ======================================================================

    public static final int ZONE_PARK = 0;
    public static final int ZONE_SPAWN = 1;
    public static final int ZONE_BOTH = 2;
    private static final String[] ZONE_TYPE_NAMES = {"Стоянка", "Спавн и удаление", "Стоянка и спавн/удаление"};
    private static java.lang.reflect.Field zoneTypeField;

    /** VDParkingZone.toString: дописывает тип зоны (0 - по умолчанию, не пишется). */
    public static String withZoneType(String s, int t) {
        if (s == null || t == 0) {
            return s;
        }
        int i = s.lastIndexOf(']');
        return i < 0 ? s : s.substring(0, i) + ",zt=" + t + s.substring(i);
    }

    /** VDParkingZone(String): читает тип зоны из данных. */
    public static int zoneTypeOfData(String data) {
        try {
            if (data == null) {
                return 0;
            }
            String v = VDUtils.getValue(data, "zt");
            return v == null ? 0 : Math.max(0, Math.min(2, Integer.parseInt(v.trim())));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static java.lang.reflect.Field zoneField() throws ReflectiveOperationException {
        if (zoneTypeField == null) {
            zoneTypeField = com.rinventor.ptm2.dimension.virtual.types.VDParkingZone.class.getDeclaredField("zoneType");
            zoneTypeField.setAccessible(true);
        }
        return zoneTypeField;
    }

    /** Тип зоны парковки (0, если поле недоступно). */
    public static int zoneType(Object zone) {
        try {
            return zoneField().getInt(zone);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** VDParkingZone.setProperty: свойство 12 = тип зоны. */
    public static void setZoneProp(Object zone, int id, String value) {
        if (id != 12) {
            return;
        }
        try {
            zoneField().setInt(zone, Math.max(0, Math.min(2, Integer.parseInt(value.trim()))));
        } catch (Throwable t) {
            // не критично
        }
    }

    /** Панель зоны парковки: список «Тип зоны». */
    public static void addZoneTypeBox(Object panel, Object level, Object entry, int id) {
        try {
            com.rinventor.ptm2.dimension.digital.panels.ParkingZonePanel p = (com.rinventor.ptm2.dimension.digital.panels.ParkingZonePanel) panel;
            Level lv = (Level) level;
            final int[] cur = {zoneType(entry)};
            com.rinventor.ptm2.engine.component.ScaledLabel lbl = new com.rinventor.ptm2.engine.component.ScaledLabel(0, 0, 100, 12, "Тип зоны", com.rinventor.ptm2.engine.graphics.Colors.text(), true, false, false);
            p.addWidget(lbl, new com.rinventor.ptm2.engine.screen.Fragment(lbl));
            ComboBox cb = new ComboBox(0, 0, 100, 20, java.util.Arrays.asList(ZONE_TYPE_NAMES), (c) -> {
                int v = ((ComboBox) c).getSelectedIndex();
                if (v >= 0 && v != cur[0]) {
                    cur[0] = v;
                    com.rinventor.ptm2.dimension.virtual.storage.VDStorageHandler.updateVal(lv, VDMemoryIDs.PARKING_ZONE, id, 12, String.valueOf(v));
                }
            });
            cb.setSelectedIndex(Math.max(0, Math.min(2, cur[0])));
            p.addWidget(cb, new com.rinventor.ptm2.engine.screen.Fragment(cb));
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: не удалось добавить выбор типа зоны: " + t);
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
            int seg = (int) hSegGet.invoke(v);
            double off = (double) hOffGet.invoke(v);
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
                    pts = (List<?>) hPts.invoke(v, v.currentPath.get(lane));
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
    private static java.lang.invoke.MethodHandle hSegGet;
    private static java.lang.invoke.MethodHandle hSegSet;
    private static java.lang.invoke.MethodHandle hOffGet;
    private static java.lang.invoke.MethodHandle hOffSet;
    private static java.lang.invoke.MethodHandle hPts;
    private static java.lang.invoke.MethodHandle hMove;
    private static java.lang.reflect.Field[] vecF;
    private static java.lang.reflect.Constructor<?> bpCtor;

    private static void initRefl() {
        if (reflInit) {
            return;
        }
        reflInit = true;
        try {
            // MethodHandles связывают только нужный член; getDeclaredMethod на Vehicle тянет клиентские типы
            java.lang.invoke.MethodHandles.Lookup lk = java.lang.invoke.MethodHandles.privateLookupIn(Vehicle.class,
                java.lang.invoke.MethodHandles.lookup());
            hSegGet = lk.findGetter(Vehicle.class, "pathSegmentIndex", int.class);
            hSegSet = lk.findSetter(Vehicle.class, "pathSegmentIndex", int.class);
            hOffGet = lk.findGetter(Vehicle.class, "pathSegmentOffset", double.class);
            hOffSet = lk.findSetter(Vehicle.class, "pathSegmentOffset", double.class);
            hPts = lk.findVirtual(Vehicle.class, "getLanePoints",
                java.lang.invoke.MethodType.methodType(List.class, VDTrafficLane.class));
            hMove = lk.findVirtual(Vehicle.class, "moveAlongPath",
                java.lang.invoke.MethodType.methodType(void.class, double.class, double.class));
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
            int seg = (int) hSegGet.invoke(v);
            double off = (double) hOffGet.invoke(v);
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
                    List<?> pts = (List<?>) hPts.invoke(v, v.currentPath.get(lane));
                    off = segLen(pts, seg);
                } else if (lane > 0) {
                    lane--;
                    List<?> pts = (List<?>) hPts.invoke(v, v.currentPath.get(lane));
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
            hSegSet.invoke(v, seg);
            hOffSet.invoke(v, off);
            v.pathProgress = prog;
            hMove.invoke(v, 0.0D, (double) PTMEntity.getYaw(v));
            return true;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: ошибка заднего хода: " + t);
            return false;
        }
    }

    /** Просит машины, стоящие вплотную позади (в том же направлении), отъехать назад первыми (цепочка отъезда). */
    private static void markBehind(Vehicle v, long now) {
        double yaw = Math.toRadians(PTMEntity.getYaw(v));
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double x = PTMEntity.getX(v);
        double z = PTMEntity.getZ(v);
        for (Vehicle o : PTMEntity.getDifferentVehicles(24.0D, v)) {
            double dx = PTMEntity.getX(o) - x;
            double dz = PTMEntity.getZ(o) - z;
            double along = dx * fx + dz * fz;
            double lat = Math.abs(-dx * fz + dz * fx);
            if (along < 0.0D && along > -(v.length / 2.0D + o.length / 2.0D + 4.0D) && lat < v.width / 2.0D + o.width / 2.0D + 0.8D
                && Math.abs(wrap180(PTMEntity.getYaw(v) - PTMEntity.getYaw(o))) <= 50.0D) {
                Drv od = DRV.get(System.identityHashCode(o));
                if (od != null) {
                    od.chainUntil = now + 4000L;
                }
            }
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

    /** Сколько миллисекунд «разрешено ехать, но стоим» считается физической блокировкой. */
    static final long STALL_MS = 4000L;

    /** Когда машина начала ждать: по решению ИИ (firstBlocked) или физически (стоит при разрешённом проезде). 0 - не ждёт. */
    private static long waitStart(Drv d, long now) {
        if (d.lastOrig) {
            return d.stillSince > 0L && now - d.stillSince >= STALL_MS ? d.stillSince : 0L;
        }
        return d.firstBlocked;
    }

    /** Автобус/троллейбус на остановке (выезжает с неё) имеет приоритет при разъезде. */
    private static long bias(Vehicle x) {
        try {
            return x instanceof Transport && VDUtils.inZone(x, VDMemoryIDs.STATION) ? 10000L : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean crossing(Vehicle a, Vehicle b) {
        return Math.abs(wrap180(PTMEntity.getYaw(a) - PTMEntity.getYaw(b))) > 50.0D;
    }

    /**
     * Решение при взаимной блокировке: кто из двух ждущих машин отъезжает назад.
     * Отъезжает та, что ждёт меньше; если ей отъехать некуда, а другой есть куда - другая.
     */
    private static boolean yieldDecision(Vehicle v, long myWait, Level level) {
        Vehicle b = blockerAhead(v);
        if (b == null || Math.abs(b.speed) >= 0.3F || myWait == 0L || !crossing(v, b)) {
            return false;
        }
        Drv bd = DRV.get(System.identityHashCode(b));
        if (bd == null) {
            return false;
        }
        long bw = waitStart(bd, System.currentTimeMillis());
        if (bw == 0L) {
            return false;
        }
        long me = myWait - bias(v);
        long he = bw - bias(b);
        boolean iLose = me > he || (me == he && System.identityHashCode(v) > System.identityHashCode(b));
        boolean canBack = spaceBehind(v, level);
        if (iLose) {
            if (!canBack) {
                // отъехать некуда: просим стоящих позади освободить место
                markBehind(v, System.currentTimeMillis());
            }
            return canBack;
        }
        return canBack && !spaceBehind(b, level);
    }

    /** Разрешено ехать, но физически стоим: возможно, нас держит встречная/поперечная машина. */
    private static boolean stallYield(Vehicle v, Drv d, Level level, long now) {
        if (!yieldDecision(v, d.stillSince, level)) {
            return false;
        }
        d.backing = true;
        d.backLeft = 7.0D;
        d.failed++;
        drvLog(d, now, "отъезжает назад, уступая (упёрлись) " + (int) PTMEntity.getX(v) + " " + (int) PTMEntity.getZ(v));
        return true;
    }

    /** Должна ли эта машина уступить (отъехать): блокирует такая же ждущая машина, которая ждёт дольше. */
    private static boolean shouldYield(Vehicle v, Drv d, Level level) {
        return yieldDecision(v, d.firstBlocked, level);
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
        if (PTMWorld.isClient(v)) {
            return orig;
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
            // Не перестраиваемся на полосу, лежащую заметно выше или ниже (эстакада и дорога под ней)
            if (Math.abs(vc(en, 1) - PTMEntity.getY(v)) > 2.0D) {
                return true;
            }
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

    // ======================================================================
    //  Автосвязки перестроения между параллельными полосами (вместо ручных меток)
    // ======================================================================

    static final boolean AUTO_LINKS = true;
    /** Длина вдоль дороги, на которой машина переходит в соседнюю полосу. */
    static final double LINK_LEN = 12.0D;
    /** Шаг между связками вдоль полосы. */
    static final double LINK_STEP = 18.0D;

    private static Object vec3(Object like, double x, double y, double z) throws ReflectiveOperationException {
        return like.getClass().getConstructor(double.class, double.class, double.class).newInstance(x, y, z);
    }

    /** Есть ли на отрезке P-Q запрещающая разметка (двойная и т.п., одинарная длиннее LINE_SOLID_RUN блоков). */
    private static boolean linkBlocked(Level level, double px, double py, double pz, double qx, double qz, double ux, double uz)
        throws ReflectiveOperationException {
        double len = Math.sqrt((qx - px) * (qx - px) + (qz - pz) * (qz - pz));
        int by = (int) Math.floor(py);
        java.util.HashSet<Long> checked = new java.util.HashSet<>();
        for (double t = 0.0D; t <= len; t += 0.5D) {
            int bx = (int) Math.floor(px + (qx - px) * t / len);
            int bz = (int) Math.floor(pz + (qz - pz) * t / len);
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
            if (mt == RoadMarkingTypes.LINE && lineRun(level, bx, by, bz, ux, uz) > LINE_SOLID_RUN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Подмена списка дорожек для графа маршрутов (RoadPath.createGraphLanes):
     * к настоящим полосам добавляются «перестроения» между соседними параллельными полосами одного потока.
     * Маршрут (автобуса, машины) сам выбирает перестроение, когда оно нужно, например перед поворотом.
     * Не создаются связки внутри перекрёстков и через запрещающую разметку.
     */
    public static java.util.Collection<String> lanesWithLinks(java.util.concurrent.ConcurrentHashMap<?, ?> map, Level level) {
        List<String> out = new java.util.ArrayList<>();
        for (Object o : map.values()) {
            out.add((String) o);
        }
        if (!AUTO_LINKS) {
            return out;
        }
        try {
            initRefl();
            if (!reflOk) {
                return out;
            }
            Map<VDTrafficLane, List<VDTrafficLane>> par = ((VDStorage) PTM.VD_STORAGE.get(level)).PARALLEL_TRAFFIC_LANES;
            if (par == null || par.isEmpty()) {
                return out;
            }
            int made = 0;
            for (Map.Entry<VDTrafficLane, List<VDTrafficLane>> e : new java.util.ArrayList<>(par.entrySet())) {
                VDTrafficLane a = e.getKey();
                if (a == null || e.getValue() == null || a.merging || a.isCurve() || a.invalid()) {
                    continue;
                }
                double ax = vc(a.getStart(), 0);
                double ay = vc(a.getStart(), 1);
                double az = vc(a.getStart(), 2);
                double aex = vc(a.getEnd(), 0);
                double aey = vc(a.getEnd(), 1);
                double aez = vc(a.getEnd(), 2);
                double la = Math.sqrt((aex - ax) * (aex - ax) + (aez - az) * (aez - az));
                if (la < LINK_LEN + 6.0D) {
                    continue;
                }
                double ux = (aex - ax) / la;
                double uz = (aez - az) / la;
                for (VDTrafficLane b : new java.util.ArrayList<>(e.getValue())) {
                    if (b == null || b.merging || b.isCurve() || b.invalid() || b.transport != a.transport) {
                        continue;
                    }
                    double bsx = vc(b.getStart(), 0);
                    double bsy = vc(b.getStart(), 1);
                    double bsz = vc(b.getStart(), 2);
                    double bex = vc(b.getEnd(), 0);
                    double bey = vc(b.getEnd(), 1);
                    double bez = vc(b.getEnd(), 2);
                    double bdx = bex - bsx;
                    double bdz = bez - bsz;
                    double lb2 = bdx * bdx + bdz * bdz;
                    double lb = Math.sqrt(lb2);
                    if (lb2 < 1.0E-6D) {
                        continue;
                    }
                    for (double sPos = 4.0D; sPos + LINK_LEN <= la - 1.0D && made < 8000; sPos += LINK_STEP) {
                        double px = ax + ux * sPos;
                        double pz = az + uz * sPos;
                        double py = ay + (aey - ay) * sPos / la;
                        double rx = ax + ux * (sPos + LINK_LEN);
                        double rz = az + uz * (sPos + LINK_LEN);
                        double t = ((rx - bsx) * bdx + (rz - bsz) * bdz) / lb2;
                        if (t * lb < 3.0D || (1.0D - t) * lb < 3.0D) {
                            continue;
                        }
                        double qx = bsx + bdx * t;
                        double qz = bsz + bdz * t;
                        double qy = bsy + (bey - bsy) * t;
                        double lat = Math.sqrt((qx - rx) * (qx - rx) + (qz - rz) * (qz - rz));
                        if (lat < 2.0D || lat > 8.0D) {
                            continue;
                        }
                        // Не связываем полосы на разной высоте (эстакада и дорога под ней)
                        if (Math.abs(qy - py) > 1.0D || Math.abs(aey - ay) > 3.0D) {
                            continue;
                        }
                        if (inIntersectionAt(level, px, pz) || inIntersectionAt(level, qx, qz)
                            || inIntersectionAt(level, (px + qx) / 2.0D, (pz + qz) / 2.0D)) {
                            continue;
                        }
                        if (linkBlocked(level, px, py, pz, qx, qz, ux, uz)) {
                            continue;
                        }
                        VDTrafficLane link = new VDTrafficLane(a.toString());
                        VDTrafficLane.class.getField("start").set(link, vec3(a.getStart(), px, py, pz));
                        VDTrafficLane.class.getField("end").set(link, vec3(a.getStart(), qx, qy, qz));
                        link.elements = new java.util.ArrayList<>();
                        link.merging = true;
                        link.rot8 = a.rot8;
                        link.rotation = (float) link.getRotation();
                        out.add(link.toString());
                        made++;
                    }
                }
            }
            if (made > 0) {
                PTMLogger.info("PTM2 ИИ: добавлено автосвязок перестроения для маршрутов: " + made);
            }
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: ошибка построения автосвязок: " + t);
        }
        return out;
    }

    /** Ревизия графа маршрутов должна меняться, когда пересчитались параллельные полосы. */
    public static long linkRevision(long orig, Level level) {
        try {
            Map<VDTrafficLane, List<VDTrafficLane>> par = ((VDStorage) PTM.VD_STORAGE.get(level)).PARALLEL_TRAFFIC_LANES;
            return orig * 31L + (par == null ? 0 : par.size());
        } catch (Throwable t) {
            return orig;
        }
    }

    // ======================================================================
    //  Приоритет автобуса/троллейбуса, выезжающего с остановки
    // ======================================================================

    /**
     * Расширение VehicleUtils.giveWayToBus: уступаем не только автобусу с включённым поворотником,
     * но и любому автобусу/троллейбусу, который трогается или медленно едет по зоне остановки справа впереди.
     */
    public static boolean giveWay(Vehicle v, boolean orig, Level level) {
        if (orig) {
            return true;
        }
        try {
            if (v instanceof Transport || PTMWorld.isClient(v)) {
                return false;
            }
            double check = Math.max(14.0D, v.speed + 14.0D);
            double yaw = Math.toRadians(PTMEntity.getYaw(v));
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            double rx = -fz;
            double rz = fx;
            boolean leftTraffic = ((WorldSavedData) PTM.DATA.get(level)).LEFT_TRAFFIC;
            double x = PTMEntity.getX(v);
            double z = PTMEntity.getZ(v);
            for (Vehicle bus : PTMEntity.getDifferentVehicles(2.0D * (check + 4.0D), v)) {
                boolean busType = bus instanceof Bus || bus.vehicleType == VehicleTypes.BUS || bus.vehicleType == VehicleTypes.TROLLEYBUS;
                if (!busType || !VDUtils.inZone(bus, VDMemoryIDs.STATION)) {
                    continue;
                }
                boolean signaling = bus.getIndicators() == (leftTraffic ? 1 : -1);
                boolean pulling = !bus.handBrake && bus.speed >= 0.3F && bus.speed < 6.0F;
                if (!signaling && !pulling) {
                    continue;
                }
                double dx = PTMEntity.getX(bus) - x;
                double dz = PTMEntity.getZ(bus) - z;
                double fwd = dx * fx + dz * fz;
                double side = dx * rx + dz * rz;
                if (leftTraffic) {
                    side = -side;
                }
                if (fwd < -2.0D || fwd > check || side < -1.0D || side > 8.0D) {
                    continue;
                }
                int diff = Rotation8.difference(Rotation8.get(PTMEntity.getYaw(v)), Rotation8.get(PTMEntity.getYaw(bus)));
                if (diff <= 2) {
                    v.allowingLaneChanger = true;
                    return true;
                }
            }
        } catch (Exception e) {
            PTMLogger.error(e);
        }
        return false;
    }

    // ======================================================================
    //  Фильтр скачков высоты колёс (машина то проваливается в дорогу, то задирается)
    // ======================================================================

    static final class Wst {
        double base;
        boolean init;
        int rejects;
    }

    static final Map<Integer, Wst> WST = new ConcurrentHashMap<>();
    /** Допустимое отклонение высоты колеса от привычной (блоков), больше - считаем помехой. */
    static final double WHEEL_JUMP = 0.8D;
    /** Фильтр выключен: на практике он не помогал, а колёса и без него ведут себя штатно. */
    static final boolean WHEEL_FILTER = false;
    static long wheelLogAt = 0L;

    /**
     * Замена WheelLocation.updateLocation(Level, x, y, z, yaw) в Vehicle.updateSection.
     * Для транспорта, едущего по маршруту (путь известен), единичные скачки высоты колеса больше
     * WHEEL_JUMP относительно высоты пути отбрасываются (колесо поймало блок над дорогой или дырку в ней).
     * Если новая высота держится дольше 25 тиков, она принимается (настоящий подъём или спуск).
     */
    public static void wheelUpdate(WheelLocation axis, Level level, double x, double y, double z, double yaw) {
        axis.updateLocation(level, x, y, z, yaw);
        if (!WHEEL_FILTER) {
            return;
        }
        try {
            Vehicle v = CTX.get();
            if (v == null || v.currentPath == null || v.currentPath.isEmpty() || v.requiresTracks) {
                return;
            }
            double newY = axis.getY();
            if (WST.size() > 20000) {
                WST.clear();
            }
            Wst st = WST.computeIfAbsent(System.identityHashCode(axis), k -> new Wst());
            double off = newY - y;
            if (!st.init) {
                st.init = true;
                st.base = off;
                return;
            }
            if (Math.abs(off - st.base) > WHEEL_JUMP) {
                if (++st.rejects > 25) {
                    st.base = off;
                    st.rejects = 0;
                    return;
                }
                axis.setY(y + st.base);
                long now = System.currentTimeMillis();
                if (now - wheelLogAt > 20000L) {
                    wheelLogAt = now;
                    PTMLogger.info("PTM2 \u0418\u0418: \u043e\u0442\u0431\u0440\u043e\u0448\u0435\u043d \u0441\u043a\u0430\u0447\u043e\u043a \u0432\u044b\u0441\u043e\u0442\u044b \u043a\u043e\u043b\u0435\u0441\u0430 " + String.format("%.2f", off - st.base)
                        + " \u043d\u0430 " + (int) x + " " + (int) y + " " + (int) z);
                }
            } else {
                st.base += (off - st.base) * 0.15D;
                st.rejects = 0;
            }
        } catch (Throwable t) {
            // не ломаем отрисовку
        }
    }

    // ======================================================================
    //  Корпус автобуса: войти и выйти можно только на остановке с открытыми дверями
    // ======================================================================

    /** Включить ли «стены» автобуса. */
    static final boolean HULL_ENABLED = true;

    /** Игроки, которых только что высадили намеренно: имя -> до какого времени не трогаем. */
    static final ConcurrentHashMap<String, Long> EXEMPT = new ConcurrentHashMap<>();

    static final class Rec {
        boolean inside;
        int sec;
        double lx;
        double lz;
        double dy;
        double wx;
        double wy;
        double wz;
        long msgAt;
        long rideAt;
    }

    static final Map<String, Rec> RECS = new ConcurrentHashMap<>();

    private static boolean doorsOpenAndStopped(Vehicle root) {
        boolean doors = root.doorButtonsEnabled ? root.doorsAllowed : root.doorsOpen;
        return doors && Math.abs(root.speed) < 0.5F;
    }

    private static void teleportPlayer(Player player, double x, double y, double z) throws ReflectiveOperationException {
        Object conn = player.getClass().getField("f_8906_").get(player);
        conn.getClass().getMethod("m_9780_", double.class, double.class, double.class, float.class, float.class, java.util.Set.class)
            .invoke(conn, x, y, z, (float) PTMEntity.getYaw(player), ((Number) srg(player, "m_146909_")).floatValue(), java.util.Collections.emptySet());
    }

    /**
     * Вызывается каждый тик у главной секции транспорта. Игрок не может пересечь корпус, пока двери закрыты
     * или транспорт едет: его возвращают туда, где он был. Игроки, зашедшие пешком при открытых дверях,
     * берутся на контроль оплаты.
     */
    public static void vehicleTick(Vehicle v) {
        if (!HULL_ENABLED) {
            return;
        }
        try {
            if (!(v instanceof Transport) || v.sectionID != 0 || PTMWorld.isClient(v)) {
                return;
            }
            Level level = (Level) srg(v, "m_9236_");
            List<Player> players = PTMWorld.getPlayers(level);
            if (players == null || players.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            terminalUnload(v, level, players, now, drv(v));
            boolean allowed = doorsOpenAndStopped(v);
            double vx = PTMEntity.getX(v);
            double vz = PTMEntity.getZ(v);
            double reach = (v.length * Math.max(1, v.totalSections)) / 2.0D + 12.0D;
            int busKey = System.identityHashCode(v);
            for (Player p : players) {
                String name = PTMEntity.getName(p);
                String rk = name + "#" + busKey;
                double dx = PTMEntity.getX(p) - vx;
                double dz = PTMEntity.getZ(p) - vz;
                if (dx * dx + dz * dz > reach * reach) {
                    RECS.remove(rk);
                    continue;
                }
                // Зритель или игрок, едущий на другом транспорте, корпус не трогает
                try {
                    if ((Boolean) srg(p, "m_5833_")) {
                        continue;
                    }
                    if (srg(p, "m_20202_") != null && sectionOfPlayer(v, p) == null) {
                        continue;
                    }
                } catch (Throwable t) {
                    // не критично
                }
                Long ex = EXEMPT.get(name);
                if (ex != null) {
                    if (now < ex) {
                        RECS.remove(rk);
                        continue;
                    }
                    EXEMPT.remove(name);
                }
                // Игрок сидит (или садится) на сиденье: место может лежать вне габарита корпуса, возвращать игрока нельзя
                boolean riding = false;
                try {
                    riding = srg(p, "m_20202_") != null;
                } catch (Throwable t) {
                    // не критично
                }
                if (riding) {
                    Rec rr = RECS.get(rk);
                    if (rr != null) {
                        rr.inside = true;
                        rr.rideAt = now;
                    }
                    continue;
                }
                int insideIdx = -1;
                Vehicle insideSec = null;
                for (int i = 0; i < v.totalSections; i++) {
                    Vehicle sec = v.getSection(i);
                    if (sec != null && VehicleUtils.isPlayerInVehiclesRotatedAABB(p, sec)) {
                        insideIdx = i;
                        insideSec = sec;
                        break;
                    }
                }
                boolean now0 = insideSec != null;
                Rec r = RECS.get(rk);
                if (r == null) {
                    r = new Rec();
                    r.inside = now0;
                    RECS.put(rk, r);
                    track(r, p, insideSec, insideIdx);
                    if (now0) {
                        registerFare(v, p, level, false);
                    }
                    continue;
                }
                if (r.inside == now0) {
                    track(r, p, insideSec, insideIdx);
                    continue;
                }
                if (!allowed && now - r.rideAt < 1500L) {
                    // только что встал с сиденья: границу корпуса не считаем нарушением
                    r.inside = now0;
                    track(r, p, insideSec, insideIdx);
                    continue;
                }
                if (allowed) {
                    r.inside = now0;
                    track(r, p, insideSec, insideIdx);
                    if (now0) {
                        registerFare(v, p, level, true);
                    }
                    continue;
                }
                // Пересечение корпуса при закрытых дверях или на ходу: возвращаем на прежнее место
                if (r.inside) {
                    Vehicle sec = v.getSection(r.sec);
                    if (sec != null) {
                        double rot = Math.toRadians(PTMEntity.getYaw(sec));
                        double wx = PTMEntity.getX(sec) + r.lx * Math.cos(rot) - r.lz * Math.sin(rot);
                        double wz = PTMEntity.getZ(sec) + r.lx * Math.sin(rot) + r.lz * Math.cos(rot);
                        teleportPlayer(p, wx, PTMEntity.getY(sec) + r.dy, wz);
                    }
                } else {
                    teleportPlayer(p, r.wx, r.wy, r.wz);
                }
                if (now - r.msgAt > 2500L) {
                    r.msgAt = now;
                    PTMChat.msgNearest("\u0414\u0432\u0435\u0440\u0438 \u0437\u0430\u043a\u0440\u044b\u0442\u044b", "red", level, PTMEntity.getX(p), PTMEntity.getY(p), PTMEntity.getZ(p));
                }
            }
        } catch (Throwable t) {
            // не ломаем тик транспорта
        }
    }

    private static void track(Rec r, Player p, Vehicle sec, int idx) {
        if (sec != null) {
            double dx = PTMEntity.getX(p) - PTMEntity.getX(sec);
            double dz = PTMEntity.getZ(p) - PTMEntity.getZ(sec);
            double rot = Math.toRadians(PTMEntity.getYaw(sec));
            r.sec = idx;
            r.lx = dx * Math.cos(rot) + dz * Math.sin(rot);
            r.lz = -dx * Math.sin(rot) + dz * Math.cos(rot);
            r.dy = PTMEntity.getY(p) - PTMEntity.getY(sec);
        } else {
            r.wx = PTMEntity.getX(p);
            r.wy = PTMEntity.getY(p);
            r.wz = PTMEntity.getZ(p);
        }
    }

    // ======================================================================
    //  Депо: появление и возврат транспорта, режим «призрака» внутри зоны, очередь на выезд
    // ======================================================================

    static final class Depot {
        int idx;
        int id;
        VDDepot d;
        int minX;
        int minZ;
        int maxX;
        int maxZ;
        boolean child;
        java.util.List<Depot> kids = new java.util.ArrayList<>();

        boolean contains(double x, double z) {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            return bx >= minX && bx <= maxX && bz >= minZ && bz <= maxZ;
        }
    }

    static final class Depots {
        long t;
        java.util.List<Depot> list = new java.util.ArrayList<>();
    }

    static final Map<Integer, Depots> DEPOTS = new ConcurrentHashMap<>();

    /** Депо в порядке возрастания номера: именно в таком порядке их показывает выпадающий список линии. */
    static java.util.List<Depot> depots(Level level) {
        long now = System.currentTimeMillis();
        int key = System.identityHashCode(level);
        Depots c = DEPOTS.get(key);
        if (c != null && now - c.t < 3000L) {
            return c.list;
        }
        Depots n = new Depots();
        n.t = now;
        java.util.TreeMap<Integer, String> sorted = new java.util.TreeMap<>();
        for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.DEPOT).entrySet()) {
            sorted.put(e.getKey(), e.getValue());
        }
        Map<Integer, Depot> byId = new java.util.HashMap<>();
        int i = 0;
        for (Map.Entry<Integer, String> e : sorted.entrySet()) {
            Depot dp = new Depot();
            dp.idx = i++;
            dp.id = e.getKey();
            dp.d = new VDDepot(e.getValue());
            dp.minX = Math.min(dp.d.sbX, dp.d.fbX);
            dp.maxX = Math.max(dp.d.sbX, dp.d.fbX);
            dp.minZ = Math.min(dp.d.sbZ, dp.d.fbZ);
            dp.maxZ = Math.max(dp.d.sbZ, dp.d.fbZ);
            n.list.add(dp);
            byId.put(dp.id, dp);
        }
        for (Depot m : n.list) {
            if (m.d.childrenIDs != null) {
                for (Integer kid : m.d.childrenIDs) {
                    Depot k = byId.get(kid);
                    if (k != null) {
                        k.child = true;
                        m.kids.add(k);
                    }
                }
            }
        }
        // вложенность: зона депо внутри другой зоны депо (допуск 2 блока) считается дочерней сама
        for (Depot a : n.list) {
            Depot parent = null;
            long pa = 0L;
            for (Depot b : n.list) {
                if (a == b) {
                    continue;
                }
                long ab = (long) (b.maxX - b.minX + 1) * (b.maxZ - b.minZ + 1);
                long aa = (long) (a.maxX - a.minX + 1) * (a.maxZ - a.minZ + 1);
                if (aa < ab && a.minX >= b.minX - 2 && a.maxX <= b.maxX + 2 && a.minZ >= b.minZ - 2 && a.maxZ <= b.maxZ + 2
                    && (parent == null || ab < pa)) {
                    parent = b;
                    pa = ab;
                }
            }
            if (parent != null) {
                a.child = true;
                if (!parent.kids.contains(a)) {
                    parent.kids.add(a);
                }
            }
        }
        DEPOTS.put(key, n);
        return n.list;
    }

    /**
     * Депо линии. Кандидаты: все дочерние зоны депо, имя которых совпадает с номером линии (так линии можно
     * назначить несколько депо), и выбранное в списке депо, если оно не является родителем одной из таких зон.
     * Рейс начинается из ближайшего к началу рейса кандидата (slotFor).
     */
    static java.util.List<Depot> candidateDepots(Level level, VDTransportLine line) {
        java.util.List<Depot> res = new ArrayList<>();
        if (line == null) {
            return res;
        }
        java.util.List<Depot> ds = depots(level);
        Depot sel = line.depotID >= 0 && line.depotID < ds.size() ? ds.get(line.depotID) : null;
        String nr = line.number == null ? "" : line.number.trim();
        if (!nr.isEmpty() && !nr.equals("null")) {
            for (Depot d : ds) {
                if (d.child && !d.d.closed && d.d.name != null && d.d.name.trim().equals(nr)) {
                    res.add(d);
                }
            }
        }
        if (sel != null && !res.contains(sel)) {
            boolean parentOfMatch = false;
            for (Depot m : res) {
                if (sel.kids.contains(m)) {
                    parentOfMatch = true;
                }
            }
            if (!parentOfMatch) {
                res.add(0, sel);
            }
        }
        return res;
    }

    /** Первое депо линии (для проверок «депо выбрано» и подписей). */
    static Depot resolveDepot(Level level, VDTransportLine line) {
        java.util.List<Depot> c = candidateDepots(level, line);
        return c.isEmpty() ? null : c.get(0);
    }

    static boolean inAnyDepot(Level level, double x, double z) {
        for (Depot d : depots(level)) {
            if (d.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** Для выпадающего списка депо в экране линии: стабильный порядок по номеру. */
    public static java.util.Set<Map.Entry<Integer, String>> sortedEntries(java.util.concurrent.ConcurrentHashMap<Integer, String> map) {
        java.util.TreeMap<Integer, String> t = new java.util.TreeMap<>();
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            t.put(e.getKey(), e.getValue());
        }
        return new java.util.LinkedHashSet<>(t.entrySet());
    }

    static final class Slot {
        long t;
        double sx;
        double sy;
        double sz;
        double yaw;
        double ex;
        double ey;
        double ez;
    }

    static final class LaneRec {
        double sx;
        double sy;
        double sz;
        double ex;
        double ey;
        double ez;
    }

    static final Map<Integer, Slot> SLOTS = new ConcurrentHashMap<>();

    private static long pkey(double x, double z) {
        return (Math.round(x * 4.0D) << 32) ^ (Math.round(z * 4.0D) & 0xFFFFFFFFL);
    }

    /**
     * Место линии в депо: пара «выезд (дорожка начинается на парковке) и въезд (дорожка заканчивается на парковке)».
     * Если выбрана дочерняя зона, берётся её пара. Если выбрано главное депо, каждой линии достаётся своя
     * свободная пара (по порядку номеров линий), пары внутри дочерних зон не трогаем.
     */
    static Slot slotFor(Level level, int lineID, VDTransportLine line) {
        return slotFor(level, lineID, line, 0.0D, 0.0D, false);
    }

    /** Место линии в ближайшем к точке (nx, nz) депо из кандидатов; без точки берётся первое депо. */
    static Slot slotFor(Level level, int lineID, VDTransportLine line, double nx, double nz, boolean hasNear) {
        if (line == null) {
            return null;
        }
        java.util.List<Depot> cands = candidateDepots(level, line);
        if (cands.isEmpty()) {
            return null;
        }
        Depot dep = cands.get(0);
        if (hasNear && cands.size() > 1) {
            double best = Double.MAX_VALUE;
            for (Depot c : cands) {
                double cx = (c.minX + c.maxX) / 2.0D;
                double cz = (c.minZ + c.maxZ) / 2.0D;
                double dist = (cx - nx) * (cx - nx) + (cz - nz) * (cz - nz);
                if (dist < best) {
                    best = dist;
                    dep = c;
                }
            }
        }
        long now = System.currentTimeMillis();
        int ckey = lineID * 100003 + dep.id;
        Slot cached = SLOTS.get(ckey);
        if (cached != null && now - cached.t < 8000L) {
            return cached.sx == Double.MAX_VALUE ? null : cached;
        }
        Slot result = computeSlot(level, lineID, line, dep);
        Slot store = result;
        if (store == null) {
            store = new Slot();
            store.sx = Double.MAX_VALUE;
        }
        store.t = now;
        SLOTS.put(ckey, store);
        return result;
    }

    private static Slot computeSlot(Level level, int lineID, VDTransportLine line, Depot dep) {
        try {
            initRefl();
            if (!reflOk) {
                return null;
            }
            if (dep.d.closed) {
                return null;
            }
            java.util.List<LaneRec> lanes = new java.util.ArrayList<>();
            java.util.Set<Long> starts = new java.util.HashSet<>();
            java.util.Set<Long> ends = new java.util.HashSet<>();
            for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.TRAFFIC_LANE).entrySet()) {
                VDTrafficLane l = new VDTrafficLane(e.getValue());
                if (!l.transport || l.getStart() == null || l.getEnd() == null) {
                    continue;
                }
                LaneRec r = new LaneRec();
                r.sx = vc(l.getStart(), 0);
                r.sy = vc(l.getStart(), 1);
                r.sz = vc(l.getStart(), 2);
                r.ex = vc(l.getEnd(), 0);
                r.ey = vc(l.getEnd(), 1);
                r.ez = vc(l.getEnd(), 2);
                lanes.add(r);
                starts.add(pkey(r.sx, r.sz));
                ends.add(pkey(r.ex, r.ez));
            }
            java.util.List<LaneRec> exits = new java.util.ArrayList<>();
            java.util.List<LaneRec> entries = new java.util.ArrayList<>();
            for (LaneRec r : lanes) {
                boolean exitInside = dep.contains(r.sx, r.sz) && !ends.contains(pkey(r.sx, r.sz));
                boolean entryInside = dep.contains(r.ex, r.ez) && !starts.contains(pkey(r.ex, r.ez));
                if (exitInside && inKids(dep, r.sx, r.sz)) {
                    exitInside = false;
                }
                if (entryInside && inKids(dep, r.ex, r.ez)) {
                    entryInside = false;
                }
                if (exitInside) {
                    exits.add(r);
                }
                if (entryInside) {
                    entries.add(r);
                }
            }
            if (exits.isEmpty()) {
                return null;
            }
            java.util.Comparator<LaneRec> bySpot = (a, b) -> a.sx != b.sx ? Double.compare(a.sx, b.sx) : Double.compare(a.sz, b.sz);
            exits.sort(bySpot);
            // пара: к каждому выезду ближайший свободный въезд
            java.util.List<LaneRec[]> pairs = new java.util.ArrayList<>();
            java.util.List<LaneRec> free = new java.util.ArrayList<>(entries);
            for (LaneRec x : exits) {
                LaneRec best = null;
                double bd = 1.0E18D;
                for (LaneRec en : free) {
                    double dd = (en.ex - x.sx) * (en.ex - x.sx) + (en.ez - x.sz) * (en.ez - x.sz);
                    if (dd < bd) {
                        bd = dd;
                        best = en;
                    }
                }
                if (best != null) {
                    free.remove(best);
                }
                pairs.add(new LaneRec[] {x, best});
            }
            // номер линии среди линий этого депо
            java.util.TreeSet<Integer> sameDepot = new java.util.TreeSet<>();
            for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.TRANSPORT_LINE).entrySet()) {
                for (Depot od : candidateDepots(level, new VDTransportLine(e.getValue()))) {
                    if (od.id == dep.id) {
                        sameDepot.add(e.getKey());
                    }
                }
            }
            int rank = new java.util.ArrayList<>(sameDepot).indexOf(lineID);
            if (rank < 0) {
                rank = 0;
            }
            LaneRec[] pair = pairs.get(rank % pairs.size());
            Slot s = new Slot();
            s.sx = pair[0].sx;
            s.sy = pair[0].sy;
            s.sz = pair[0].sz;
            s.yaw = Math.toDegrees(Math.atan2(-(pair[0].ex - pair[0].sx), pair[0].ez - pair[0].sz));
            LaneRec in = pair[1];
            s.ex = in == null ? s.sx : in.ex;
            s.ey = in == null ? s.sy : in.ey;
            s.ez = in == null ? s.sz : in.ez;
            return s;
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: \u043e\u0448\u0438\u0431\u043a\u0430 \u0432\u044b\u0431\u043e\u0440\u0430 \u043c\u0435\u0441\u0442\u0430 \u0432 \u0434\u0435\u043f\u043e: " + t);
            return null;
        }
    }

    private static boolean inKids(Depot dep, double x, double z) {
        for (Depot k : dep.kids) {
            if (k.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    private static final ThreadLocal<VDRoute> CTX_ROUTE = new ThreadLocal<>();
    private static final ThreadLocal<VDTransportLine> CTX_LINE = new ThreadLocal<>();

    public static void noteRoute(VDRoute r) {
        CTX_ROUTE.set(r);
    }

    public static void noteLine(VDTransportLine l) {
        CTX_LINE.set(l);
    }

    private static void setFinal(Object o, String field, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        if (value instanceof Double) {
            f.setDouble(o, (Double) value);
        } else if (value instanceof Float) {
            f.setFloat(o, (Float) value);
        }
    }

    /**
     * Вызывается сразу после создания точки появления транспорта в центре первой платформы маршрута.
     * Если у линии выбрано депо, транспорт появляется на выезде своей пары в депо.
     */
    public static void spawnAdjust(DirectionalPos dp, Level level) {
        try {
            VDRoute route = CTX_ROUTE.get();
            VDTransportLine line = CTX_LINE.get();
            if (route == null || line == null) {
                return;
            }
            Slot s = slotFor(level, route.lineID, line, dp.getX(), dp.getZ(), true);
            if (s == null) {
                return;
            }
            setFinal(dp, "x", s.sx);
            setFinal(dp, "y", s.sy);
            setFinal(dp, "z", s.sz);
            setFinal(dp, "direction", (float) s.yaw);
            PTMLogger.info("PTM2 \u0434\u0435\u043f\u043e: \u043b\u0438\u043d\u0438\u044f " + route.lineID + " \u0432\u044b\u0435\u0437\u0436\u0430\u0435\u0442 \u0438\u0437 \u0434\u0435\u043f\u043e \u043d\u0430 " + (int) s.sx + " " + (int) s.sz);
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: \u043e\u0448\u0438\u0431\u043a\u0430 \u0442\u043e\u0447\u043a\u0438 \u0432\u044b\u0435\u0437\u0434\u0430 \u0438\u0437 \u0434\u0435\u043f\u043e: " + t);
        }
    }



    /**
     * Рекомендуемое число автобусов линии по расписанию на день: рейсы восстанавливаются из записей расписания
     * (новый рейс начинается, когда остановка повторяется или время «откатывается»), каждый рейс занимает автобус на
     * время рейса плюс 5 минут на разворот, ответ равен наибольшему числу одновременно занятых автобусов.
     * Возвращает {рекомендуется, рейсов, оборот в минутах, интервал в минутах} или null, если расписания нет.
     */
    static int[] recommendedBuses(List<Pair<Integer, Pair<Integer, String>>> sched) {
        try {
            if (sched == null || sched.isEmpty()) {
                return null;
            }
            List<double[]> trips = new ArrayList<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            double wrap = 0.0D;
            double prevRaw = -1.0D;
            double first = 0.0D;
            double last = 0.0D;
            int count = 0;
            for (Pair<Integer, Pair<Integer, String>> e : sched) {
                int platform = e.getSecond().getFirst();
                double raw = TimeHelper.parseTime(e.getSecond().getSecond());
                if (raw < 0) {
                    continue;
                }
                if (prevRaw >= 0 && raw < prevRaw - 600.0D) {
                    wrap += 1440.0D;
                }
                double abs = raw + wrap;
                prevRaw = raw;
                if (count > 0 && seen.contains(platform)) {
                    double gap = count > 1 ? (last - first) / (count - 1) : 5.0D;
                    trips.add(new double[] {first, last + gap});
                    seen.clear();
                    count = 0;
                }
                if (count == 0) {
                    first = abs;
                }
                last = abs;
                seen.add(platform);
                count++;
            }
            if (count > 0) {
                double gap = count > 1 ? (last - first) / (count - 1) : 5.0D;
                trips.add(new double[] {first, last + gap});
            }
            if (trips.isEmpty()) {
                return null;
            }
            List<double[]> events = new ArrayList<>();
            double sumLen = 0.0D;
            for (double[] t : trips) {
                events.add(new double[] {t[0], 1.0D});
                events.add(new double[] {t[1] + 5.0D, -1.0D});
                sumLen += t[1] - t[0] + 5.0D;
            }
            events.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
            int cur = 0;
            int peak = 0;
            for (double[] ev : events) {
                cur += (int) ev[1];
                peak = Math.max(peak, cur);
            }
            trips.sort((a, b) -> Double.compare(a[0], b[0]));
            double interval = trips.size() > 1 ? (trips.get(trips.size() - 1)[0] - trips.get(0)[0]) / (trips.size() - 1) : 0.0D;
            return new int[] {peak, trips.size(), (int) Math.round(sumLen / trips.size()), (int) Math.round(interval)};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Гаражные номера по линиям с «реалистичным» расписанием: у линии есть постоянный список номеров (парк), он
     * хранится в поле «временная шкала» расписания (номер транспорта и пока пустой список рейсов). Новый автобус линии
     * получает первый номер парка, который сейчас не занят живым автобусом этой линии; если свободных нет и парк
     * не больше 40, в парк добавляется новый номер из диапазона компании. Так на линии работают одни и те же номера,
     * а их число само равно числу одновременно нужных автобусов.
     */
    public static void assignGarage(VDTrackableVehicle v, Level level, VDTransportLine line, int lineID, int routeID,
                                    List<VDTrackableVehicle> stored, long routeSet) {
        try {
            if (v == null || line == null || line.scheduleType != 1) {
                return;
            }
            int[] keyHolder = new int[1];
            VDSchedule schedule = scheduleOf(level, lineID, keyHolder);
            Integer scheduleKey = schedule == null ? null : keyHolder[0];
            if (schedule == null || scheduleKey == null) {
                return;
            }
            if (hasManualTimes(schedule)) {
                int mv = manualVehicle(level, schedule, routeID, routeSet);
                if (mv >= 0) {
                    v.vehicleID = mv;
                    return;
                }
            }
            java.util.Set<Integer> busy = new java.util.HashSet<>();
            if (stored != null) {
                for (VDTrackableVehicle o : stored) {
                    if (o != null && o != v && o.vehicleID >= 0) {
                        busy.add(o.vehicleID);
                    }
                }
            }
            for (Pair<Integer, List<Pair<Integer, Integer>>> f : schedule.timeline) {
                int n = f.getFirst();
                if (n >= 0 && !busy.contains(n)) {
                    v.vehicleID = n;
                    return;
                }
            }
            if (schedule.timeline.size() >= 40) {
                return;
            }
            String companyData = VDStorageHandler.get(level, line.companyID, VDMemoryIDs.TRANSPORT_COMPANY);
            if (companyData == null || companyData.isEmpty()) {
                return;
            }
            VDTransportCompany company = new VDTransportCompany(companyData);
            java.util.Set<Integer> used = new java.util.HashSet<>(busy);
            for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.SCHEDULE).entrySet()) {
                for (Pair<Integer, List<Pair<Integer, Integer>>> f : new VDSchedule(e.getValue()).timeline) {
                    used.add(f.getFirst());
                }
            }
            int number = -1;
            for (int attempt = 0; attempt < 30; attempt++) {
                int cand = VehicleUtils.newVehicleID(company, VehicleTypes.fromID(line.type));
                if (cand >= 0 && !used.contains(cand)) {
                    number = cand;
                    break;
                }
            }
            if (number < 0) {
                return;
            }
            schedule.timeline.add(Pair.of(number, new ArrayList<Pair<Integer, Integer>>()));
            VDStorageHandler.update(level, scheduleKey, schedule.toString());
            v.vehicleID = number;
            PTMLogger.info("PTM2 \u043f\u0430\u0440\u043a: \u043b\u0438\u043d\u0438\u044f " + lineID + " \u043f\u043e\u043b\u0443\u0447\u0438\u043b\u0430 \u043d\u043e\u0432\u044b\u0439 \u0433\u0430\u0440\u0430\u0436\u043d\u044b\u0439 \u043d\u043e\u043c\u0435\u0440 " + number
                + " (\u0432 \u043f\u0430\u0440\u043a\u0435 " + schedule.timeline.size() + ")");
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: assignGarage " + t);
        }
    }

    /** Парк линии с реалистичным расписанием: сколько гаражных номеров закреплено и какие. */
    static String fleetInfo(Level level, int lineId) {
        try {
            {
                VDSchedule sc = scheduleOf(level, lineId, null);
                if (sc != null) {
                    int fleet = 0;
                    for (Pair<Integer, List<Pair<Integer, Integer>>> f : sc.timeline) {
                        if (f.getFirst() >= 0) {
                            fleet++;
                        }
                    }
                    if (fleet == 0) {
                        return "";
                    }
                    StringBuilder b = new StringBuilder(", \u0432 \u043f\u0430\u0440\u043a\u0435 " + fleet + ": ");
                    int shown = 0;
                    for (Pair<Integer, List<Pair<Integer, Integer>>> f : sc.timeline) {
                        if (f.getFirst() < 0) {
                            continue;
                        }
                        if (shown++ > 0) {
                            b.append(", ");
                        }
                        b.append(f.getFirst());
                        if (shown >= 12) {
                            b.append("\u2026");
                            break;
                        }
                    }
                    return b.toString();
                }
            }
        } catch (Throwable t) {
            // не критично
        }
        return "";
    }

    static String depotShort(Level level, VDTransportLine line) {
        try {
            Depot d = resolveDepot(level, line);
            if (d == null) {
                return "\u043d\u0435\u0442";
            }
            int more = candidateDepots(level, line).size() - 1;
            return String.valueOf(d.d.name) + (more > 0 ? " +" + more : "");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Состояние автобуса для диагностики остановки: цель, расстояние, внутри ли платформы, таймеры, двери. */
    static String vehicleDiag(Level level, VDTrackableVehicle v) {
        try {
            long now = TimeHelper.now();
            String dist = "?";
            boolean inPos = false;
            boolean inDest = false;
            if (v.position != null && v.destination != null) {
                dist = String.valueOf((int) com.rinventor.ptm2.engine.util.Maths.simpleDistance(v.position, v.destination));
                inPos = VDUtils.getPlatformID(level, com.rinventor.ptm2.engine.computing.Converter.toPos(v.position)) == v.nextPlatformID;
                inDest = VDUtils.getPlatformID(level, com.rinventor.ptm2.engine.computing.Converter.toPos(v.destination)) == v.nextPlatformID;
            }
            return "\u043f\u043b\u0430\u0442\u0444\u043e\u0440\u043c\u0430=" + v.nextPlatformID
                + ", \u0434\u043e \u0446\u0435\u043b\u0438=" + dist
                + ", \u0432 \u0437\u043e\u043d\u0435 \u0430\u0432\u0442\u043e\u0431\u0443\u0441=" + (inPos ? "\u0434\u0430" : "\u043d\u0435\u0442")
                + ", \u0446\u0435\u043b\u044c=" + (inDest ? "\u0434\u0430" : "\u043d\u0435\u0442")
                + ", \u0441\u0442\u043e\u0438\u0442 \u0441=" + (v.stopTime > 0L ? String.valueOf((now - v.stopTime) / 1000L) : "\u043d\u0435\u0442")
                + ", \u0434\u043e \u043e\u0442\u043f\u0440.=" + (v.timeToLeave > 0L ? String.valueOf((v.timeToLeave - now) / 1000L) : "\u043d\u0435\u0442")
                + ", \u0434\u0432\u0435\u0440\u0438=" + (v.doorsOpen ? "\u043e\u0442\u043a\u0440" : "\u0437\u0430\u043a\u0440")
                + ", " + (v.virtual ? "\u0432\u0438\u0440\u0442" : "\u0444\u0438\u0437")
                + ", ai=" + (v.ai ? "\u0434\u0430" : "\u043d\u0435\u0442")
                + ", v=" + v.speed;
        } catch (Throwable t) {
            return "\u043e\u0448\u0438\u0431\u043a\u0430 " + t;
        }
    }

    /** Строка диагностики: имя депо и найденное место выезда/въезда. */
    static String depotDiag(Level level, int lineId, VDTransportLine line) {
        try {
            Depot d = resolveDepot(level, line);
            if (d == null) {
                return "\u043d\u0435\u0442";
            }
            Slot s = slotFor(level, lineId, line);
            String r = String.valueOf(d.d.name) + (d.child ? " (\u0434\u043e\u0447.)" : "") + " x " + d.minX + ".." + d.maxX + ", z " + d.minZ + ".." + d.maxZ;
            if (s == null) {
                return r + ", \u043c\u0435\u0441\u0442\u0430 \u043d\u0435\u0442 (\u0432\u044b\u0435\u0437\u0434\u043e\u0432 \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u043e)";
            }
            return r + ", \u0432\u044b\u0435\u0437\u0434 " + (int) s.sx + " " + (int) s.sz + ", \u0432\u044a\u0435\u0437\u0434 " + (int) s.ex + " " + (int) s.ez;
        } catch (Throwable t) {
            return "\u043e\u0448\u0438\u0431\u043a\u0430 " + t;
        }
    }

    /**
     * Вызывается в начале createVirtualVehicle: если это первая остановка рейса линии с депо,
     * точка появления переносится на выезд своей пары (в цепочках рейсов, например кольцо, стандартный
     * код ставит появление на дороге перед платформой).
     */
    public static int spawnFix(DirectionalPos dp, Level level, VDTransportLine line, int routeID, int platformID) {
        try {
            if (dp == null || line == null) {
                return platformID;
            }
            String raw = VDStorageHandler.get(level, routeID, VDMemoryIDs.ROUTE);
            if (raw == null || raw.isEmpty()) {
                return platformID;
            }
            VDRoute route = new VDRoute(raw);
            Slot s = slotFor(level, route.lineID, line, dp.getX(), dp.getZ(), true);
            if (s == null) {
                if (resolveDepot(level, line) != null) {
                    long nowMs = System.currentTimeMillis();
                    Long last = NO_SLOT_LOG.get(route.lineID);
                    if (last == null || nowMs - last > 60000L) {
                        NO_SLOT_LOG.put(route.lineID, nowMs);
                        PTMLogger.info("PTM2 \u0434\u0435\u043f\u043e: \u0443 \u043b\u0438\u043d\u0438\u0438 " + route.lineID + " \u0434\u0435\u043f\u043e \u0432\u044b\u0431\u0440\u0430\u043d\u043e, \u043d\u043e \u043c\u0435\u0441\u0442\u0430 (\u0432\u044b\u0435\u0437\u0434/\u0432\u044a\u0435\u0437\u0434) \u043d\u0435\u0442, \u0430\u0432\u0442\u043e\u0431\u0443\u0441 \u043f\u043e\u044f\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u043d\u0430 \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043a\u0435 " + platformID);
                    }
                }
                return platformID;
            }
            List<Integer> stops = VDUtils.parseStops(route.stops);
            if (stops == null || stops.isEmpty()) {
                return platformID;
            }
            if (stops.get(0).intValue() != platformID) {
                // у линии с депо транспорт появляется только из депо: появление на дороге у остановок подавляем
                return -1;
            }
            setFinal(dp, "x", s.sx);
            setFinal(dp, "y", s.sy);
            setFinal(dp, "z", s.sz);
            setFinal(dp, "direction", (float) s.yaw);
            PTMLogger.info("PTM2 \u0434\u0435\u043f\u043e: \u0440\u0435\u0439\u0441 " + routeID + " \u043b\u0438\u043d\u0438\u0438 " + route.lineID + " \u043f\u043e\u044f\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u043d\u0430 \u0432\u044b\u0435\u0437\u0434\u0435 " + (int) s.sx + " " + (int) s.sz);
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: spawnFix " + t);
        }
        return platformID;
    }



    private static final Map<String, double[]> PARK_WATCH = new ConcurrentHashMap<>();

    /**
     * Порог «доехал» для удаления транспорта после последнего рейса (вместо 2 блоков). Для линий с депо
     * транспорт считается доехавшим внутри зоны депо или в 6 блоках от въезда; если он стоит на месте
     * больше 75 секунд (не смог проехать в депо), он тоже убирается, а место записывается в лог.
     */
    public static double parkReach(Level level, VDTrackableVehicle v, String id) {
        try {
            if (v == null || v.lineID < 0 || v.position == null || id == null) {
                return 2.0D;
            }
            String raw = VDStorageHandler.get(level, v.lineID, VDMemoryIDs.TRANSPORT_LINE);
            if (raw == null || raw.isEmpty()) {
                return 2.0D;
            }
            Slot s = slotFor(level, v.lineID, new VDTransportLine(raw), v.destination == null ? 0.0D : vc(v.destination, 0),
                v.destination == null ? 0.0D : vc(v.destination, 2), v.destination != null);
            if (s == null) {
                return 2.0D;
            }
            double x = vc(v.position, 0);
            double z = vc(v.position, 2);
            double dist = Math.sqrt((x - s.ex) * (x - s.ex) + (z - s.ez) * (z - s.ez));
            if (dist < 3.5D) {
                PARK_WATCH.remove(id);
                return 1.0E9D;
            }
            long now = System.currentTimeMillis();
            double[] rec = PARK_WATCH.get(id);
            if (rec == null || Math.abs(rec[0] - x) > 2.0D || Math.abs(rec[1] - z) > 2.0D) {
                if (PARK_WATCH.size() > 500) {
                    PARK_WATCH.clear();
                }
                PARK_WATCH.put(id, new double[] {x, z, now});
            } else if (dist < 60.0D && now - (long) rec[2] > 60000L) {
                PARK_WATCH.remove(id);
                PTMLogger.info("PTM2 \u0434\u0435\u043f\u043e: \u043b\u0438\u043d\u0438\u044f " + v.lineID + " \u043d\u0435 \u0434\u043e\u0435\u0445\u0430\u043b\u0430 \u0434\u043e \u0434\u0435\u043f\u043e, \u0443\u0431\u0440\u0430\u043d\u0430 \u043d\u0430 " + (int) x + " " + (int) z + " (\u0432\u044a\u0435\u0437\u0434 " + (int) s.ex + " " + (int) s.ez + ")");
                return 1.0E9D;
            }
        } catch (Throwable t) {
            // не критично
        }
        return 2.0D;
    }


    /** Замена getDriveAwayDestination: после последнего рейса транспорт едет в своё место в депо. */
    public static Object driveAway(Level level, VDTrackableVehicle vehicle, Object orig) {
        try {
            if (vehicle == null || vehicle.lineID < 0) {
                return orig;
            }
            String raw = VDStorageHandler.get(level, vehicle.lineID, VDMemoryIDs.TRANSPORT_LINE);
            if (raw == null || raw.isEmpty()) {
                return orig;
            }
            VDTransportLine line = new VDTransportLine(raw);
            Slot s = slotFor(level, vehicle.lineID, line, vc(vehicle.position, 0), vc(vehicle.position, 2), true);
            if (s == null) {
                return orig;
            }
            PTMLogger.info("PTM2 \u0434\u0435\u043f\u043e: \u043b\u0438\u043d\u0438\u044f " + vehicle.lineID + " \u0435\u0434\u0435\u0442 \u0432 \u0434\u0435\u043f\u043e \u043d\u0430 " + (int) s.ex + " " + (int) s.ez);
            return vec3(orig, s.ex, 0.0D, s.ez);
        } catch (Throwable t) {
            return orig;
        }
    }

    /** На последней остановке маршрута все пассажиры выходят и никто не садится. */
    public static Pair<Integer, Integer> passengers(Level level, int current, int max, int routeID, int platformID, Pair<Integer, Integer> orig) {
        try {
            if (routeID != -1 && platformID != -1) {
                int last = lastStop(level, routeID);
                if (last == platformID) {
                    return Pair.<Integer, Integer>of(current, 0);
                }
            }
        } catch (Throwable t) {
            // не критично
        }
        return orig;
    }

    static int lastStop(Level level, int routeID) {
        String raw = VDStorageHandler.get(level, routeID, VDMemoryIDs.ROUTE);
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        java.util.List<Integer> stops = com.rinventor.ptm2.engine.computing.Converter.toIntList(new VDRoute(raw).stops);
        return stops.isEmpty() ? -1 : stops.get(stops.size() - 1);
    }

    /** Внутри депо транспорт не считает другой транспорт препятствием. */
    public static Vehicle clearance(Vehicle v, double distance, Vehicle orig, Level level) {
        if (v instanceof Transport && !PTMWorld.isClient(v)) {
            int ok = System.identityHashCode(v);
            standLog((Transport) v, ok);
            if (orig != null) {
                if (LAST_OBS.size() > 500) {
                    LAST_OBS.clear();
                }
                LAST_OBS.put(ok, new java.lang.ref.WeakReference<>(orig));
            } else {
                LAST_OBS.remove(ok);
            }
        }
        if (orig != null && v instanceof Transport && inAnyDepot(level, PTMEntity.getX(v), PTMEntity.getZ(v))) {
            return null;
        }
        return orig;
    }

    /** Внутри депо касания транспорта не считаются аварией. */
    public static boolean noCrash(Vehicle v, boolean orig, Level level) {
        if (orig && v instanceof Transport && inAnyDepot(level, PTMEntity.getX(v), PTMEntity.getZ(v))) {
            return false;
        }
        return orig;
    }

    /**
     * Движение внутри депо: помехи от других машин не учитываются, но выезжающие выезжают по очереди
     * (кто раньше появился в депо, тот раньше едет). Въезжающие едут свободно.
     */
    private static boolean depotMove(Vehicle v, Drv d, Level level, long now) {
        if (d.depotSince == 0L) {
            d.depotSince = now;
        }
        if (livingAhead(v, level)) {
            return false;
        }
        boolean leaving = ((Transport) v).getRouteID() != -1;
        if (leaving) {
            for (Vehicle o : PTMEntity.getDifferentVehicles(200.0D, v)) {
                if (!(o instanceof Transport) || ((Transport) o).getRouteID() == -1) {
                    continue;
                }
                Drv od = DRV.get(System.identityHashCode(o));
                if (od != null && od.depotSince > 0L && now - od.seen < 2000L && od.depotSince < d.depotSince
                    && now - od.depotSince < 60000L && inAnyDepot(level, PTMEntity.getX(o), PTMEntity.getZ(o))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ======================================================================
    //  Высадка на ближайшую платформу (безбилетники и конечная остановка)
    // ======================================================================

    /** Точка на ближайшей платформе (тротуар остановки) относительно точки (x, z): {x, z} или null. */
    static double[] nearestPlatformSpot(Level level, double x, double z) {
        double best = 60.0D * 60.0D;
        double[] res = null;
        for (Map.Entry<Integer, String> e : VDStorageHandler.getAll(level, VDMemoryIDs.PLATFORM).entrySet()) {
            VDPlatform p = new VDPlatform(e.getValue());
            double cx = p.getCenterX() + 0.5D;
            double cz = p.getCenterZ() + 0.5D;
            double dd = (cx - x) * (cx - x) + (cz - z) * (cz - z);
            if (p.isInside(x, z)) {
                dd = 0.0D;
            }
            if (dd < best) {
                best = dd;
                res = new double[] {cx, cz};
            }
        }
        return res;
    }

    /** Убрать игрока из автобуса и поставить на ближайшую платформу. */
    static void ejectToPlatform(Player player, Vehicle root, Vehicle sec, boolean riding, Level level) throws ReflectiveOperationException {
        EXEMPT.put(PTMEntity.getName(player), System.currentTimeMillis() + 4000L);
        int seatIdx = riding ? sec.passengerSeatIndex(player) : -1;
        if (riding) {
            srg(player, "m_8127_");
        }
        if (seatIdx >= 0 && seatIdx < sec.getSeats().size()) {
            sec.getSeats().get(seatIdx).setOccupied(false);
            sec.removePassengerFromMap(player);
            try {
                int id = (Integer) srg(sec, "m_19879_");
                new VehicleSeatClientboundPacket(id, seatIdx, false).sendToAllClients();
            } catch (Throwable t) {
                // метка занятости не критична
            }
        }
        double bx = PTMEntity.getX(root);
        double bz = PTMEntity.getZ(root);
        double[] spot = nearestPlatformSpot(level, bx, bz);
        double ty = PTMEntity.getY(root) + 1.0D;
        double tx = spot != null ? spot[0] : bx;
        double tz = spot != null ? spot[1] : bz;
        if (spot == null) {
            // платформы нет: хотя бы за пределы корпуса, вбок от автобуса
            double yaw = Math.toRadians(PTMEntity.getYaw(root));
            tx = bx + Math.cos(yaw) * (root.width / 2.0D + 2.5D);
            tz = bz + Math.sin(yaw) * (root.width / 2.0D + 2.5D);
        }
        teleportPlayer(player, tx, ty, tz);
        resyncGravity(player);
        try {
            sec.resetFloorRidingPlayers();
        } catch (Throwable t) {
            // не критично
        }
    }

    /** На конечной остановке (или при завершённом рейсе) высаживаем всех игроков на платформу. */
    static void terminalUnload(Vehicle v, Level level, List<Player> players, long now, Drv d) {
        try {
            if (now - d.termCheck < 1000L || !v.ai || v.mainUUID == null) {
                return;
            }
            d.termCheck = now;
            if (!doorsOpenAndStopped(v)) {
                return;
            }
            String raw = VDStorageHandler.get(level, v.mainUUID.toString(), VDMemoryIDs.TRACKABLE_VEHICLE);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            VDTrackableVehicle tv = new VDTrackableVehicle(raw);
            boolean terminal;
            if (tv.routeID == -1) {
                terminal = true;
            } else {
                terminal = tv.nextPlatformID != -1 && tv.nextPlatformID == lastStop(level, tv.routeID);
            }
            if (!terminal) {
                return;
            }
            for (Player p : players) {
                Vehicle sec = sectionOfPlayer(v, p);
                boolean riding = sec != null;
                if (sec == null) {
                    for (int i = 0; i < v.totalSections; i++) {
                        Vehicle cand = v.getSection(i);
                        if (cand != null && VehicleUtils.isPlayerInVehiclesRotatedAABB(p, cand)) {
                            sec = cand;
                            break;
                        }
                    }
                }
                if (sec != null) {
                    PENDING.remove(PTMEntity.getName(p));
                    ejectToPlatform(p, v, sec, riding, level);
                    PTMChat.msgNearest("\u041a\u043e\u043d\u0435\u0447\u043d\u0430\u044f \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043a\u0430, \u0432\u044b\u0445\u043e\u0434\u0438\u0442\u0435", "yellow", level,
                        PTMEntity.getX(p), PTMEntity.getY(p), PTMEntity.getZ(p));
                }
            }
        } catch (Throwable t) {
            PTMLogger.warn("PTM2 fixes: \u043e\u0448\u0438\u0431\u043a\u0430 \u0432\u044b\u0441\u0430\u0434\u043a\u0438 \u043d\u0430 \u043a\u043e\u043d\u0435\u0447\u043d\u043e\u0439: " + t);
        }
    }

    // ======================================================================
    //  «Полулетающий» игрок: клиент оставляет игроку отключённую гравитацию (флаг ходьбы по полу автобуса)
    // ======================================================================

    /** Принудительно переслать клиенту «гравитация включена»: true и сразу false в одном тике (пакет уйдёт со значением false). */
    static void resyncGravity(Player p) {
        try {
            srg(p, "m_20242_", Boolean.TRUE);
            srg(p, "m_20242_", Boolean.FALSE);
            srg(p, "m_6853_", Boolean.TRUE);
        } catch (Throwable t) {
            // не критично
        }
    }

    /** Вызывается в начале Vehicle.removeOrGoVirtual: всем игрокам рядом сбрасываем флаг гравитации. */
    public static void vehicleGone(Vehicle v) {
        try {
            if (!(v instanceof Transport) || PTMWorld.isClient(v)) {
                return;
            }
            Level level = (Level) srg(v, "m_9236_");
            List<Player> players = PTMWorld.getPlayers(level);
            if (players == null) {
                return;
            }
            double vx = PTMEntity.getX(v);
            double vz = PTMEntity.getZ(v);
            double r = Math.max(8.0D, v.length) + 4.0D;
            for (Player p : players) {
                double dx = PTMEntity.getX(p) - vx;
                double dz = PTMEntity.getZ(p) - vz;
                if (dx * dx + dz * dz < r * r) {
                    resyncGravity(p);
                }
            }
        } catch (Throwable t) {
            // не мешаем удалению
        }
    }
}
