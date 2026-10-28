package cn.aigc.dit.generate.describe;

/**
 * 从模型回复中拆出 【identity】 / 【t2i_prompt】。
 */
public final class CaptionSections {

    private CaptionSections() {
    }

    public static ImageDescribeResult parse(String text) {
        String raw = text == null ? "" : text.trim();
        String identity = "";
        String t2i = "";
        if (raw.contains("【identity】") || raw.contains("【t2i_prompt】")) {
            String[] parts = raw.split("【");
            for (String part : parts) {
                String p = part.trim();
                if (p.startsWith("identity】")) {
                    identity = p.substring("identity】".length()).trim();
                    int cut = identity.indexOf("【t2i_prompt】");
                    if (cut >= 0) {
                        identity = identity.substring(0, cut).trim();
                    }
                } else if (p.startsWith("t2i_prompt】")) {
                    t2i = p.substring("t2i_prompt】".length()).trim();
                }
            }
        }
        if (t2i.isEmpty()) {
            t2i = raw;
        }
        if (identity.isEmpty()) {
            int nl = t2i.indexOf('\n');
            identity = (nl > 0 ? t2i.substring(0, nl) : t2i);
            if (identity.length() > 120) {
                identity = identity.substring(0, 120);
            }
        }
        return new ImageDescribeResult(identity, t2i, raw);
    }
}
