-- 真人写实风改为半写实 3D / 二次元写实，避免 Seedance 把参考图判成实拍人脸。
UPDATE drama_series
SET style_prefix = '高精度二次元写实，半写实3D渲染，精致CG质感，3D国漫轻写实厚涂，棚拍柔光，写实皮肤毛发与布料质感，不要卡通平涂线稿',
    updated_at = NOW()
WHERE style_key = 'live-realism';

UPDATE drama_asset
SET image_prompt = REPLACE(
        image_prompt,
        '真人写实风，当代都市实拍，现代常服，电影级摄影，自然肤质与真实光影，浅景深，胶片质感，不要古装，不要武侠仙侠，不要卡通漫画线稿',
        '高精度二次元写实，半写实3D渲染，精致CG质感，3D国漫轻写实厚涂，棚拍柔光，写实皮肤毛发与布料质感，不要卡通平涂线稿'),
    description = REPLACE(
        description,
        '真人写实风，当代都市实拍，现代常服，电影级摄影，自然肤质与真实光影，浅景深，胶片质感，不要古装，不要武侠仙侠，不要卡通漫画线稿',
        '高精度二次元写实，半写实3D渲染，精致CG质感，3D国漫轻写实厚涂，棚拍柔光，写实皮肤毛发与布料质感，不要卡通平涂线稿'),
    updated_at = NOW()
WHERE COALESCE(image_prompt, '') LIKE '%真人写实风，当代都市实拍%'
   OR COALESCE(description, '') LIKE '%真人写实风，当代都市实拍%';

UPDATE drama_asset
SET image_prompt = REPLACE(
        image_prompt,
        '电影级三维渲染，次世代数字人质感，皮肤次表面散射，电影布光与浅景深，胶片调色，当代都市常服，不要卡通漫画线稿，不要古装，不要武侠仙侠',
        '高精度二次元写实，半写实3D渲染，精致CG质感，3D国漫轻写实厚涂，棚拍柔光，写实皮肤毛发与布料质感，不要卡通平涂线稿'),
    description = REPLACE(
        description,
        '电影级三维渲染，次世代数字人质感，皮肤次表面散射，电影布光与浅景深，胶片调色，当代都市常服，不要卡通漫画线稿，不要古装，不要武侠仙侠',
        '高精度二次元写实，半写实3D渲染，精致CG质感，3D国漫轻写实厚涂，棚拍柔光，写实皮肤毛发与布料质感，不要卡通平涂线稿'),
    updated_at = NOW()
WHERE COALESCE(image_prompt, '') LIKE '%次世代数字人质感%'
   OR COALESCE(description, '') LIKE '%次世代数字人质感%';

UPDATE drama_asset
SET image_prompt = REPLACE(image_prompt, '当代都市人物，穿现代常服', '当代都市角色，穿现代常服'),
    description = REPLACE(description, '当代都市人物，穿现代常服', '当代都市角色，穿现代常服'),
    updated_at = NOW()
WHERE COALESCE(image_prompt, '') LIKE '%当代都市人物，穿现代常服%'
   OR COALESCE(description, '') LIKE '%当代都市人物，穿现代常服%';

UPDATE drama_asset
SET image_prompt = REPLACE(image_prompt, '当代都市数字人，穿现代常服，三维角色渲染', '当代都市角色，穿现代常服'),
    description = REPLACE(description, '当代都市数字人，穿现代常服，三维角色渲染', '当代都市角色，穿现代常服'),
    updated_at = NOW()
WHERE COALESCE(image_prompt, '') LIKE '%当代都市数字人，穿现代常服%'
   OR COALESCE(description, '') LIKE '%当代都市数字人，穿现代常服%';

UPDATE drama_series
SET style_prefix = REPLACE(
        style_prefix,
        '当代都市写实常服与日常面料，不要洛丽塔，不要哥特，不要汉服古装，不要卡通平涂线稿',
        '写实皮肤毛发与布料质感，不要卡通平涂线稿'),
    updated_at = NOW()
WHERE style_key = 'live-realism'
  AND COALESCE(style_prefix, '') LIKE '%当代都市写实常服与日常面料%';

UPDATE drama_asset
SET image_prompt = REPLACE(
        image_prompt,
        '当代都市写实常服与日常面料，不要洛丽塔，不要哥特，不要汉服古装，不要卡通平涂线稿',
        '写实皮肤毛发与布料质感，不要卡通平涂线稿'),
    description = REPLACE(
        description,
        '当代都市写实常服与日常面料，不要洛丽塔，不要哥特，不要汉服古装，不要卡通平涂线稿',
        '写实皮肤毛发与布料质感，不要卡通平涂线稿'),
    updated_at = NOW()
WHERE COALESCE(image_prompt, '') LIKE '%当代都市写实常服与日常面料%'
   OR COALESCE(description, '') LIKE '%当代都市写实常服与日常面料%';
