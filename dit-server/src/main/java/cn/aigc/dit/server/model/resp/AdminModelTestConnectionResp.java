package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "文本模型测连接结果")
public class AdminModelTestConnectionResp {

    private boolean ok;
    private String message;

    public static AdminModelTestConnectionResp success() {
        AdminModelTestConnectionResp resp = new AdminModelTestConnectionResp();
        resp.setOk(true);
        resp.setMessage("连接成功");
        return resp;
    }
}
