package cn.aigc.dit.server.drama;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DramaNameMentionsTest {

    @Test
    void ordersNamesByFirstAppearanceNotPoolOrder() {
        List<String> pool = List.of("洛白", "夏安", "局长");
        assertEquals(List.of("夏安", "洛白"),
                DramaNameMentions.mentionedInOrder(pool, "全景。风扇转动，夏安往白板贴照片，洛白用书盖脸。"));
        assertEquals(List.of("洛白"),
                DramaNameMentions.mentionedInOrder(pool, "特写。洛白在桌后用书盖脸装睡。"));
    }

    @Test
    void exactMentionDoesNotPullDreamVariantFromBaseName() {
        List<String> pool = List.of("洛白", "洛白·入梦", "夏安");
        assertEquals(List.of("洛白"),
                DramaNameMentions.mentionedExactInOrder(pool, "洛白拿出怀表。"));
        assertEquals(List.of("洛白·入梦"),
                DramaNameMentions.mentionedExactInOrder(pool, "洛白·入梦漂浮。"));
    }

    @Test
    void exactMentionPrefersLongerNameOverSubstring() {
        List<String> pool = List.of("局长", "赵局长", "夏安");
        assertEquals(List.of("赵局长"),
                DramaNameMentions.mentionedExactInOrder(pool, "赵局长：洛老弟，别睡了。"));
        assertEquals(List.of("局长"),
                DramaNameMentions.mentionedExactInOrder(pool, "局长摔档案。"));
    }
}
