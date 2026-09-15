package cn.aigc.dit.server.service;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetServiceNameTest {

    @Test
    void customNameIsTrimmed() {
        assertEquals("角色对白", AssetService.assetName(" 角色对白 ", "voice.mp3"));
    }

    @Test
    void blankCustomNameFallsBackToFilename() {
        assertEquals("voice", AssetService.assetName(" ", "voice.mp3"));
    }

    @Test
    void rejectsNameLongerThanDatabaseLimit() {
        assertThrows(BizException.class, () -> AssetService.assetName("音".repeat(129), "voice.mp3"));
    }
}
