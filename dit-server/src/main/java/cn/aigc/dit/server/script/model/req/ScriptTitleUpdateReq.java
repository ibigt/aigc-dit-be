package cn.aigc.dit.server.script.model.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ScriptTitleUpdateReq {
    @NotBlank
    @Size(max = 256)
    private String title;
}
