package cn.aigc.dit.generate.ark;

/**
 * 方舟素材库管控面：{@code CreateAssetGroup}、{@code CreateAsset}、{@code GetAsset}、{@code DeleteAsset}。
 */
public interface ArkAssetApi {

    String createAssetGroup(String name, String description, String groupType, String projectName);

    String createAsset(String groupId, String url, String name, String projectName);

    ArkAssetView getAsset(String assetId, String projectName);

    void deleteAsset(String assetId, String projectName);
}
