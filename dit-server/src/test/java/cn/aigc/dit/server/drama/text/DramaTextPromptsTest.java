package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaTextPromptsTest {

    @Test
    void shotSchemaAsksForVisibleActionNotPlotBeats() {
        String system = DramaTextPrompts.shotSystem();

        assertTrue(system.contains("全景。风扇转动，夏安往白板贴照片，洛白用书盖脸。"));
        assertTrue(system.contains("按动作/对白里出场先后排序"));
        assertTrue(system.contains("动作只写一个人时"));
        assertTrue(system.contains("只列本镜被使用或被拿在手上的道具"));
        assertTrue(system.contains("画面出入点"));
        assertTrue(system.contains("2～3 个连续可见动作"));
        assertTrue(system.contains("不要写 compiled_prompt"));
        assertFalse(system.contains("交代空间与二人关系"));
        assertFalse(system.contains("不要写镜头语言"));
        assertFalse(system.contains("门被推开"));
    }
}
