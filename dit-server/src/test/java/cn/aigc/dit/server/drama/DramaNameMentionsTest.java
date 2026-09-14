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
}
