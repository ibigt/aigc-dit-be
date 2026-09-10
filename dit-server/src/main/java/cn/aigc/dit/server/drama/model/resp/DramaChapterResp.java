package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "漫剧章节")
public class DramaChapterResp {

    private Long id;
    private Long seriesId;
    private Long episodeId;
    private Integer chapterIndex;
    private String name;
    private Integer sceneIndex;
    private String body;
    private String status;
    private List<DramaShotResp> shots = new ArrayList<>();
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
