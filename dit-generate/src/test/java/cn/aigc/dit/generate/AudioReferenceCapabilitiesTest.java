package cn.aigc.dit.generate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioReferenceCapabilitiesTest {

    @Test
    void distinguishesSeedanceVersions() {
        AudioReferenceCapabilities v25 = AudioReferenceCapabilities.resolve(
                AdapterCatalog.SEEDANCE_2_5, AdapterCatalog.PROVIDER_OFFICIAL,
                "video", "doubao-seedance-2-5-260628");
        assertTrue(v25.supported());
        assertEquals(10, v25.maxReferences());
        assertTrue(v25.audioOnly());

        AudioReferenceCapabilities v20 = AudioReferenceCapabilities.resolve(
                AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.PROVIDER_OFFICIAL,
                "video", "doubao-seedance-2-0-260128");
        assertTrue(v20.supported());
        assertEquals(3, v20.maxReferences());
        assertFalse(v20.audioOnly());

        AudioReferenceCapabilities v15 = AudioReferenceCapabilities.resolve(
                AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.PROVIDER_OFFICIAL,
                "video", "doubao-seedance-1-5-pro-250528");
        assertFalse(v15.supported());
        assertEquals(0, v15.maxReferences());
    }
}
