package cn.aigc.dit.server.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("asset")
public class Asset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String name;
    private String mimeType;
    /** image / video / audio */
    private String kind;
    private String storageKey;
    private String posterKey;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
