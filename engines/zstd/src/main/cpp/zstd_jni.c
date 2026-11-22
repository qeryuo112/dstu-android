#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "zstd.h"

#define LIMIT (16u * 1024u * 1024u)

static void fail(JNIEnv* env) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (type != NULL) (*env)->ThrowNew(env, type, "ZSTD_INVALID_OR_LIMIT");
}

static jbyteArray convert(JNIEnv* env, jbyteArray input, int encode) {
    if (input == NULL) { fail(env); return NULL; }
    jsize length = (*env)->GetArrayLength(env, input);
    if (length < 0 || (size_t)length > LIMIT) { fail(env); return NULL; }
    jbyte* bytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (bytes == NULL) return NULL;
    void* out = malloc(LIMIT);
    if (out == NULL) { (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT); fail(env); return NULL; }
    size_t result;
    if (encode) {
        result = ZSTD_compress(out, LIMIT, bytes, (size_t)length, 0);
    } else {
        ZSTD_DCtx* ctx = ZSTD_createDCtx();
        if (ctx == NULL) result = (size_t)-1;
        else {
            size_t set = ZSTD_DCtx_setParameter(ctx, ZSTD_d_windowLogMax, 24);
            result = ZSTD_isError(set) ? set : ZSTD_decompressDCtx(ctx, out, LIMIT, bytes, (size_t)length);
            ZSTD_freeDCtx(ctx);
        }
    }
    (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);
    if (ZSTD_isError(result) || result > LIMIT) { free(out); fail(env); return NULL; }
    jbyteArray output = (*env)->NewByteArray(env, (jsize)result);
    if (output != NULL && result > 0) (*env)->SetByteArrayRegion(env, output, 0, (jsize)result, out);
    free(out);
    return output;
}

JNIEXPORT jbyteArray JNICALL Java_com_deepstudent_engines_zstd_ZstdNative_encode(JNIEnv* env, jobject self, jbyteArray input) {
    (void)self;
    return convert(env, input, 1);
}

JNIEXPORT jbyteArray JNICALL Java_com_deepstudent_engines_zstd_ZstdNative_decode(JNIEnv* env, jobject self, jbyteArray input) {
    (void)self;
    return convert(env, input, 0);
}
