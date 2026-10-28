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
}
