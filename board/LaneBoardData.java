package com.rinventor.ptm2.fixes.board;

import java.util.ArrayList;
import java.util.List;

/** Данные табло направлений по полосам: набор допустимых значков и разбор строки с полосами. */
public final class LaneBoardData {
    /** Значки полос: имена совпадают с существующими знаками мода sign_lane_<имя>_blue. */
    public static final String[] TOKENS = {
        "straight", "straight_left", "straight_right", "left", "right", "left_right", "straight_u_turn",
        "diag_left", "diag_right", "straight_diag_left", "straight_diag_right", "littlebit_left", "littlebit_right"
    };
    public static final int MAX_LANES = 10;

    private LaneBoardData() {
    }

    public static boolean valid(String token) {
        for (String t : TOKENS) {
            if (t.equals(token)) {
                return true;
            }
        }
        return false;
    }

    /** Строка вида «straight_left,straight,right» в список; пустая или неверная строка даёт одну полосу «straight». */
    public static List<String> parse(String s) {
        List<String> res = new ArrayList<>();
        if (s != null) {
            for (String part : s.split(",")) {
                String t = part.trim();
                if (valid(t) && res.size() < MAX_LANES) {
                    res.add(t);
                }
            }
        }
        if (res.isEmpty()) {
            res.add("straight");
        }
        return res;
    }

    public static String join(List<String> lanes) {
        StringBuilder sb = new StringBuilder();
        for (String t : lanes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /** Маска допустимых манёвров по углам поворота (бит i = угол i*45 градусов вправо): 0 прямо, 1 и 7 диагонали, 2-3 право, 5-6 лево, 4 разворот. */
    public static int mask(String token) {
        String c = token.toUpperCase();
        int mask = 0;
        if (c.contains("U_TURN")) {
            mask |= 1 << 4;
            c = c.replace("U_TURN", "");
        }
        if (c.contains("DIAG_LEFT") || c.contains("LITTLEBIT_LEFT")) {
            mask |= 1 << 7;
            c = c.replace("DIAG_LEFT", "").replace("LITTLEBIT_LEFT", "");
        }
        if (c.contains("DIAG_RIGHT") || c.contains("LITTLEBIT_RIGHT")) {
            mask |= 1 << 1;
            c = c.replace("DIAG_RIGHT", "").replace("LITTLEBIT_RIGHT", "");
        }
        if (c.contains("STRAIGHT")) {
            mask |= 1;
            c = c.replace("STRAIGHT", "");
        }
        if (c.contains("LEFT")) {
            mask |= (1 << 5) | (1 << 6);
        }
        if (c.contains("RIGHT")) {
            mask |= (1 << 2) | (1 << 3);
        }
        return mask;
    }
}
