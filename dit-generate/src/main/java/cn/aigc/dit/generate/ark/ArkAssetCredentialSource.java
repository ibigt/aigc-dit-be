package cn.aigc.dit.generate.ark;

/**
 * 角色审核用的火山 Access Key / Secret Key。
 * 管理端加密保存的值优先，没有时再退回进程配置。
 */
public interface ArkAssetCredentialSource {

    String accessKey();

    String secretKey();
}
