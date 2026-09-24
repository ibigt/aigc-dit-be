package cn.aigc.dit.server.script.model.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ScriptChatReq {
    @NotBlank
    @Size(max = 20000)
    private String message;
    private Boolean thinking = Boolean.TRUE;
}
