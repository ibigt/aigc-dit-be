package cn.aigc.dit.server.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("recharge_order")
public class RechargeOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String requestId;
    private Long userId;
    private Integer amountYuan;
    private Long creditAmount;
    private String paymentChannel;
    private String status;
    private String providerTradeNo;
    private String qrCode;
    private OffsetDateTime paidAt;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
