package cn.aigc.dit.server.mapper.stat;

import lombok.Data;

@Data
public class NamedCount {
    private String name;
    private Long cnt;
}
