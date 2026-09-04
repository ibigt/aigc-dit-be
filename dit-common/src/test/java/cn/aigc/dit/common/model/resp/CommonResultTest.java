package cn.aigc.dit.common.model.resp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonResultTest {

    @Test
    void successUsesCode200() {
        CommonResult<String> result = CommonResult.success("pong");
        assertTrue(result.isSuccess());
        assertEquals(200, result.getCode());
        assertEquals("pong", result.getData());
    }

    @Test
    void failedKeepsBusinessCode() {
        CommonResult<Void> result = CommonResult.failed(401, "未登录");
        assertFalse(result.isSuccess());
        assertEquals(401, result.getCode());
        assertEquals("未登录", result.getMessage());
    }
}
