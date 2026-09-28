# 字幕模型文件

本目录保存后端字幕处理所需的 ONNX 模型和中文发音资产。大文件由 Git LFS 管理；克隆仓库后运行 `git lfs pull`，确认得到真正的模型文件而非 LFS 指针。

服务器部署目录：

```text
/mnt/ai-gc/models/silero_vad.onnx
/mnt/ai-gc/models/forced-alignment/zh_w2v2_tiny_fc_10ms/model_quantized.onnx
/mnt/ai-gc/models/forced-alignment/assets/*
```

本目录结构与上述 `/mnt/ai-gc/models` 下的结构相同。更新服务器模型时把本目录内容复制到数据目录，确认权限可读，并重建后端容器。模型通过只读挂载提供给后端，不打包进 JAR 或 Docker 镜像。

第三方来源与许可：

- `silero_vad.onnx`：Silero VAD，MIT，© 2020–present Silero Team；[项目与许可证](https://github.com/snakers4/silero-vad)。
- `forced-alignment/zh_w2v2_tiny_fc_10ms/model_quantized.onnx`：来自 [charsiu-js ONNX models](https://huggingface.co/mnaoizyyy/charsiu-js-models)，基于 [charsiu 中文模型](https://huggingface.co/charsiu/zh_w2v2_tiny_fc_10ms) 的量化导出，MIT；上游归属信息见 [charsiu-js ATTRIBUTION](https://github.com/mnaoizy/charsiu-js/blob/main/ATTRIBUTION.md)。
- `forced-alignment/assets/g2pm_*`：charsiu-js 的中文 G2P 资产，包含来自 [g2pM](https://github.com/kakaobrain/g2pM)（Apache-2.0）与 [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cedict)（CC BY-SA 4.0）的数据；保留第三方归属与许可。`vocab_zh.json` 为中文音素词表。

项目代码许可证不覆盖这些第三方模型及数据。分发模型时，应同时保留相应上游许可证与归属信息。

当前模型文件 SHA-256：

```text
silero_vad.onnx                                                      1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3
forced-alignment/zh_w2v2_tiny_fc_10ms/model_quantized.onnx         9576125f4d11af4239185502b695ed09629b7825b0115dfe1f2dcf222194c35d
forced-alignment/assets/g2pm_lstm.bin                               e460f9e1d342406a57557766690d54c5b40c7a0bc55c7c211b6dae89f5c4db0c
```
