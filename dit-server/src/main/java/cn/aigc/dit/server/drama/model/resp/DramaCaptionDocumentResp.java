package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "分镜字幕文档（校对用）")
public class DramaCaptionDocumentResp {

    private Long shotId;
    private String status;
    private String sourceType;
    private String processorVersion;
    private Long durationMs;
    private Double alignmentConfidence;
    private Double alignmentCoverage;
    private String fallbackReason;
    private String modelVersion;
    private String rawText;
    private String normalizedText;
    private String captionVideoUrl;
    private String subtitleJsonUrl;
    private List<CueItem> cues = new ArrayList<>();

    @Data
    public static class CueItem {
        private Long startMs;
        private Long endMs;
        private String text;
    }
}
