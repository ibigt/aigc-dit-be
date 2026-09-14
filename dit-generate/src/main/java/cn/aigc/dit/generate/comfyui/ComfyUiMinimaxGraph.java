package cn.aigc.dit.generate.comfyui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MiniMax-H3 ComfyUI API 工作流（无 subgraph）。t2v/i2v 走 fl2va，r2v 走 ref2va。
 */
public final class ComfyUiMinimaxGraph {

    public static final String CLIP = "qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors";
    public static final String VIDEO_VAE = "minimax_h3_video_vae_fp16.safetensors";
    public static final String AUDIO_VAE = "minimax_h3_audio_vae_fp32.safetensors";
    public static final String UNET_T2V = "minimax_h3_fl2va_pruned_int8_convrot.safetensors";
    public static final String UNET_R2V = "minimax_h3_ref2va_pruned_int8_convrot.safetensors";
    public static final String LORA_T2V = "minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors";
    public static final String LORA_R2V = "minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors";
    public static final int T2V_STEPS = 8;
    public static final int R2V_STEPS = 4;

    private ComfyUiMinimaxGraph() {
    }

    public static Map<String, Object> textToVideo(String prompt, int width, int height, int length,
                                                 int seed, String unet, String lora, int steps) {
        return imageToVideo(prompt, width, height, length, seed, unet, lora, steps, null, null);
    }

    public static Map<String, Object> imageToVideo(String prompt, int width, int height, int length,
                                                  int seed, String unet, String lora, int steps,
                                                  String firstFrame, String lastFrame) {
        Graph graph = new Graph();
        Stack stack = loaders(graph, unet, lora);
        Map<String, Object> miniMax = new LinkedHashMap<>();
        miniMax.put("clip", ref(stack.clip, 0));
        miniMax.put("vae", ref(stack.videoVae, 0));
        miniMax.put("prompt", prompt);
        miniMax.put("width", width);
        miniMax.put("height", height);
        miniMax.put("length", length);
        if (firstFrame != null && !firstFrame.isBlank()) {
            String load = graph.add("LoadImage", Map.of("image", firstFrame));
            miniMax.put("first_frame", ref(load, 0));
        }
        if (lastFrame != null && !lastFrame.isBlank()) {
            String load = graph.add("LoadImage", Map.of("image", lastFrame));
            miniMax.put("last_frame", ref(load, 0));
        }
        String cond = graph.add("MiniMaxH3ImageToVideo", miniMax);
        return finish(graph, stack, cond, seed, steps);
    }

    public static Map<String, Object> referenceToVideo(String prompt, int width, int height, int length,
                                                      int seed, String unet, String lora, int steps,
                                                      List<String> imageFiles, List<String> videoFiles,
                                                      List<String> audioFiles) {
        Graph graph = new Graph();
        Stack stack = loaders(graph, unet, lora);
        Map<String, Object> miniMax = new LinkedHashMap<>();
        miniMax.put("clip", ref(stack.clip, 0));
        miniMax.put("vae", ref(stack.videoVae, 0));
        miniMax.put("audio_vae", ref(stack.audioVae, 0));
        miniMax.put("prompt", prompt);
        miniMax.put("width", width);
        miniMax.put("height", height);
        miniMax.put("length", length);
        miniMax.put("ref_image_size", "match");
        List<String> images = imageFiles == null ? List.of() : imageFiles;
        for (int i = 0; i < images.size(); i++) {
            String load = graph.add("LoadImage", Map.of("image", images.get(i)));
            miniMax.put("ref_images.ref_image_" + i, ref(load, 0));
        }
        List<String> videos = videoFiles == null ? List.of() : videoFiles;
        for (int i = 0; i < videos.size(); i++) {
            Map<String, Object> vhs = new LinkedHashMap<>();
            vhs.put("video", videos.get(i));
            vhs.put("force_rate", 24.0);
            vhs.put("custom_width", 0);
            vhs.put("custom_height", 0);
            vhs.put("frame_load_cap", 0);
            vhs.put("skip_first_frames", 0);
            vhs.put("select_every_nth", 1);
            String load = graph.add("VHS_LoadVideo", vhs);
            miniMax.put("ref_videos.ref_video_" + i, ref(load, 0));
            miniMax.put("ref_video_audios.ref_video_audio_" + i, ref(load, 2));
        }
        List<String> audios = audioFiles == null ? List.of() : audioFiles;
        for (int i = 0; i < audios.size(); i++) {
            String load = graph.add("LoadAudio", Map.of("audio", audios.get(i)));
            miniMax.put("ref_audios.ref_audio_" + i, ref(load, 0));
        }
        String cond = graph.add("MiniMaxH3ReferenceToVideo", miniMax);
        return finish(graph, stack, cond, seed, steps);
    }

    private static Stack loaders(Graph graph, String unet, String lora) {
        String unetId = graph.add("UNETLoader", Map.of(
                "unet_name", unet,
                "weight_dtype", "default"));
        String model = graph.add("LoraLoaderModelOnly", Map.of(
                "model", ref(unetId, 0),
                "lora_name", lora,
                "strength_model", 1.0));
        String clip = graph.add("CLIPLoader", Map.of(
                "clip_name", CLIP,
                "type", "minimax",
                "device", "default"));
        String videoVae = graph.add("VAELoader", Map.of("vae_name", VIDEO_VAE));
        String audioVae = graph.add("VAELoader", Map.of("vae_name", AUDIO_VAE));
        return new Stack(model, clip, videoVae, audioVae);
    }

    private static Map<String, Object> finish(Graph graph, Stack stack, String cond, int seed, int steps) {
        String noise = graph.add("RandomNoise", Map.of("noise_seed", seed));
        String sampler = graph.add("KSamplerSelect", Map.of("sampler_name", "res_multistep"));
        String sigmas = graph.add("BasicScheduler", Map.of(
                "model", ref(stack.model, 0),
                "scheduler", "simple",
                "steps", steps,
                "denoise", 1.0));
        String guider = graph.add("BasicGuider", Map.of(
                "model", ref(stack.model, 0),
                "conditioning", ref(cond, 0)));
        String sampled = graph.add("SamplerCustomAdvanced", Map.of(
                "noise", ref(noise, 0),
                "guider", ref(guider, 0),
                "sampler", ref(sampler, 0),
                "sigmas", ref(sigmas, 0),
                "latent_image", ref(cond, 1)));
        String frames = graph.add("VAEDecode", Map.of(
                "samples", ref(sampled, 0),
                "vae", ref(stack.videoVae, 0)));
        String audio = graph.add("VAEDecodeAudio", Map.of(
                "samples", ref(sampled, 0),
                "vae", ref(stack.audioVae, 0)));
        String video = graph.add("CreateVideo", Map.of(
                "images", ref(frames, 0),
                "audio", ref(audio, 0),
                "fps", 24.0));
        graph.add("SaveVideo", Map.of(
                "video", ref(video, 0),
                "filename_prefix", "dit_minimax",
                "format", "auto",
                "codec", "auto"));
        return graph.nodes;
    }

    private static List<Object> ref(String nodeId, int slot) {
        return List.of(nodeId, slot);
    }

    private static final class Graph {
        private final Map<String, Object> nodes = new LinkedHashMap<>();
        private int seq = 1;

        private String add(String classType, Map<String, Object> inputs) {
            String id = String.valueOf(seq++);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("class_type", classType);
            node.put("inputs", inputs);
            nodes.put(id, node);
            return id;
        }
    }

    private record Stack(String model, String clip, String videoVae, String audioVae) {
    }
}
