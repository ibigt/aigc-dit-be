package cn.aigc.dit.server.mapper.stat;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class UserTaskStat {
    private Long userId;
    private Long requests;
    private OffsetDateTime lastSeen;
}
