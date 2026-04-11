package dev.cppide.core.ai

/**
 * Hardcoded list of on-device LLM models the IDE knows how to download.
 *
 * All entries are in the `.litertlm` format consumed by Google's LiteRT-LM
 * Android SDK (`com.google.ai.edge.litertlm:litertlm-android`). GGUF /
 * llama.cpp is intentionally not used — LiteRT-LM gives us a single
 * maintained AAR, GPU + NPU acceleration, and 3–7× lower inference RAM
 * than llama.cpp for the same model weights.
 *
 * Sizes below are taken from the live HuggingFace repositories at
 * catalog-write time and are used only for pre-download UI. The download
 * service always trusts the HTTP Content-Length it sees at runtime.
 *
 * Device-specific variants (e.g. `Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm`
 * for Snapdragon 8 Gen 2) are intentionally omitted — we ship the generic
 * int4 build that runs everywhere and let LiteRT-LM pick CPU or GPU at
 * runtime via [dev.cppide.core.ai] configuration.
 */
object ModelCatalog {

    /**
     * Qwen 3 0.6B — the smallest viable chat model on the LiteRT catalog.
     * Fits on 2–3 GB RAM phones with a comfortable margin. Quality is
     * limited but it answers on devices where nothing else will.
     */
    val QWEN_3_0_6B = ModelInfo(
        id = "qwen3-0.6b",
        displayName = "Qwen 3 0.6B",
        description = "Alibaba Qwen 3, 0.6B params. ~614 MB download. " +
            "Smallest model in the catalog — runs on 2–3 GB RAM phones.",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
        fileName = "Qwen3-0.6B.litertlm",
        sizeBytes = 614L * 1024 * 1024,
    )

    /**
     * Gemma 3 1B int4 — low-end default. ~584 MB download, ~0.8 GB RAM.
     * Best quality-per-byte in the sub-1GB tier. The "int4" generic
     * build runs on any arm64 device; chipset-specific NPU variants
     * exist upstream but aren't used here.
     */
    val GEMMA_3_1B_INT4 = ModelInfo(
        id = "gemma-3-1b-int4",
        displayName = "Gemma 3 1B (int4)",
        description = "Google Gemma 3, 1B params, 4-bit. ~584 MB download. " +
            "Default for mid-range phones (3+ GB RAM).",
        url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
        fileName = "gemma3-1b-it-int4.litertlm",
        sizeBytes = 584L * 1024 * 1024,
    )

    /**
     * Gemma 4 E2B — the best-quality option for phones with 4+ GB RAM.
     * MatFormer "effective 2B" architecture. Per LiteRT-LM benchmarks on
     * a Galaxy S26 Ultra: ~1.7 GB CPU RAM, ~676 MB GPU RAM at inference.
     */
    val GEMMA_4_E2B = ModelInfo(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        description = "Google Gemma 4, effective 2B active params. " +
            "2.58 GB download. Best quality — needs 4+ GB RAM.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2580L * 1024 * 1024,
    )

    val DEFAULT: List<ModelInfo> = listOf(QWEN_3_0_6B, GEMMA_3_1B_INT4, GEMMA_4_E2B)
}
