package cn.aigc.dit.generate.caption;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 烧中文字幕用的字体。优先用配置路径，否则探测容器 / Windows 常见字体。
 */
@Slf4j
public final class CaptionFonts {

    static final List<String> CANDIDATES = List.of(
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/opentype/noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/msyhbd.ttc",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/simsun.ttc",
            "/System/Library/Fonts/PingFang.ttc",
            "/Library/Fonts/Arial Unicode.ttf"
    );

    private CaptionFonts() {
    }

    public static Path resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().normalize();
            }
            log.warn("configured caption font missing: {}", configured);
        }
        for (String candidate : CANDIDATES) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}
