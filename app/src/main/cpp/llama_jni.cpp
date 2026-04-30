#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include "llama.h"

#define LOG_TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaHandle {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;
    int n_ctx = 0;
    std::string chat_template;
    std::mutex              gen_mutex;       // serialises concurrent generate calls
    std::atomic<bool>       abort_requested{false};
};

// ── Helpers ───────────────────────────────────────────────────────────────────

static std::string jstring_to_string(JNIEnv * env, jstring js) {
    if (!js) return "";
    const char * chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

static jstring string_to_jstring(JNIEnv * env, const std::string & s) {
    return env->NewStringUTF(s.c_str());
}

// Returns the byte length of all complete UTF-8 characters at the start of [data, len).
// Partial sequences at the end are excluded.
static size_t complete_utf8_len(const char * data, size_t len) {
    size_t i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)data[i];
        size_t char_len;
        if      (c < 0x80)         char_len = 1;
        else if ((c & 0xE0) == 0xC0) char_len = 2;
        else if ((c & 0xF0) == 0xE0) char_len = 3;
        else if ((c & 0xF8) == 0xF0) char_len = 4;
        else { i++; continue; }  // invalid lead byte — skip
        if (i + char_len > len) break;  // incomplete at tail
        i += char_len;
    }
    return i;
}

static LlamaHandle * to_handle(jlong ptr) {
    return reinterpret_cast<LlamaHandle *>(ptr);
}

// Remove all occurrences of <|channel>thinking<|channel>...<|channel> from text.
// The model may emit these thinking blocks in responses; strip them from history
// so they don't pollute subsequent turns.
static std::string strip_thinking_blocks(const std::string & text) {
    const std::string open_tag  = "<|channel>thinking<|channel>";
    const std::string close_tag = "<|channel>";
    std::string result;
    size_t pos = 0;
    while (pos < text.size()) {
        size_t start = text.find(open_tag, pos);
        if (start == std::string::npos) {
            result += text.substr(pos);
            break;
        }
        result += text.substr(pos, start - pos);
        size_t end = text.find(close_tag, start + open_tag.size());
        if (end == std::string::npos) {
            // No closing tag; skip the rest entirely
            break;
        }
        pos = end + close_tag.size();
    }
    return result;
}

// Build the full Gemma 4 prompt from structured message data.
// Handles: system, user, model (assistant), tool role messages,
// tool_calls in model responses, enable_thinking, strip_thinking, tools.
static std::string build_gemma4_prompt(
        const std::vector<std::string> & roles,
        const std::vector<std::string> & contents,
        const std::vector<std::string> & tool_calls_json,  // per-message; "" if none
        const std::vector<std::string> & tool_call_ids,    // per-message; "" if none
        const std::string & tools_json,
        bool enable_thinking,
        bool add_ass)
{
    std::string out;
    int n = (int)roles.size();

    // Extract system message (must be first if present)
    int start_idx = 0;
    std::string system_message;
    if (n > 0 && roles[0] == "system") {
        system_message = contents[0];
        start_idx = 1;
    }

    // Emit the system/preamble turn (user role in Gemma 4 format)
    out += "<|turn>user\n";
    if (!system_message.empty()) {
        out += system_message;
    }
    // Append thinking control channel
    out += "<|channel>thinking<|channel>";
    out += enable_thinking ? "true" : "false";
    out += "\n";
    // Append tool declarations if any
    if (!tools_json.empty()) {
        out += "<tool>\n";
        out += tools_json;
        out += "\n</tool>\n";
    }
    out += "<turn|>\n";

    // Emit conversation turns
    for (int i = start_idx; i < n; i++) {
        const std::string & role    = roles[i];
        const std::string & content = contents[i];
        const std::string & tc_json = tool_calls_json[i];
        const std::string & tc_id   = tool_call_ids[i];

        if (role == "user") {
            out += "<|turn>user\n";
            out += content;
            out += "<turn|>\n";
        } else if (role == "assistant" || role == "model") {
            out += "<|turn>model\n";
            if (!tc_json.empty()) {
                // Assistant issued tool calls — emit call block
                out += "<|call>\n";
                out += tc_json;
                out += "\n<call|>\n";
            } else {
                // Regular text response; strip thinking tokens from history
                out += strip_thinking_blocks(content);
            }
            out += "<turn|>\n";
        } else if (role == "tool") {
            // Tool result message
            out += "<|turn>tool\n";
            out += "<|response>\n";
            // Wrap as {"name": "<name>", "result": <content>}
            // tool_call_id identifies which call this is a result for
            out += "{\"id\":\"" + tc_id + "\",\"result\":" + content + "}";
            out += "\n<response|>\n";
            out += "<turn|>\n";
        }
    }

    if (add_ass) {
        out += "<|turn>model\n";
        if (enable_thinking) {
            // Signal model to start thinking
            out += "<|channel>thinking<|channel>";
        }
    }

    return out;
}

// ChatML fallback for models whose template llama_chat_apply_template doesn't support.
static std::string build_chatml_fallback(
        const std::vector<llama_chat_message> & chat,
        bool add_ass)
{
    std::string out;
    for (const auto & msg : chat) {
        out += "<|im_start|>" + std::string(msg.role) + "\n"
             + std::string(msg.content) + "<|im_end|>\n";
    }
    if (add_ass) out += "<|im_start|>assistant\n";
    LOGI("Applied ChatML fallback template");
    return out;
}

// ── JNI functions ─────────────────────────────────────────────────────────────

extern "C" {

// long nativeInit(String modelPath, int nThreads, boolean useVulkan, int nCtx)
JNIEXPORT jlong JNICALL
Java_com_litert_tunnel_engine_LlamaJni_nativeInit(
        JNIEnv * env, jclass,
        jstring j_model_path, jint n_threads, jboolean use_vulkan, jint n_ctx)
{
    llama_backend_init();

    std::string model_path = jstring_to_string(env, j_model_path);
    LOGI("Loading model: %s  threads=%d  vulkan=%d  n_ctx=%d",
         model_path.c_str(), (int)n_threads, (int)use_vulkan, (int)n_ctx);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = use_vulkan ? -1 : 0;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) {
        LOGE("Failed to load model: %s", model_path.c_str());
        if (use_vulkan) {
            LOGI("Retrying with CPU...");
            mparams.n_gpu_layers = 0;
            model = llama_model_load_from_file(model_path.c_str(), mparams);
        }
        if (!model) return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx            = (uint32_t)n_ctx;
    cparams.n_threads        = (uint32_t)n_threads;
    cparams.n_threads_batch  = (uint32_t)n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0L;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const char * tmpl_raw = llama_model_chat_template(model, nullptr);
    std::string chat_template = tmpl_raw ? tmpl_raw : "";

    auto * handle = new LlamaHandle{model, ctx, sampler, (int)n_ctx, chat_template};
    LOGI("Model loaded. GPU offload=%d  template_len=%zu", mparams.n_gpu_layers, chat_template.size());
    return reinterpret_cast<jlong>(handle);
}

// String nativeGetChatTemplate(long handle)  — for logging / inspection
JNIEXPORT jstring JNICALL
Java_com_litert_tunnel_engine_LlamaJni_nativeGetChatTemplate(
        JNIEnv * env, jclass, jlong ptr)
{
    auto * h = to_handle(ptr);
    if (!h || !h->model) return string_to_jstring(env, "");
    return string_to_jstring(env, h->chat_template);
}

// void nativeGenerate(long handle, String[] roles, String[] contents,
//                     String[] toolCallsJsonPerMsg, String[] toolCallIds,
//                     String toolsJson, boolean enableThinking,
//                     TokenCallback callback)
JNIEXPORT void JNICALL
Java_com_litert_tunnel_engine_LlamaJni_nativeGenerate(
        JNIEnv * env, jclass, jlong ptr,
        jobjectArray j_roles, jobjectArray j_contents,
        jobjectArray j_tool_calls_json, jobjectArray j_tool_call_ids,
        jstring j_tools_json, jboolean j_enable_thinking,
        jobject callback)
{
    auto * h = to_handle(ptr);
    if (!h || !h->ctx || !h->model) return;

    std::lock_guard<std::mutex> lock(h->gen_mutex);
    h->abort_requested.store(false);
    llama_memory_clear(llama_get_memory(h->ctx), false);

    // Unpack JNI arrays
    jint n_msg = env->GetArrayLength(j_roles);
    std::vector<std::string> role_strs(n_msg), content_strs(n_msg);
    std::vector<std::string> tc_json_strs(n_msg), tc_id_strs(n_msg);
    for (int i = 0; i < n_msg; i++) {
        auto jr  = (jstring)env->GetObjectArrayElement(j_roles,           i);
        auto jc  = (jstring)env->GetObjectArrayElement(j_contents,        i);
        auto jtc = (jstring)env->GetObjectArrayElement(j_tool_calls_json, i);
        auto jid = (jstring)env->GetObjectArrayElement(j_tool_call_ids,   i);
        role_strs[i]    = jstring_to_string(env, jr);
        content_strs[i] = jstring_to_string(env, jc);
        tc_json_strs[i] = jstring_to_string(env, jtc);
        tc_id_strs[i]   = jstring_to_string(env, jid);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        env->DeleteLocalRef(jtc);
        env->DeleteLocalRef(jid);
    }
    std::string tools_json     = jstring_to_string(env, j_tools_json);
    bool        enable_thinking = (bool)j_enable_thinking;

    // Build prompt.
    // For Gemma 4 (detected via <|turn> in template): use our full implementation.
    // For other models: try llama_chat_apply_template; fall back to ChatML.
    std::string prompt;
    const std::string & tmpl = h->chat_template;

    if (tmpl.find("<|turn>") != std::string::npos) {
        prompt = build_gemma4_prompt(
                role_strs, content_strs, tc_json_strs, tc_id_strs,
                tools_json, enable_thinking, true);
        LOGI("Applied Gemma4 template  thinking=%d  tools=%s",
             (int)enable_thinking, tools_json.empty() ? "none" : "yes");
    } else {
        // Build llama_chat_message array (role/content only — legacy path)
        std::vector<llama_chat_message> chat(n_msg);
        for (int i = 0; i < n_msg; i++) {
            chat[i] = {role_strs[i].c_str(), content_strs[i].c_str()};
        }
        const char * tmpl_ptr = tmpl.empty() ? nullptr : tmpl.c_str();
        int needed = llama_chat_apply_template(tmpl_ptr, chat.data(), (size_t)n_msg, true, nullptr, 0);
        if (needed >= 0) {
            prompt.resize(needed);
            llama_chat_apply_template(tmpl_ptr, chat.data(), (size_t)n_msg, true, prompt.data(), needed);
        } else {
            LOGI("llama_chat_apply_template returned %d — using ChatML fallback", needed);
            prompt = build_chatml_fallback(chat, true);
            if (prompt.empty()) {
                LOGE("ChatML fallback produced empty prompt");
                return;
            }
        }
    }
    LOGI("Prompt built: %d chars", (int)prompt.size());

    // Tokenize
    const struct llama_vocab * vocab = llama_model_get_vocab(h->model);
    int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                          nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                       tokens.data(), (int32_t)tokens.size(), true, true) < 0) {
        LOGE("Tokenization failed");
        return;
    }

    // Trim if prompt exceeds context
    int max_tokens = h->n_ctx - 64;
    if ((int)tokens.size() > max_tokens) {
        tokens.erase(tokens.begin(), tokens.begin() + ((int)tokens.size() - max_tokens));
        LOGI("Prompt trimmed to %d tokens", max_tokens);
    }

    // Decode prompt tokens
    llama_batch batch = llama_batch_init((int32_t)tokens.size(), 0, 1);
    for (int i = 0; i < (int)tokens.size(); i++) {
        batch.token   [batch.n_tokens] = tokens[i];
        batch.pos     [batch.n_tokens] = i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id  [batch.n_tokens][0] = 0;
        batch.logits  [batch.n_tokens] = (i == (int)tokens.size() - 1) ? 1 : 0;
        batch.n_tokens++;
    }
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        llama_batch_free(batch);
        return;
    }
    llama_batch_free(batch);

    // Resolve callback
    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    if (!on_token) {
        LOGE("onToken method not found");
        return;
    }

    // Generate tokens
    int n_pos = (int)tokens.size();
    char piece_buf[256];
    std::string utf8_buf;  // accumulates bytes until a complete UTF-8 sequence is ready
    while (n_pos < h->n_ctx && !h->abort_requested.load()) {
        llama_token token_id = llama_sampler_sample(h->sampler, h->ctx, -1);
        llama_sampler_accept(h->sampler, token_id);

        if (llama_vocab_is_eog(vocab, token_id)) break;

        int n = llama_token_to_piece(vocab, token_id, piece_buf, sizeof(piece_buf), 0, false);
        if (n > 0) {
            utf8_buf.append(piece_buf, n);
            size_t complete = complete_utf8_len(utf8_buf.data(), utf8_buf.size());
            if (complete > 0) {
                std::string to_send(utf8_buf.data(), complete);
                utf8_buf.erase(0, complete);
                jstring j_piece = env->NewStringUTF(to_send.c_str());
                env->CallVoidMethod(callback, on_token, j_piece);
                env->DeleteLocalRef(j_piece);
                if (env->ExceptionCheck()) break;
            }
        }

        llama_batch gen_batch = llama_batch_init(1, 0, 1);
        gen_batch.token   [0] = token_id;
        gen_batch.pos     [0] = n_pos++;
        gen_batch.n_seq_id[0] = 1;
        gen_batch.seq_id  [0][0] = 0;
        gen_batch.logits  [0] = 1;
        gen_batch.n_tokens = 1;
        if (llama_decode(h->ctx, gen_batch) != 0) {
            llama_batch_free(gen_batch);
            break;
        }
        llama_batch_free(gen_batch);
    }
    // Flush any remaining bytes (should be empty for well-formed output)
    if (!utf8_buf.empty() && !env->ExceptionCheck()) {
        jstring j_piece = env->NewStringUTF(utf8_buf.c_str());
        env->CallVoidMethod(callback, on_token, j_piece);
        env->DeleteLocalRef(j_piece);
    }
    llama_sampler_reset(h->sampler);
}

// void nativeClearContext(long handle)
JNIEXPORT void JNICALL
Java_com_litert_tunnel_engine_LlamaJni_nativeClearContext(
        JNIEnv * env, jclass, jlong ptr)
{
    auto * h = to_handle(ptr);
    if (!h || !h->ctx) return;
    llama_memory_clear(llama_get_memory(h->ctx), false);
    LOGI("KV cache cleared");
}

// void nativeAbortGenerate(long handle)
// Sets abort flag and blocks until any running nativeGenerate call exits.
JNIEXPORT void JNICALL
Java_com_litert_tunnel_engine_LlamaJni_nativeAbortGenerate(
        JNIEnv * env, jclass, jlong ptr)
{
    auto * h = to_handle(ptr);
    if (!h) return;
    h->abort_requested.store(true);
    std::lock_guard<std::mutex> lock(h->gen_mutex);  // waits for generate to exit
    LOGI("Generation aborted");
}

// void nativeFree(long handle)
JNIEXPORT void JNICALL
Java_com_litert_tunnel_engine_LlamaJni_nativeFree(
        JNIEnv * env, jclass, jlong ptr)
{
    auto * h = to_handle(ptr);
    if (!h) return;
    if (h->sampler) llama_sampler_free(h->sampler);
    if (h->ctx)     llama_free(h->ctx);
    if (h->model)   llama_model_free(h->model);
    llama_backend_free();
    delete h;
    LOGI("LlamaHandle freed");
}

} // extern "C"
