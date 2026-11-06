package com.diting.app;

/**
 * 两套主题：深色（暖黑）与浅色（暖纸）。
 *
 * 不是调亮度，是换一套配色：底色、卡片、描边、字色、珊瑚色一起换。
 * 颜色全部以静态字段暴露，界面代码只读 Theme.XXX，切换后重建视图即可。
 */
public final class Theme {

    public static final int DARK = 0;
    public static final int LIGHT = 1;

    public static int mode = DARK;

    public static int BG;
    public static int CARD;
    public static int BORDER;
    public static int INK;
    public static int MUTED;
    public static int FAINT;
    public static int ACCENT;

    static {
        set(DARK);
    }

    private Theme() {
    }

    public static void set(int m) {
        mode = (m == LIGHT) ? LIGHT : DARK;
        if (mode == LIGHT) {
            // 暖纸：不是纯白，是偏米的一层，衬线字放上去像书页
            BG = 0xFFF3EFE6;
            CARD = 0xFFFBF8F1;
            BORDER = 0xFFDED5C4;
            INK = 0xFF24211C;
            MUTED = 0xFF6B6459;
            FAINT = 0xFF9A9285;
            // 浅底上珊瑚要压深一点，否则字压不住
            ACCENT = 0xFFC9613C;
        } else {
            BG = 0xFF1B1A18;
            CARD = 0xFF24231F;
            BORDER = 0xFF34322C;
            INK = 0xFFEFEBE2;
            MUTED = 0xFFA49C90;
            FAINT = 0xFF6E675E;
            ACCENT = 0xFFD97757;
        }
    }

    /** 当前主题下，切过去会变成什么。 */
    public static int toggled() {
        return mode == DARK ? LIGHT : DARK;
    }
}
