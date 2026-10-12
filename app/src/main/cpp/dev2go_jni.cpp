// JNI shim for the on-device LLM runtime.
//
// Thin by design: all logic lives in dev2go_server.cpp, which is also what the
// workstation probe links against. That split is what makes the probe meaningful — it
// exercises the same server the phone runs, not a lookalike.

#include <jni.h>

#include "dev2go_server.h"

#include "llama.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_dev2go_runtime_NativeServer_start(JNIEnv * env, jobject, jstring j_model_path, jint port, jint n_ctx,
                                           jint n_threads, jint n_batch, jint n_ubatch) {
    const char * model_path = env->GetStringUTFChars(j_model_path, nullptr);
    if (model_path == nullptr) {
        return JNI_FALSE;
    }

    dev2go::ServerConfig config;
    config.model_path = model_path;
    config.port       = port;
    config.n_ctx      = n_ctx;
    config.n_threads  = n_threads;
    config.n_batch    = n_batch;
    config.n_ubatch   = n_ubatch;

    env->ReleaseStringUTFChars(j_model_path, model_path);

    return dev2go::Server::instance().start(config) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_dev2go_runtime_NativeServer_stop(JNIEnv *, jobject) {
    dev2go::Server::instance().stop();
}

JNIEXPORT jboolean JNICALL
Java_com_dev2go_runtime_NativeServer_isRunning(JNIEnv *, jobject) {
    return dev2go::Server::instance().running() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_dev2go_runtime_NativeServer_backendVersion(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"