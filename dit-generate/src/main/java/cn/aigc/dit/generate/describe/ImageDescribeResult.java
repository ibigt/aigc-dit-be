package cn.aigc.dit.generate.describe;

/**
 * 图生文结构化结果：identity 短句 + 可粘贴 T2I 正文。
 */
public record ImageDescribeResult(String identity, String t2iPrompt, String raw) {

    public String assembledPrompt() {
        String id = identity == null ? "" : identity.trim();
        String body = t2iPrompt == null ? "" : t2iPrompt.trim();
        if (id.isEmpty()) {
            return body;
        }
        if (body.startsWith(id)) {
            return body;
        }
        return id + "。原创CGI数字人，电影级仿真人。" + body;
    }

    /** 与 {@link #assembledPrompt()} 同一拼接位置，尾句改成写实真人实拍。 */
    public static final String REALISM_CLAUSE =
            "真人实拍写真，自然肤质与可见毛孔，真实发丝与布料质感，棚拍柔光，不要3D渲染，不要CG数字人，不要塑料皮肤。";

    public String realisticPrompt() {
        String id = identity == null ? "" : identity.trim();
        String body = stripCgi(t2iPrompt == null ? "" : t2iPrompt.trim());
        if (id.isEmpty()) {
            return body;
        }
        if (body.startsWith(id)) {
            return body;
        }
        return id + "。" + REALISM_CLAUSE + body;
    }

    private static String stripCgi(String text) {
        return text.replace("原创CGI数字人，电影级仿真人。", "")
                .replace("原创CGI数字人，电影级仿真人", "")
                .replace("原创 CGI 数字人（电影级仿真人即可），", "")
                .replace("原创 CGI 数字人（电影级仿真人即可）", "")
                .trim();
    }

    /**
     * 只拼外形锚点。出片前自动定妆不用 {@link #assembledPrompt()}，那一句会带上 CGI 数字人。
     */
    public String appearance() {
        String id = identity == null ? "" : identity.trim();
        String body = t2iPrompt == null ? "" : t2iPrompt.trim();
        String merged;
        if (id.isEmpty()) {
            merged = body;
        } else if (body.isEmpty() || body.startsWith(id)) {
            merged = body.isEmpty() ? id : body;
        } else {
            merged = id + "。" + body;
        }
        return merged.replace("原创CGI数字人，电影级仿真人。", "")
                .replace("原创CGI数字人，电影级仿真人", "")
                .trim();
    }
}
