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
        assertTrue(system.contains("按本镜对白长短、动作节拍"));
        assertFalse(system.contains("全部镜头必须等于用户指定的秒数"));
        assertFalse(system.contains("交代空间与二人关系"));
        assertFalse(system.contains("不要写镜头语言"));
        assertFalse(system.contains("门被推开"));
    }

    @Test
    void shotUserAsksForPerShotDuration() {
        String user = DramaTextPrompts.shotUser("{}", "洛白", 8, 30, 6);
        assertTrue(user.contains("自行估计"));
        assertTrue(user.contains("不要所有镜头填同一个秒数"));
        assertTrue(user.contains("本集目标总时长约 30 秒"));
        assertTrue(user.contains("目标大约 6 个分镜"));
        assertFalse(user.contains("必须是 8，不得改成其它秒数"));
    }
}
