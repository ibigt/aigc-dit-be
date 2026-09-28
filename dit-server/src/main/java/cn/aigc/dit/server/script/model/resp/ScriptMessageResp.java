package cn.aigc.dit.server.script.model.resp;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ScriptMessageResp {
    private Long id;
    private String role;
    private String content;
    private String thinkingProcess;
    private OffsetDateTime createdAt;
}
