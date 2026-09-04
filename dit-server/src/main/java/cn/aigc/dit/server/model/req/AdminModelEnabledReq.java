package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "启停模型")
public class AdminModelEnabledReq {

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}
