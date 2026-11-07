package com.diting.app;

import android.graphics.drawable.GradientDrawable;

/**
 * 两套主题：夜纸（深）与日纸（浅）。
 *
 * 不是调亮度，是换一整套「纸、墨、朱砂」：
 *   BG      纸面
 *   CARD    压在纸上的另一张纸（气泡、输入框、月历）
 *   BORDER  一根头发的分隔线
 *   INK     墨
 *   MUTED   淡墨
 *   FAINT   更淡的墨（注脚）
 *   ACCENT  朱砂（印章、选中、提醒）
 *   WASH    朱砂兑水后的纸色（输入框、时间格）
 *
 * 界面只读 Theme.XXX；切换主题后重建视图即可。
 */
public final class Theme {

    public static final int DARK = 0;
    public static final int LIGHT = 1;

    public static int mode = DARK;

    public static int BG;
    public static int BG_DEEP;
    public static int CARD;
    public static int BORDER;
    public static int INK;
    public static int MUTED;
    public static int FAINT;
    public static int ACCENT;
    public static int ACCENT_DEEP;
    public static int WASH;
    /** 朱砂底上的字色：浅色主题用纸色，深色主题用墨色。 */
    public static int ON_ACCENT;

    static {
        set(DARK);
    }

    private Theme() {
    }

    public static void set(int m) {
        mode = (m == LIGHT) ? LIGHT : DARK;
        if (mode == LIGHT) {
            // 日纸：米色纸，不是纯白；朱砂压深一点，压得住纸。
            BG = 0xFFF2EADA;
            BG_DEEP = 0xFFEAE0CC;
            CARD = 0xFFF8F1E3;
            BORDER = 0xFFD8C9B2;
            INK = 0xFF2A241C;
            MUTED = 0xFF6D6151;
            FAINT = 0xFF9B8D78;
            ACCENT = 0xFFB4472F;
            ACCENT_DEEP = 0xFF8C3322;
            WASH = 0xFFEADBC8;
            ON_ACCENT = 0xFFF7EFDF;
        } else {
            // 夜纸：暖黑，不是纯黑；纸纹靠两层深浅撑出来。
            BG = 0xFF16130F;
            BG_DEEP = 0xFF120F0C;
            CARD = 0xFF211C16;
            BORDER = 0xFF3A3127;
            INK = 0xFFEFE5D2;
            MUTED = 0xFFA99A83;
            FAINT = 0xFF78695A;
            ACCENT = 0xFFD8795B;
            ACCENT_DEEP = 0xFFE89372;
            WASH = 0xFF2A2119;
            ON_ACCENT = 0xFF1A1511;
        }
    }

    /** 当前主题下，切过去会变成什么。 */
    public static int toggled() {
        return mode == DARK ? LIGHT : DARK;
    }

    /** 纸面：从上到下深一点点，整屏不至于死板。 */
    public static GradientDrawable paper() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG, BG_DEEP});
    }
}
