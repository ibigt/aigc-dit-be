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
}
