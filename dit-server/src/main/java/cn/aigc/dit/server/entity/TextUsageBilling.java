package cn.aigc.dit.server.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("text_usage_billing")
public class TextUsageBilling {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String billingRef;
    private Long userId;
    private Long seriesId;
    private Long modelId;
    private String operation;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedPromptTokens;
    private Long creditCost;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
