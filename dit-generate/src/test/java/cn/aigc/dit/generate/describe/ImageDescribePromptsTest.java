package cn.aigc.dit.generate.describe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageDescribePromptsTest {

    @Test
    void forAssetTypeMapsCharacterSceneProp() {
        assertEquals(ImageDescribePrompts.CHARACTER_FULL, ImageDescribePrompts.forAssetType("角色"));
        assertEquals(ImageDescribePrompts.SCENE, ImageDescribePrompts.forAssetType("场景"));
        assertEquals(ImageDescribePrompts.PROP, ImageDescribePrompts.forAssetType("道具"));
        assertEquals(ImageDescribePrompts.CHARACTER_FULL, ImageDescribePrompts.forAssetType(null));
        assertEquals(ImageDescribePrompts.CHARACTER_FULL, ImageDescribePrompts.forAssetType("其他"));
    }

    @Test
    void scenePromptAvoidsCharacterCgiTail() {
        assertFalse(ImageDescribePrompts.SCENE.contains("CGI 数字人"));
        assertFalse(ImageDescribePrompts.SCENE.contains("第二个人"));
        assertTrue(ImageDescribePrompts.SCENE.contains("禁止人物"));
        assertTrue(ImageDescribePrompts.PROP.contains("禁止人物"));
        assertTrue(ImageDescribePrompts.CHARACTER_FULL.contains("CGI 数字人"));
    }

    @Test
    void forFaceRatioSplitsCloseupAndFull() {
        assertEquals(ImageDescribePrompts.CHARACTER_CLOSEUP, ImageDescribePrompts.forFaceRatio(0.25));
        assertEquals(ImageDescribePrompts.CHARACTER_FULL, ImageDescribePrompts.forFaceRatio(0.1));
        assertEquals(ImageDescribePrompts.CHARACTER_FULL, ImageDescribePrompts.forFaceRatio(null));
    }

    @Test
    void characterAnchorsSkipCompositionAndCgi() {
        String prompt = ImageDescribePrompts.CHARACTER_ANCHORS;
        assertTrue(prompt.contains("年龄"));
        assertTrue(prompt.contains("发型"));
        assertTrue(prompt.contains("服装"));
        assertTrue(prompt.contains("湿身"));
        assertTrue(prompt.contains("不要决定构图"));
        assertTrue(prompt.contains("不要写「原创CGI数字人」"));
        assertFalse(prompt.contains("人物写成原创"));
    }

    @Test
    void appearanceJoinsAnchorsWithoutCgiTail() {
        ImageDescribeResult result = new ImageDescribeResult("二十五岁短发", "圆脸，白衬衫，湿身", "raw");
        assertEquals("二十五岁短发。圆脸，白衬衫，湿身", result.appearance());
        assertFalse(result.appearance().contains("原创CGI"));
        assertTrue(result.assembledPrompt().contains("原创CGI数字人"));
        assertEquals(
                "二十五岁短发。圆脸，白衬衫，湿身。真人实拍，不要3D渲染，不要CG数字人，不要塑料皮肤。",
                result.realisticPrompt());
        assertTrue(result.realisticPrompt().indexOf("圆脸") < result.realisticPrompt().indexOf("真人实拍"));
        assertTrue(result.realisticPrompt().endsWith("不要塑料皮肤。"));
        assertFalse(result.realisticPrompt().contains("棚拍柔光"));
        assertFalse(result.realisticPrompt().contains("可见毛孔"));
        assertFalse(result.realisticPrompt().contains("原创CGI"));
        assertFalse(result.realisticPrompt().contains("正脸近景"));
        assertEquals(
                "圆脸。真人实拍，不要3D渲染，不要CG数字人，不要塑料皮肤。",
                new ImageDescribeResult("", "圆脸", "raw").realisticPrompt());
        assertTrue(ImageDescribePrompts.CHARACTER_REALISM.contains("黑底就写黑底"));
        assertTrue(ImageDescribePrompts.CHARACTER_REALISM.contains("风格约束写在提示词最后"));
        assertTrue(ImageDescribePrompts.CHARACTER_REALISM.contains(
                "肤色按原图写（冷白、暖白、小麦、浅黄，只写最接近的一种）"));
        assertTrue(ImageDescribePrompts.CHARACTER_REALISM.contains("五官与脸型细节 → 肤色 → 发型鬓角灰白"));
        assertTrue(ImageDescribePrompts.CHARACTER_REALISM.contains("年龄气质 + 最可识别的外形锚点（发型/五官/标志配饰）"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("灰底棚拍光影"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("自然肤质与可见毛孔"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("真人实拍写真"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("毛孔"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("法令纹"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("和常见脸的差别"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("杏眼"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("五官只按画面左侧近景写"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("网红脸"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("原创 CGI 数字人"));
        assertTrue(ImageDescribePrompts.CHARACTER_FULL.contains("CGI 数字人"));
        assertTrue(ImageDescribePrompts.CHARACTER_FULL.contains("灰底棚拍光影"));
        assertFalse(ImageDescribePrompts.CHARACTER_FULL.contains("肤色按原图写"));
    }
}
