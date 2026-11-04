-- 高级工作台模板库元数据（需求 4.3.1.2）：模板编码、分类、标签、封面、版本、模型适配器、节点拓扑、默认参数
-- 拓扑节点坐标使用缩略图坐标系（440x230），前端据此绘制 SVG 缩略图，避免加载完整画布。

ALTER TABLE workflow ADD COLUMN IF NOT EXISTS template_code   VARCHAR(64);
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS category         VARCHAR(32);
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS tags             JSONB        NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS cover_path       VARCHAR(256);
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS version          VARCHAR(16) NOT NULL DEFAULT '1.0';
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS model_adapter    VARCHAR(64);
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS topology         JSONB;
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS default_params   JSONB        NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN workflow.template_code IS '模板编码：官方模板唯一标识，自定义模板为空';
COMMENT ON COLUMN workflow.category IS '模板分类：video / image / audio';
COMMENT ON COLUMN workflow.tags IS '模板标签数组，用于标签筛选';
COMMENT ON COLUMN workflow.cover_path IS '模板封面图路径';
COMMENT ON COLUMN workflow.version IS '模板版本号';
COMMENT ON COLUMN workflow.model_adapter IS '模型适配器：minimax-h3 / wan2.2 / ltx2.3 / sdxl / comfyui';
COMMENT ON COLUMN workflow.topology IS '节点拓扑缩略图数据：nodes（x,y,w,label,tone）与 edges（节点下标对）';
COMMENT ON COLUMN workflow.default_params IS '实例化时套用的默认参数：ratio / resolution / duration / copies 等';

UPDATE workflow SET
    template_code = 'minimax-h3-i2v',
    category = 'video',
    tags = '["图生视频","多参考","Minimax-H3"]'::jsonb,
    cover_path = '/media/comfy-racer-poster.webp',
    version = '1.0',
    model_adapter = 'minimax-h3',
    default_params = '{"ratio":"16:9","resolution":"2K","duration":5,"copies":1}'::jsonb,
    topology = '{"nodes":[{"x":14,"y":30,"w":86,"label":"加载首帧","tone":"rose"},{"x":14,"y":140,"w":86,"label":"加载尾帧","tone":"pink"},{"x":130,"y":85,"w":90,"label":"创意脚本","tone":"yellow"},{"x":250,"y":85,"w":80,"label":"视频生成","tone":"green"},{"x":250,"y":170,"w":80,"label":"参考音频","tone":"violet"},{"x":360,"y":110,"w":72,"label":"视频剪辑","tone":"blue"}],"edges":[[2,3],[0,3],[1,3],[3,5],[4,5]]}'::jsonb
WHERE title = 'Minimax-H3 图生视频';

UPDATE workflow SET
    template_code = 'wan22-t2v',
    category = 'video',
    tags = '["文生视频","脚本驱动","Wan2.2"]'::jsonb,
    cover_path = '/media/wan-22-poster.webp',
    version = '1.0',
    model_adapter = 'wan2.2',
    default_params = '{"ratio":"16:9","resolution":"1080P","duration":5,"copies":1}'::jsonb,
    topology = '{"nodes":[{"x":30,"y":90,"w":92,"label":"视频提示词","tone":"yellow"},{"x":170,"y":90,"w":88,"label":"视频生成","tone":"green"},{"x":300,"y":90,"w":76,"label":"视频剪辑","tone":"blue"}],"edges":[[0,1],[1,2]]}'::jsonb
WHERE title = 'Wan2.2 文生视频';

UPDATE workflow SET
    template_code = 'ltx-shot-variations',
    category = 'video',
    tags = '["镜头变体","参考图","LTX"]'::jsonb,
    cover_path = '/media/quick-create/video-neon-ride.jpg',
    version = '1.0',
    model_adapter = 'ltx2.3',
    default_params = '{"ratio":"9:16","resolution":"1080P","duration":5,"copies":2}'::jsonb,
    topology = '{"nodes":[{"x":12,"y":95,"w":88,"label":"镜头脚本","tone":"yellow"},{"x":125,"y":30,"w":80,"label":"人物出图","tone":"amber"},{"x":125,"y":160,"w":80,"label":"场景出图","tone":"amber"},{"x":240,"y":30,"w":76,"label":"近景视频","tone":"green"},{"x":240,"y":160,"w":76,"label":"广角视频","tone":"green"},{"x":350,"y":95,"w":72,"label":"视频剪辑","tone":"blue"}],"edges":[[0,1],[0,2],[1,3],[2,4],[3,5],[4,5]]}'::jsonb
WHERE title = 'LTX-Video 文生视频';

UPDATE workflow SET
    template_code = 'sdxl-image-board',
    category = 'image',
    tags = '["分镜","批量出图","SDXL"]'::jsonb,
    cover_path = '/media/quick-create/image-fashion.jpg',
    version = '1.0',
    model_adapter = 'sdxl',
    default_params = '{"ratio":"16:9","resolution":"2K","copies":4}'::jsonb,
    topology = '{"nodes":[{"x":14,"y":95,"w":86,"label":"分镜描述","tone":"yellow"},{"x":130,"y":20,"w":72,"label":"镜头 1","tone":"amber"},{"x":130,"y":75,"w":72,"label":"镜头 2","tone":"amber"},{"x":130,"y":130,"w":72,"label":"镜头 3","tone":"amber"},{"x":130,"y":185,"w":72,"label":"镜头 4","tone":"amber"},{"x":250,"y":95,"w":80,"label":"分镜表","tone":"blue"}],"edges":[[0,1],[0,2],[0,3],[0,4],[1,5],[2,5],[3,5],[4,5]]}'::jsonb
WHERE title = 'SDXL 基础出图';

UPDATE workflow SET
    template_code = 'minimax-h3-comfy',
    category = 'video',
    tags = '["ComfyUI","原生节点","参数调试"]'::jsonb,
    cover_path = '/media/comfy-racer-poster.webp',
    version = '1.0',
    model_adapter = 'comfyui',
    default_params = '{"ratio":"16:9","resolution":"720P","duration":5,"copies":1}'::jsonb,
    topology = '{"nodes":[{"x":10,"y":20,"w":64,"label":"使用说明","tone":"gray"},{"x":10,"y":95,"w":72,"label":"加载图像","tone":"amber"},{"x":95,"y":60,"w":76,"label":"分辨率选择","tone":"pink"},{"x":95,"y":140,"w":76,"label":"缩放总像素","tone":"pink"},{"x":185,"y":160,"w":70,"label":"读取尺寸","tone":"rose"},{"x":190,"y":70,"w":86,"label":"H3 原生节点","tone":"green"},{"x":300,"y":40,"w":78,"label":"保存视频","tone":"blue"},{"x":10,"y":175,"w":64,"label":"模型链接","tone":"gray"},{"x":270,"y":140,"w":64,"label":"尺寸参考","tone":"gray"}],"edges":[[0,5],[1,4],[2,5],[3,4],[4,5],[5,6]]}'::jsonb
WHERE title = 'Minimax-H3 ComfyUI 原生流程';

-- 官方模板：SDXL 高清修手与补帧（需求 4.3.1.1）
INSERT INTO workflow (user_id, title, description, type, model_label, origin, demo_key, source_path, builtin, node_count, graph, template_code, category, tags, cover_path, version, model_adapter, default_params, topology)
VALUES
    (NULL, 'SDXL 高清修手与补帧', '以 SDXL 重绘修复手部与材质细节，并按目标分辨率放大输出。', 'image', 'SDXL', '光驭画布 · 高清修复', 'sdxl-restore', NULL, TRUE, 8,
     '{"nodes":[],"edges":[],"demo":"sdxl-restore"}'::jsonb,
     'sdxl-restore', 'image', '["高清","修手","补帧","SDXL"]'::jsonb, '/media/quick-create/image-product.jpg', '1.0', 'sdxl',
     '{"ratio":"1:1","resolution":"2K","copies":2}'::jsonb,
     '{"nodes":[{"x":10,"y":80,"w":76,"label":"加载模型","tone":"rose"},{"x":10,"y":20,"w":82,"label":"正向提示词","tone":"yellow"},{"x":10,"y":145,"w":82,"label":"负向提示词","tone":"yellow"},{"x":105,"y":80,"w":72,"label":"加载图像","tone":"amber"},{"x":190,"y":80,"w":84,"label":"缩放总像素","tone":"pink"},{"x":285,"y":80,"w":74,"label":"K 采样器","tone":"violet"},{"x":285,"y":165,"w":74,"label":"VAE 解码","tone":"red"},{"x":370,"y":80,"w":66,"label":"保存图像","tone":"blue"}],"edges":[[0,5],[1,5],[2,5],[3,4],[4,5],[5,6],[6,7]]}'::jsonb);
