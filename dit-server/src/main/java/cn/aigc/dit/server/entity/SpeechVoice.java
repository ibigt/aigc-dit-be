package cn.aigc.dit.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("speech_voice")
public class SpeechVoice {
    private Long id;
    private String provider;
    private String voiceId;
    private String name;
    private String language;
    private Integer sortOrder;
    private Boolean enabled;
}
