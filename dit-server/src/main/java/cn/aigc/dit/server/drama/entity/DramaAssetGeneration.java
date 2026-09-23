package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("drama_asset_generation")
public class DramaAssetGeneration {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;
    private Long userId;
    private String storageKey;
    private Long genTaskId;
    /** generate | upload | library */
    private String source;
    private String label;
    private String resolution;
    private Long libraryAssetId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
