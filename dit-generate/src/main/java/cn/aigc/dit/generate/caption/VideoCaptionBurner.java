package cn.aigc.dit.generate.caption;

import java.util.List;

/**
 * 把字幕烧进已落盘的成片，返回新的 storage key；失败时调用方应保留原 key。
 */
public interface VideoCaptionBurner {

    String burn(String storageKey, List<CaptionCue> cues);
}
