package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "创作端可见模型（仅 enabled，不含密钥与内部地址）")
public class ModelPublicResp {
    private Long id;
    private String name;
    private String modelKey;
    private String adapterKey;
    private String provider;
    private String category;
    private List<String> capabilities;
    private Boolean supportsAudioReference;
    private Integer maxAudioReferences;
    private Boolean supportsAudioOnlyReference;
    private Map<String, Object> pricing;
    /** 可安全下发的语音选项：voices/formats/defaultVoice/defaultFormat/minSpeed/maxSpeed。 */
    private Map<String, Object> audioOptions;
    private Boolean enabled;
    private String description;
    private String badge;
    private String logo;
}
