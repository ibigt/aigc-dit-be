package cn.aigc.dit.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("ark_review_credential")
public class ArkReviewCredential {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private String accessKeyEnc;
    private String secretKeyEnc;
    private OffsetDateTime updatedAt;
}
