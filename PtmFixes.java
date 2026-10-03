package com.rinventor.ptm2.fixes;

import com.rinventor.ptm2.core.properties.VDMemoryIDs;
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
    public static void tick(Level level) {
        try {
            if (level == null || PTMWorld.isClient(level)) {
                return;
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
