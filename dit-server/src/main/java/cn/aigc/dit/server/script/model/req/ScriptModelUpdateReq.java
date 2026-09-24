package cn.aigc.dit.server.script.model.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScriptModelUpdateReq {
    @NotNull
    private Long modelId;
}
