package cn.aigc.dit.server.script.model.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScriptDocumentSaveReq {
    @NotNull
    private String document;
}
