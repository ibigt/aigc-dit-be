package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "积分流水")
public class CreditLedgerResp {
    private Long id;
    private Long changeAmt;
    private Long balanceAfter;
    private String reason;
    private String refId;
    private String remark;
    private String type;
    private String title;
    private OffsetDateTime createdAt;
}
