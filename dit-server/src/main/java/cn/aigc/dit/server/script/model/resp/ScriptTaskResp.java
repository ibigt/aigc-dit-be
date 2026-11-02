package cn.aigc.dit.server.script.model.resp;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ScriptTaskResp {
    private Long id;
    private String title;
    private String mode;
    private String status;
    private String genre;
    private Integer targetEpisodes;
    private Long modelId;
    private String modelName;
    private String inputText;
    private String document;
    private Map<String, Object> result;
    private List<String> attachments;
    private Long dramaSeriesId;
    /** 已关联短剧时，剧本里尚未写入对应章节原文的集数。 */
    private Integer unimportedEpisodeCount;
    private Integer version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<ScriptMessageResp> messages;
}
