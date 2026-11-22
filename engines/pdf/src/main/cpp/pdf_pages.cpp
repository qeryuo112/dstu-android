#include "pdf_internal.h"

#include <android/bitmap.h>
#include <cmath>
#include <limits>
#include <vector>

#include "public/fpdf_edit.h"
#include "public/fpdf_text.h"

static constexpr int max_characters = 100000;
static constexpr int64_t max_pixels = 16777216;

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_deepstudent_engines_pdf_PdfNative_pageInfo(JNIEnv* env, jobject, jlong id, jint index) {
    std::lock_guard<std::mutex> guard(pdf_mutex);
    auto page = pdf_page(env, id, index);
    if (!page) return nullptr;
    jfloat values[] = {FPDF_GetPageWidthF(page.get()), FPDF_GetPageHeightF(page.get()),
                       static_cast<jfloat>(FPDFPage_GetRotation(page.get()))};
    jfloatArray result = env->NewFloatArray(3);
    if (result) env->SetFloatArrayRegion(result, 0, 3, values);
    return result;
}

static void append_character(FPDF_TEXTPAGE page, int index, std::vector<jdouble>& out) {
    double left, right, bottom, top;
    out.push_back(FPDFText_GetUnicode(page, index));
    if (FPDFText_GetCharBox(page, index, &left, &right, &bottom, &top)) {
        out.insert(out.end(), {left, bottom, right, top});
    } else {
        out.insert(out.end(), 4, std::numeric_limits<double>::quiet_NaN());
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_deepstudent_engines_pdf_PdfNative_characters(JNIEnv* env, jobject, jlong id, jint index) {
    std::lock_guard<std::mutex> guard(pdf_mutex);
    auto page = pdf_page(env, id, index);
    if (!page) return nullptr;
    TextPage text(FPDFText_LoadPage(page.get()), FPDFText_ClosePage);
    if (!text) { pdf_fail(env, -6); return nullptr; }
    int count = FPDFText_CountChars(text.get());
    if (count < 0 || count > max_characters) { pdf_fail(env, -101); return nullptr; }
    std::vector<jdouble> data;
    data.reserve(static_cast<size_t>(count) * 5);
    for (int i = 0; i < count; ++i) append_character(text.get(), i, data);
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(data.size()));
    if (result && !data.empty()) env->SetDoubleArrayRegion(result, 0, data.size(), data.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_deepstudent_engines_pdf_PdfNative_render(JNIEnv* env, jobject, jlong id, jint index, jobject bitmap) {
    std::lock_guard<std::mutex> guard(pdf_mutex);
    auto page = pdf_page(env, id, index);
    if (!page) return;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height ||
        static_cast<int64_t>(info.width) * info.height > max_pixels) {
        pdf_fail(env, -101); return;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        pdf_fail(env, -1); return;
    }
    FPDF_BITMAP target = FPDFBitmap_CreateEx(info.width, info.height, FPDFBitmap_BGRA, pixels, info.stride);
    if (target) {
        FPDFBitmap_FillRect(target, 0, 0, info.width, info.height, 0xffffffff);
        FPDF_RenderPageBitmap(target, page.get(), 0, 0, info.width, info.height, 0,
                              FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER | FPDF_RENDER_LIMITEDIMAGECACHE);
        FPDFBitmap_Destroy(target);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!target) pdf_fail(env, -1);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_deepstudent_engines_pdf_PdfNative_pageToPixel(JNIEnv* env, jobject, jlong id, jint index,
                                                       jint width, jint height, jdouble x, jdouble y) {
    std::lock_guard<std::mutex> guard(pdf_mutex);
    auto page = pdf_page(env, id, index);
    if (!page) return nullptr;
    int dx = 0, dy = 0;
    if (!FPDF_PageToDevice(page.get(), 0, 0, width, height, 0, x, y, &dx, &dy)) {
        pdf_fail(env, -6); return nullptr;
    }
    jdouble values[] = {static_cast<double>(dx), static_cast<double>(dy)};
    jdoubleArray result = env->NewDoubleArray(2);
    if (result) env->SetDoubleArrayRegion(result, 0, 2, values);
    return result;
}
