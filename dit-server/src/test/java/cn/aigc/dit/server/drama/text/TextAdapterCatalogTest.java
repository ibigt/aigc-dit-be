package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.generate.AdapterCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextAdapterCatalogTest {

    @Test
    void keysStayOutOfImageVideoCatalog() {
        assertNull(AdapterCatalog.find(TextAdapterCatalog.AGNES_TEXT));
        assertNull(AdapterCatalog.find(TextAdapterCatalog.OPENAI_COMPAT));
    }

    @Test
    void infersOfficialAndCustom() {
        assertEquals(TextAdapterCatalog.AGNES_TEXT, TextAdapterCatalog.infer("官方API", "agnes-2.5-flash"));
        assertEquals(TextAdapterCatalog.OPENAI_COMPAT, TextAdapterCatalog.infer("自定义配置", "gpt-4o-mini"));
        assertEquals(TextAdapterCatalog.OPENAI_COMPAT, TextAdapterCatalog.infer("手动配置", "qwen-plus"));
        assertEquals("agnes-2.5-flash", TextAdapterCatalog.require(TextAdapterCatalog.AGNES_TEXT).defaultModelKey());
        assertEquals(TextAdapterCatalog.AGNES_DEFAULT_ENDPOINT,
                TextAdapterCatalog.require(TextAdapterCatalog.AGNES_TEXT).defaultEndpoint());
    }
}
