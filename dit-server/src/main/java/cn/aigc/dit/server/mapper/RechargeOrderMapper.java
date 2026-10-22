package cn.aigc.dit.server.mapper;

import cn.aigc.dit.server.entity.RechargeOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrder> {

    @Insert("""
            INSERT INTO recharge_order
              (order_no, request_id, user_id, amount_yuan, credit_amount, payment_channel,
               status, provider_trade_no, qr_code, paid_at)
            VALUES
              (#{orderNo}, #{requestId}, #{userId}, #{amountYuan}, #{creditAmount}, #{paymentChannel},
               #{status}, #{providerTradeNo}, #{qrCode}, #{paidAt})
            ON CONFLICT (user_id, request_id) DO NOTHING
            """)
    int insertIfAbsent(RechargeOrder order);
}
