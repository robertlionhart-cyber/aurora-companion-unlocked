#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sys/stat.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr long long LOW_MEMORY_MODEL_BYTES = 1500000000LL;   // 3B-class GGUF files
static bool g_low_memory = false;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.7f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static common_batch                       g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    // Aurora 0.8.5: 3B-class files (~2 GB) ran a 6 GB phone out of memory: the CPU repack buffer copied 1.3 GB of
    // weights into anonymous memory (swapped to zram, then the app froze and was killed). For big files keep the
    // weights memory-mapped (no repack copy) and use a smaller KV cache / compute buffer.
    struct stat st {};
    g_low_memory = stat(model_path, &st) == 0 && (long long) st.st_size >= LOW_MEMORY_MODEL_BYTES;
    if (g_low_memory) {
        model_params.use_extra_bufts = false;
        LOGi("%s: big model (%lld bytes): low-memory mode (no weight repack, q8_0 KV cache)", __func__, (long long) st.st_size);
    }

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    if (g_low_memory) {
        ctx_params.n_ubatch = BATCH_SIZE / 2;          // smaller compute buffer
        ctx_params.type_k = GGML_TYPE_Q8_0;            // half-size KV cache (needs flash attention, on by default)
        ctx_params.type_v = GGML_TYPE_Q8_0;
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_init_from_model() returned null)", __func__);
    }
    return context;
}

/**
 * 0.8.8: chat sampling tuned for small models: temp 0.7, top-k 40, top-p 0.9, min-p 0.05, repeat penalty 1.1 over
 * the last 64 tokens. DRY is off: on Qwen2.5 1.5B it pushed replies into runaway loops. AURORA_* env vars are
 * only for the box test harness (never set on Android).
 */
static float env_f(const char *k, float d) { const char *v = getenv(k); return v ? (float) atof(v) : d; }
static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = env_f("AURORA_TEMP", temp);
    sparams.top_k = 40;
    sparams.top_p = env_f("AURORA_TOPP", 0.9f);
    sparams.min_p = env_f("AURORA_MINP", 0.05f);
    sparams.penalty_last_n = (int) env_f("AURORA_PEN_N", 64);
    sparams.penalty_repeat = env_f("AURORA_PEN", 1.1f);
    sparams.dry_multiplier = env_f("AURORA_DRY", 0.0f);
    sparams.dry_base = 1.75f;
    sparams.dry_allowed_length = 3;
    sparams.dry_penalty_last_n = 1024;
    if (getenv("AURORA_SEED")) sparams.seed = (uint32_t) atoi(getenv("AURORA_SEED"));
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = common_batch(context);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch batch(context);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            batch.add(0, i, 0, false);
        }

        batch.set_output(batch.size() - 1, true);
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            batch.clear();
            for (j = 0; j < pl; j++) {
                batch.add(0, i, j, true);
            }

            if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states (Aurora 0.8.3: turn-aware context management).
 * - chat_msgs: the messages currently held in the KV cache (system first, if the model has a template)
 * - turn_starts / turn_msg_index: KV position and chat_msgs index where each user turn starts, so the
 *   oldest whole turns can be dropped from the KV cache without re-computing the rest
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;
static std::vector<llama_pos> turn_starts;
static std::vector<size_t> turn_msg_index;

static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;
static bool assistant_open = false;
static int  g_gen_error = 0;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    turn_starts.clear();
    turn_msg_index.clear();
    system_prompt_position = 0;
    current_position = 0;
    assistant_open = false;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/** 0.8.8: forget the sampler's penalty history when the context is replaced (new chat, rebuild, restore). */
static void reset_sampler_history() { if (g_sampler) common_sampler_reset(g_sampler); }

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    g_gen_error = 0;
}

static bool has_template() {
    return common_chat_templates_was_explicit(g_chat_templates.get());
}

static std::string format_msg(const std::string &role, const std::string &content, bool add_ass) {
    common_chat_msg m;
    m.role = role;
    m.content = content;
    return common_chat_format_single(g_chat_templates.get(), chat_msgs, m, add_ass, /* use_jinja */ false);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content, bool add_ass) {
    auto formatted = format_msg(role, content, add_ass);
    common_chat_msg m;
    m.role = role;
    m.content = content;
    chat_msgs.push_back(m);
    return formatted;
}

/** Number of chat_msgs that make up the system block (never trimmed): 1, or 2 for templates without a system role. */
static size_t g_sys_msgs = 1;
constexpr const char *FOLDED_SYSTEM_ACK = "Okay, I'll stay in character.";

/**
 * 0.8.9: adds the system prompt and returns its formatted text. Templates with no system role (Gemma) drop a lone
 * system message, so the instructions go in as the first user turn plus a short model acknowledgement
 * (<start_of_turn>user ... <end_of_turn><start_of_turn>model ... <end_of_turn>). That block sits before
 * system_prompt_position, so trimming old turns never removes the persona.
 */
static std::string add_system_block(const std::string &system_prompt) {
    g_sys_msgs = 1;
    const bool folds = format_msg(ROLE_SYSTEM, "x", false).empty();
    if (!folds) return chat_add_and_format(ROLE_SYSTEM, system_prompt, false);
    g_sys_msgs = 2;
    std::string out = chat_add_and_format(ROLE_USER, system_prompt, false);
    out += chat_add_and_format(ROLE_ASSISTANT, FOLDED_SYSTEM_ACK, false);
    LOGi("%s: template has no system role; system prompt folded into the first user turn", __func__);
    return out;
}

/** Decodes tokens at current_position. Never shifts: callers make sure they fit (returns 3 if not). */
static int decode_at_current(const llama_tokens &tokens, const bool compute_last_logit) {
    if (current_position + (int) tokens.size() > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGe("%s: %d tokens at %d won't fit into the context", __func__, (int) tokens.size(), current_position);
        return 3;
    }
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int n = std::min((int) tokens.size() - i, BATCH_SIZE);
        g_batch.clear();
        for (int j = 0; j < n; j++) {
            const bool want_logit = compute_last_logit && (i + j == (int) tokens.size() - 1);
            g_batch.add(tokens[i + j], current_position + i + j, 0, want_logit);
        }
        const int rc = llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
        if (rc) {
            LOGe("%s: llama_decode failed w/ %d", __func__, rc);
            return 2;
        }
    }
    current_position += (int) tokens.size();
    return 0;
}

/**
 * Drops the oldest whole turns (after the system prompt) until at least [needed] tokens are free.
 * If keep_last, the newest turn (the one being generated) is never dropped. Returns tokens freed.
 */
static int trim_oldest_turns(int needed, bool keep_last) {
    const int n_turns = (int) turn_starts.size();
    const int max_drop = keep_last ? n_turns - 1 : n_turns;
    if (max_drop <= 0 || needed <= 0) return 0;
    // Anything between the system prompt and the first turn (e.g. a greeting) goes with the first turn.
    int k = 0;
    llama_pos cut = system_prompt_position;
    while (k < max_drop) {
        k++;
        cut = (k < n_turns) ? turn_starts[k] : current_position;
        if (cut - system_prompt_position >= needed) break;
    }
    const int delta = cut - system_prompt_position;
    if (delta <= 0) return 0;
    auto *mem = llama_get_memory(g_context);
    llama_memory_seq_rm(mem, 0, system_prompt_position, cut);
    if (cut < current_position) llama_memory_seq_add(mem, 0, cut, current_position, -delta);
    current_position -= delta;

    size_t removed_msgs = 0;
    if (has_template()) {
        const size_t first_msg = g_sys_msgs;   // the system block (1 message, or 2 when folded for Gemma)
        const size_t msg_cut = std::min((k < n_turns) ? turn_msg_index[k] : chat_msgs.size(), chat_msgs.size());
        if (msg_cut > first_msg) {
            chat_msgs.erase(chat_msgs.begin() + first_msg, chat_msgs.begin() + msg_cut);
            removed_msgs = msg_cut - first_msg;
        }
    }
    turn_starts.erase(turn_starts.begin(), turn_starts.begin() + k);
    turn_msg_index.erase(turn_msg_index.begin(), turn_msg_index.begin() + k);
    for (auto &p : turn_starts) p -= delta;
    for (auto &i : turn_msg_index) i -= removed_msgs;
    LOGi("%s: dropped %d turns (%d tokens); position now %d", __func__, k, delta, current_position);
    return delta;
}

/**
 * Closes an assistant turn that ended without an end-of-turn token (stopped, interrupted, hit the length
 * limit): records the message and decodes the template's closing tokens (e.g. <|eot_id|>) so the KV cache
 * stays consistent with chat_msgs.
 */
static void close_assistant_turn(bool decode_tail) {
    if (!assistant_open) return;
    assistant_open = false;
    std::string content = assistant_ss.str();
    if (!has_template()) return;
    std::string tail;
    if (decode_tail) {
        static const std::string PROBE = "\x01" "AURORA_PROBE" "\x01";
        const auto probe = format_msg(ROLE_ASSISTANT, PROBE, false);
        const auto at = probe.rfind(PROBE);
        if (at != std::string::npos) tail = probe.substr(at + PROBE.size());
    }
    chat_add_and_format(ROLE_ASSISTANT, content, false);
    if (!tail.empty()) {
        const auto toks = common_tokenize(g_context, tail, false, true);
        if (decode_at_current(toks, false) != 0) {
            LOGw("%s: couldn't decode the closing tokens", __func__);
        }
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    reset_sampler_history();
    reset_long_term_states();
    reset_short_term_states();

    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    std::string formatted_system_prompt(system_prompt);
    const bool tmpl = has_template();
    if (tmpl) formatted_system_prompt = add_system_block(system_prompt);
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt, true, tmpl);
    if ((int) system_tokens.size() > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGe("%s: System prompt too long for context! %d tokens", __func__, (int) system_tokens.size());
        return 1;
    }
    const int rc = decode_at_current(system_tokens, false);
    if (rc) {
        LOGe("%s: decode failed (%d)", __func__, rc);
        reset_long_term_states();
        return 2;
    }
    system_prompt_position = current_position;
    return 0;
}

/**
 * Rebuilds the whole context: system prompt + earlier messages (Continue a saved chat, recover from an error,
 * or restore after a memory-notes pass). roles: "user" / "assistant".
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_loadHistory(
        JNIEnv *env, jobject /*unused*/, jstring jsystem, jobjectArray jroles, jobjectArray jtexts) {
    reset_sampler_history();
    reset_long_term_states();
    reset_short_term_states();
    const bool tmpl = has_template();

    const auto *sys = env->GetStringUTFChars(jsystem, nullptr);
    std::string s(sys);
    env->ReleaseStringUTFChars(jsystem, sys);
    std::string fs = tmpl ? add_system_block(s) : s;
    auto toks = common_tokenize(g_context, fs, true, tmpl);
    int rc = decode_at_current(toks, false);
    if (rc) { LOGe("%s: system decode failed (%d)", __func__, rc); reset_long_term_states(); return rc == 3 ? 1 : 2; }
    system_prompt_position = current_position;

    const int n = env->GetArrayLength(jroles);
    for (int i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jt = (jstring) env->GetObjectArrayElement(jtexts, i);
        const char *r = env->GetStringUTFChars(jr, nullptr);
        const char *t = env->GetStringUTFChars(jt, nullptr);
        std::string role(r), text(t);
        env->ReleaseStringUTFChars(jr, r);
        env->ReleaseStringUTFChars(jt, t);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jt);
        if (role == ROLE_USER) {
            turn_starts.push_back(current_position);
            turn_msg_index.push_back(chat_msgs.size());
        }
        std::string piece = tmpl ? chat_add_and_format(role, text, false)
                                 : (role == ROLE_USER ? "User: " : "Assistant: ") + text + "\n";
        auto pt = common_tokenize(g_context, piece, false, tmpl);
        rc = decode_at_current(pt, false);
        if (rc) { LOGe("%s: history decode failed at %d (%d)", __func__, i, rc); reset_long_term_states(); return rc == 3 ? 3 : 2; }
    }
    LOGi("%s: context rebuilt with %d messages, position %d", __func__, n, current_position);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_tokenCount(JNIEnv *env, jobject /*unused*/, jstring jtext) {
    const auto *t = env->GetStringUTFChars(jtext, nullptr);
    std::string text(t);
    env->ReleaseStringUTFChars(jtext, t);
    if (!g_context) return (int) (text.size() / 3);
    return (int) common_tokenize(g_context, text, false, true).size();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_contextPos(JNIEnv * /*env*/, jobject /*unused*/) {
    return current_position;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_trimOldest(JNIEnv * /*env*/, jobject /*unused*/, jint needed) {
    return trim_oldest_turns(needed, false);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_finishAssistant(JNIEnv * /*env*/, jobject /*unused*/) {
    close_assistant_turn(true);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_genError(JNIEnv * /*env*/, jobject /*unused*/) {
    return g_gen_error;
}

/** Snapshot of the chat (KV cache of sequence 0 + bookkeeping), used around a memory-notes pass. */
static struct {
    bool valid = false;
    std::vector<uint8_t> kv;
    std::vector<common_chat_msg> msgs;
    llama_pos sys = 0, cur = 0;
    std::vector<llama_pos> ts;
    std::vector<size_t> tmi;
} g_saved;

constexpr size_t MAX_SNAPSHOT_BYTES = 256u * 1024u * 1024u;

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_saveState(JNIEnv * /*env*/, jobject /*unused*/) {
    g_saved.valid = false;
    g_saved.kv.clear();
    g_saved.kv.shrink_to_fit();
    close_assistant_turn(true);
    const size_t size = llama_state_seq_get_size(g_context, 0);
    if (size == 0 || size > MAX_SNAPSHOT_BYTES) { LOGw("%s: snapshot size %zu, skipped", __func__, size); return 1; }
    try { g_saved.kv.resize(size); } catch (...) { return 1; }
    const size_t got = llama_state_seq_get_data(g_context, g_saved.kv.data(), size, 0);
    if (got == 0) { g_saved.kv.clear(); return 1; }
    g_saved.kv.resize(got);
    g_saved.msgs = chat_msgs;
    g_saved.sys = system_prompt_position;
    g_saved.cur = current_position;
    g_saved.ts = turn_starts;
    g_saved.tmi = turn_msg_index;
    g_saved.valid = true;
    LOGi("%s: saved %zu bytes at position %d", __func__, got, current_position);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_restoreState(JNIEnv * /*env*/, jobject /*unused*/) {
    reset_sampler_history();
    if (!g_saved.valid) return 1;
    reset_short_term_states();
    auto *mem = llama_get_memory(g_context);
    llama_memory_clear(mem, false);
    const size_t rc = llama_state_seq_set_data(g_context, g_saved.kv.data(), g_saved.kv.size(), 0);
    g_saved.valid = false;
    g_saved.kv.clear();
    g_saved.kv.shrink_to_fit();
    if (rc == 0) {
        LOGe("%s: restore failed", __func__);
        reset_long_term_states();
        return 2;
    }
    chat_msgs = g_saved.msgs;
    system_prompt_position = g_saved.sys;
    current_position = g_saved.cur;
    turn_starts = g_saved.ts;
    turn_msg_index = g_saved.tmi;
    assistant_open = false;
    LOGi("%s: restored chat at position %d", __func__, current_position);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    close_assistant_turn(true);   // safety: a previous reply that never got closed
    reset_short_term_states();

    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string formatted_user_prompt(user_prompt);
    const bool tmpl = has_template();
    const std::string raw_user_prompt(user_prompt);
    if (tmpl) formatted_user_prompt = format_msg(ROLE_USER, raw_user_prompt, true);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // No BOS in the middle of a conversation (add_special = false).
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, false, tmpl);
    const int n = (int) user_tokens.size();

    // Make room by dropping whole old turns, never by shifting mid-prompt.
    const int limit = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (current_position + n + 1 > limit) {
        trim_oldest_turns(current_position + n + 1 - limit, false);
    }
    if (current_position + n + 1 > limit) {
        LOGe("%s: prompt of %d tokens doesn't fit (position %d)", __func__, n, current_position);
        return 3;
    }
    const size_t msg_index = chat_msgs.size();
    if (tmpl) {
        common_chat_msg m;
        m.role = ROLE_USER;
        m.content = raw_user_prompt;
        chat_msgs.push_back(m);
    }

    const llama_pos start = current_position;
    const int rc = decode_at_current(user_tokens, true);
    if (rc) {
        LOGe("%s: llama_decode() failed (%d)", __func__, rc);
        if (tmpl && chat_msgs.size() > msg_index) chat_msgs.resize(msg_index);
        return 2;
    }
    turn_starts.push_back(start);
    turn_msg_index.push_back(msg_index);
    stop_generation_position = current_position + n_predict;
    assistant_open = true;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Context full: drop old whole turns (keeping this one); if that's impossible, end the reply.
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - 8) {
        LOGw("%s: Context full! Dropping old turns...", __func__);
        const int freed = trim_oldest_turns(512, true);
        if (freed <= 0 || current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - 8) {
            close_assistant_turn(true);
            return nullptr;
        }
        stop_generation_position -= freed;
    }

    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        close_assistant_turn(true);
        return nullptr;
    }

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // 0.8.8: a control token that isn't end-of-turn (e.g. ChatML <|im_start|>) means the model is starting
    // a new turn on its own (writing the user's lines). End the reply cleanly instead of printing it.
    {
        const auto *vocab = llama_model_get_vocab(g_model);
        if (!llama_vocab_is_eog(vocab, new_token_id) &&
            (llama_vocab_get_attr(vocab, new_token_id) & LLAMA_TOKEN_ATTR_CONTROL)) {
            LOGw("%s: control token %d mid-reply; ending the turn", __func__, new_token_id);
            close_assistant_turn(true);
            return nullptr;
        }
    }

    g_batch.clear();
    g_batch.add(new_token_id, current_position, 0, true);
    const int rc = llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
    if (rc != 0) {
        LOGe("%s: llama_decode() failed for generated token (%d)", __func__, rc);
        g_gen_error = rc;
        close_assistant_turn(false);
        return nullptr;
    }
    current_position++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        // The end-of-turn token is already in the KV cache; just record the message.
        if (assistant_open) {
            assistant_open = false;
            if (has_template()) chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str(), false);
        }
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        result = env->NewStringUTF("");
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    g_batch = common_batch();
    llama_free(g_context);
    llama_model_free(g_model);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
