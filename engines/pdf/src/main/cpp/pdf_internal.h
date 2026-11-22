#pragma once

#include <jni.h>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "public/fpdfview.h"

struct PdfFile {
    int fd = -1;
    FPDF_FILEACCESS access{};
    FPDF_DOCUMENT document = nullptr;
    ~PdfFile();
};

using Page = std::unique_ptr<fpdf_page_t__, decltype(&FPDF_ClosePage)>;
using TextPage = std::unique_ptr<fpdf_textpage_t__, void (*)(FPDF_TEXTPAGE)>;

extern std::mutex pdf_mutex;
extern std::unordered_map<jlong, std::unique_ptr<PdfFile>> pdf_documents;
void pdf_fail(JNIEnv* env, int code);
PdfFile* pdf_find(JNIEnv* env, jlong id);
Page pdf_page(JNIEnv* env, jlong id, jint index);
