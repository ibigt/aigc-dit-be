package cn.aigc.dit.generate.ark;

/**
 * 清洗方舟 API Key：去掉粘贴进来的 Bearer 前缀、引号和空白。
 */
public final class ArkKeys {

    private ArkKeys() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().replace("\r", "").replace("\n", "");
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
