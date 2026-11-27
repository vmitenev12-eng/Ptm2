package com.rinventor.ptm2.fixes.board;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZoneOffset;
import net.minecraft.world.level.Level;

/**
 * Мягкая связь с модом real_climate (если он установлен и включено real_time):
 * - zoneFix: TimeHelper.zoneID возвращает пояс по долготе якоря климата вместо выбранного вручную,
 *   поэтому часы, табло и расписания ptm2 идут по тому же времени, что и небо;
 * - skipTime: ptm2 не двигает время мира по своему восходу/закату (setWorldTimeToMatchRealTime),
 *   чтобы два мода не перебивали друг друга, время мира ведёт климат.
 * Без real_climate класс ничего не меняет.
 */
public final class ClimateLink {
    private static final String CLS = "com.realclimate.climate.RealTimeService";
    private static volatile boolean resolved;
    private static volatile Method realTime;
    private static volatile Method offset;

    private ClimateLink() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        try {
            Class<?> c = Class.forName(CLS, true, ClimateLink.class.getClassLoader());
            realTime = c.getMethod("isRealTime");
            offset = c.getMethod("zoneOffsetSeconds", Level.class);
        } catch (Throwable t) {
            realTime = null;
            offset = null;
        }
        resolved = true;
    }

    private static boolean active() {
        resolve();
        Method m = realTime;
        if (m == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(m.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Вызывается из TimeHelper.zoneID(level) перед возвратом. */
    public static ZoneId zoneFix(ZoneId orig, Level level) {
        try {
            if (level != null && active()) {
                Object sec = offset.invoke(null, level);
                return ZoneOffset.ofTotalSeconds((Integer) sec);
            }
        } catch (Throwable t) {
            // остаётся пояс из настроек ptm2
        }
        return orig;
    }

    /** Вызывается в начале TimeHelper.setWorldTimeToMatchRealTime(level): true - ничего не делать. */
    public static boolean skipTime(Level level) {
        return active();
    }
}
