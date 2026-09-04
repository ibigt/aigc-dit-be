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
    private String provider;
    private String category;
    private List<String> capabilities;
    private Map<String, Object> pricing;
    private Boolean enabled;
    private String description;
    private String badge;
    private String logo;
}
