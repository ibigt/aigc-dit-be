package cn.aigc.dit.generate.ark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArkKeysTest {

    @Test
    void stripsBearerPrefixQuotesAndWhitespace() {
        assertEquals("sk-live", ArkKeys.normalize("  Bearer sk-live  "));
        assertEquals("sk-live", ArkKeys.normalize("\"sk-live\""));
        assertEquals("sk-live", ArkKeys.normalize("bearer sk-live\n"));
        assertEquals("", ArkKeys.normalize("   "));
    }
}
