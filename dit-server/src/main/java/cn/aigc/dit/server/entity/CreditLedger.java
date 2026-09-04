package cn.aigc.dit.server.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("credit_ledger")
public class CreditLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long changeAmt;
    private Long balanceAfter;
    private String reason;
    private String refId;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
