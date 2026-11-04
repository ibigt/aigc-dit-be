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
        assertTrue(result.realisticPrompt().contains("真人实拍写真"));
        assertTrue(result.realisticPrompt().contains("棚拍柔光"));
        assertTrue(result.realisticPrompt().contains("不要CG数字人"));
        assertFalse(result.realisticPrompt().contains("原创CGI"));
        assertFalse(result.realisticPrompt().contains("正脸近景"));
        assertTrue(ImageDescribePrompts.CHARACTER_REALISM.contains("真人实拍写真"));
        assertTrue(ImageDescribePrompts.CHARACTER_REALISM.contains("灰底棚拍光影"));
        assertFalse(ImageDescribePrompts.CHARACTER_REALISM.contains("原创 CGI 数字人"));
        assertTrue(ImageDescribePrompts.CHARACTER_FULL.contains("CGI 数字人"));
    }
}
