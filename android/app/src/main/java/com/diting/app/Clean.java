package com.diting.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 送模型前的水口词清洗（派生的「干净副本」）。
 *
 * 为什么只清副本：schema 里 {@code original} 是证据，错字、断句、语气词一个都不改
 * （见 schema/record.md）。但转写里的口头填充很容易被模型抄进它写的句子——尤其提示词
 * 要求「必须引原话细节」「引语只许用原话」的时候。所以原话照旧落盘，只把清洗后的副本
 * 拼进请求体；三种兜底选句前也过一遍。
 *
 * 口径保守，宁可漏删不可误删：
 * - 无条件删：嗯/呃/哦/噢/喔，以及多字的「那个那个/这个这个/就是说/怎么说呢/
 *   你知道吗/对吧」等。
 * - 只在句首、句尾、标点旁删：啊/唉/诶/欸/额——「金额」「额度」里的额不能被误删，
 *   「唉声叹气」里的唉也不能。
 * - 不删：这个/那个/然后/就是/其实/反正——它们是歧义的（「那个会」「这个方案」
 *   有实义），交给提示词按语境判断。
 */
public final class Clean {

    private Clean() {
    }

    /** 任何上下文里都只是填充、不承载信息的词。 */
    private static final Pattern ALWAYS = Pattern.compile(
            "(?:嗯(?!哼)|呃(?!逆)|哦(?!豁)|噢|喔)+"
          + "|(?:那个){2,}"
          + "|(?:这个){2,}"
          + "|(?:就是){2,}"
          + "|(?<!也)就是说"
          + "|(?<!不管)怎么说呢"
          + "|你知道吗"
          + "|你知道吧"
          + "|知道吧"
          + "|(?:对吧|是吧|对不对)(?=[，,。！？!?…、；;：:\\s]|$)"
          + "|然后就是"
    );

    /** 句首或标点后的单字填充；额只从这一侧删（保护「金额/额度」）。 */
    private static final Pattern EDGE_LEAD = Pattern.compile(
            "(^|[，,。！？!?…、；;：:\\s])"
          + "(?:(?:啊|诶|欸)|(?:额)|(?:唉(?!声)))+"
    );

    /** 句尾或标点前的单字填充；不删额（「金额。」不能被删成「金。」）。 */
    private static final Pattern EDGE_TRAIL = Pattern.compile(
            "(?:(?:啊|诶|欸)|(?:唉(?!声)))+(?=[，,。！？!?…、；;：:\\s]|$)"
    );

    /** 句中连着出现两三次的单字填充（啊/唉/诶/欸）；不含额，避免「金额额度」被误伤。 */
    private static final Pattern REPEATED_SINGLE = Pattern.compile("(?:啊|唉|诶|欸){2,}");

    /** 删掉填充词后只剩空引号/空括号。 */
    private static final Pattern EMPTY_QUOTES = Pattern.compile(
            "「\\s*」|『\\s*』|“\\s*”|\"\\s*\"|（\\s*）|\\(\\s*\\)"
    );

    private static final Pattern SPACE_BEFORE_PUNCT =
            Pattern.compile("\\s+([，,。！？!?…、；;：:])");
    private static final Pattern SPACE_AFTER_PUNCT =
            Pattern.compile("([，,。！？!?…、；;：:])\\s+");
    private static final Pattern DUP_COMMA = Pattern.compile("[，,、]{2,}");
    private static final Pattern DUP_FULLSTOP = Pattern.compile("。{2,}");
    private static final Pattern COMMA_BEFORE_STOP = Pattern.compile("[，,、](?=[。！？!?])");
    private static final Pattern LEADING_PUNCT =
            Pattern.compile("^[，,、；;：:。！？!?…\\s]+");
    private static final Pattern TRAILING_COMMA =
            Pattern.compile("[，,、；;：:]+$");
    private static final Pattern PUNCT_ONLY =
            Pattern.compile("[\\p{P}\\s]+");

    /** 整段只剩这些词时，它不成句，兜底不该把它当细节念出来。 */
    private static final Set<String> AMBIGUOUS = new HashSet<String>(Arrays.asList(
            "那个", "这个", "然后", "就是", "其实", "反正", "还有", "一下",
            "所以", "但是", "不过", "而且", "那", "这"
    ));

    /**
     * 去掉口水词，返回一份只给模型看的文本。原始记录不受影响。
     *
     * @param s 转写原话；null 或空白返回空串
     */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\u3000', ' ').replace("\u200b", "").trim();
        if (t.isEmpty()) {
            return "";
        }

        t = ALWAYS.matcher(t).replaceAll("");
        t = EDGE_LEAD.matcher(t).replaceAll("$1");
        t = EDGE_TRAIL.matcher(t).replaceAll("");
        t = REPEATED_SINGLE.matcher(t).replaceAll("");
        t = EMPTY_QUOTES.matcher(t).replaceAll("");

        t = SPACE_BEFORE_PUNCT.matcher(t).replaceAll("$1");
        t = SPACE_AFTER_PUNCT.matcher(t).replaceAll("$1");
        t = DUP_COMMA.matcher(t).replaceAll("，");
        t = DUP_FULLSTOP.matcher(t).replaceAll("。");
        t = COMMA_BEFORE_STOP.matcher(t).replaceAll("");
        t = LEADING_PUNCT.matcher(t).replaceAll("");
        t = TRAILING_COMMA.matcher(t).replaceAll("");
        t = t.trim();

        // 清完只剩标点，等于没有内容。
        if (PUNCT_ONLY.matcher(t).matches()) {
            return "";
        }
        return t;
    }

    /**
     * 清洗后是不是「不成句」：空串，或整段只剩「然后」「那个」这类歧义词。
     * 兜底挑细节时用它跳过没有信息量的片段。
     */
    public static boolean isNoise(String s) {
        String t = clean(s);
        if (t.isEmpty()) {
            return true;
        }
        return AMBIGUOUS.contains(t);
    }
}
