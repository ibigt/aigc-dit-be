package cn.aigc.dit.server.script.model.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ScriptTaskCreateReq {
    @Size(max = 256)
    private String title;

    @NotBlank
    private String mode;

    @Size(max = 128)
    private String genre;

    @Min(1)
    @Max(99)
    private Integer targetEpisodes = 6;

    private Long modelId;

    @Size(max = 200000)
    private String inputText;

    private List<String> attachments;
}
