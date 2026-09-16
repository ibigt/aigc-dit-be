package cn.aigc.dit.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticationFilterTest {

    @Test
    void acceptsQueryTokenOnlyForBrowserLoadedMediaAndCaptionResources() {
        assertTrue(accepts("/api/media"));
        assertTrue(accepts("/api/drama/shots/12/captions.vtt"));
        assertTrue(accepts("/api/drama/episodes/34/film/captions.vtt"));

        assertFalse(accepts("/api/drama/shots/12"));
        assertFalse(accepts("/api/drama/episodes/34/film"));
        assertFalse(accepts("/api/drama/shots/not-a-number/captions.vtt"));
    }

    private static boolean accepts(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return JwtAuthenticationFilter.acceptsQueryToken(request);
    }
}
