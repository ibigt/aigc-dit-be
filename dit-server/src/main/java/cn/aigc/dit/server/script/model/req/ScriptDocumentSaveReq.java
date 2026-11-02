package cn.aigc.dit.server.script.model.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ScriptDocumentSaveReq {
    @NotNull
    private String document;
    /** 按集保存时携带。有值时以该列表为准，document 由服务端重新拼接。 */
    private List<ScriptEpisodeScriptReq> episodeScripts;
}
