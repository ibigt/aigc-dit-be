package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "素材")
public class AssetResp {
    private Long id;
    private String name;
    private String title;
    private String mimeType;
    private String kind;
    private String type;
    private String storageKey;
    private String posterKey;
    private String url;
    private String posterUrl;
    private String src;
    private String poster;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Long durationMs;
    private OffsetDateTime createdAt;
}
