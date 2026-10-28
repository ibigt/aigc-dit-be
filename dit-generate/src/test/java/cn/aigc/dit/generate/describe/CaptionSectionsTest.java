package cn.aigc.dit.generate.describe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptionSectionsTest {

    @Test
    void parseSplitsIdentityAndT2iSections() {
        ImageDescribeResult result = CaptionSections.parse(
                "前言忽略\n【identity】中年男性短发灰鬓\n【t2i_prompt】方脸浓眉，深灰外套，站姿全身");
        assertEquals("中年男性短发灰鬓", result.identity());
        assertEquals("方脸浓眉，深灰外套，站姿全身", result.t2iPrompt());
        assertTrue(result.assembledPrompt().startsWith("中年男性短发灰鬓"));
        assertTrue(result.assembledPrompt().contains("方脸浓眉"));
    }

    @Test
    void parseFallsBackWhenSectionsMissing() {
        ImageDescribeResult result = CaptionSections.parse("第一行身份锚点\n其余可直接当提示词的正文");
        assertEquals("第一行身份锚点", result.identity());
        assertEquals("第一行身份锚点\n其余可直接当提示词的正文", result.t2iPrompt());
    }
}
