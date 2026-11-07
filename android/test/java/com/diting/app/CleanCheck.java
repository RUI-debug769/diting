package com.diting.app;

/**
 * Clean 的纯 JVM 自测：不依赖 Android、不需要 JUnit。
 *
 * 口径是「宁可漏删，不可误删」：删掉的应该是噪声，留下的应该是信息。
 * 运行见 android/check_clean.sh。
 */
public final class CleanCheck {

    private static int failed = 0;

    public static void main(String[] args) {
        // 无条件删：句首语气词 + 标点收尾
        eq("嗯，那个会又拖到十二点，他们第三次讲同一页 PPT。",
           "那个会又拖到十二点，他们第三次讲同一页 PPT。");
        eq("呃我觉得这个方案不行", "我觉得这个方案不行");
        eq("哦，我知道了", "我知道了");

        // 多字填充
        eq("那个那个，我说明天再说", "我说明天再说");
        eq("就是说你这个方案有问题", "你这个方案有问题");
        eq("这个这个，怎么说呢，先这样吧", "先这样吧");
        eq("你知道吗，他昨天没来", "他昨天没来");
        eq("然后就是，那个那个，我觉得可以", "我觉得可以");
        eq("对吧，我们明天见", "我们明天见");

        // 边界单字：句首/句尾/标点旁删
        eq("啊，今天下雨了", "今天下雨了");
        eq("唉，算了", "算了");
        eq("今天啊，真热", "今天，真热");
        eq("是啊，我知道了", "是，我知道了");
        eq("额", "");
        eq("嗯嗯嗯", "");
        eq("「嗯」", "");
        eq("嗯。", "");

        // 不能误删：额不是语气词的时候、唉不是叹气的时候
        eq("他说的金额是三百", "他说的金额是三百");
        eq("金额额度", "金额额度");
        eq("金额。", "金额。");
        eq("唉声叹气", "唉声叹气");
        eq("呃逆了两声", "呃逆了两声");
        eq("他哦豁了一声", "他哦豁了一声");

        // 不能误删：填充词嵌在固定说法/实词里的时候
        eq("也就是说，方案要改", "也就是说，方案要改");
        eq("不管怎么说呢，先这样", "不管怎么说呢，先这样");
        eq("他坐在对吧台旁边", "他坐在对吧台旁边");

        // 有实义的指示代词/连词保留
        eq("那个会改到明天", "那个会改到明天");
        eq("这个方案先放着", "这个方案先放着");
        eq("我然后去了公司", "我然后去了公司");
        eq("他就是不同意", "他就是不同意");

        // 标点清理
        eq("嗯，，，今天", "今天");
        eq("今天。。。真热", "今天。真热");

        // isNoise：整段只剩歧义词
        isNoise("然后", true);
        isNoise("那个", true);
        isNoise("嗯嗯", true);
        isNoise("「嗯」", true);
        isNoise("那个会", false);
        isNoise("然后我去了公司", false);
        isNoise("", true);

        if (failed > 0) {
            System.out.println("CleanCheck 失败 " + failed + " 条");
            System.exit(1);
        }
        System.out.println("CleanCheck 全部通过");
    }

    private static void eq(String in, String want) {
        String got = Clean.clean(in);
        if (!want.equals(got)) {
            failed++;
            System.out.println("FAIL clean(\"" + in + "\")");
            System.out.println("  want: \"" + want + "\"");
            System.out.println("  got : \"" + got + "\"");
        }
    }

    private static void isNoise(String in, boolean want) {
        boolean got = Clean.isNoise(in);
        if (got != want) {
            failed++;
            System.out.println("FAIL isNoise(\"" + in + "\") want " + want + " got " + got);
        }
    }
}
