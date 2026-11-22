#include "public/fpdf_edit.h"
#include "public/fpdf_text.h"
#include "public/fpdfview.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string>
#include <unistd.h>

static void require(bool result, const char* message) {
    if (!result) { fprintf(stderr, "FAIL %s\n", message); exit(1); }
}

static FPDF_DOCUMENT open_pdf(const char* root, const char* name, const char* password = nullptr) {
    std::string path = std::string(root) + "/" + name;
    return FPDF_LoadDocument(path.c_str(), password);
}

static int fd_count() {
    DIR* dir = opendir("/proc/self/fd");
    require(dir, "open fd directory");
    int count = 0;
    while (readdir(dir)) ++count;
    closedir(dir);
    return count;
}

static long rss_kib() {
    FILE* file = fopen("/proc/self/statm", "r");
    require(file, "open statm");
    long size = 0, resident = 0;
    int fields = fscanf(file, "%ld %ld", &size, &resident);
    fclose(file);
    require(fields == 2, "read statm");
    return resident * sysconf(_SC_PAGESIZE) / 1024;
}

static void exact_text(FPDF_TEXTPAGE text, const unsigned int* expected, int count) {
    require(FPDFText_CountChars(text) == count, "desktop character count");
    for (int i = 0; i < count; ++i) {
        require(FPDFText_GetUnicode(text, i) == expected[i], "desktop character sequence");
    }
}

static void chinese(const char* root) {
    auto doc = open_pdf(root, "bug_420508260.pdf");
    require(doc && FPDF_GetPageCount(doc) == 1, "open chinese");
    auto page = FPDF_LoadPage(doc, 0);
    auto text = FPDFText_LoadPage(page);
    int count = FPDFText_CountChars(text);
    const unsigned int expected[] = {'W', 'h', 'a', 't', ' ', 'i', 's', ' ', 0x6211, 0x7684, ' ',
        'f', 'a', 'v', 'o', 'r', 'i', 't', 'e', ' ', 0x98df, 0x7269, '?'};
    exact_text(text, expected, sizeof(expected) / sizeof(expected[0]));
    bool found = false;
    for (int i = 0; i < count; ++i) {
        if (FPDFText_GetUnicode(text, i) != 0x6211) continue;
        double left, right, bottom, top;
        require(FPDFText_GetCharBox(text, i, &left, &right, &bottom, &top), "chinese box");
        int x, y;
        require(FPDF_PageToDevice(page, 0, 0, 400, 400, 0, left, top, &x, &y), "map box");
        require(x >= 0 && x <= 400 && y >= 0 && y <= 400, "mapped bounds");
        printf("PDF_NATIVE_CHINESE_BOX left=%.6f right=%.6f bottom=%.6f top=%.6f pixel=%d,%d\n",
               left, right, bottom, top, x, y);
        found = true;
    }
    require(found, "chinese text");
    FPDFText_ClosePage(text);
    FPDF_ClosePage(page);
    FPDF_CloseDocument(doc);
}

static void rotation_and_scan(const char* root) {
    auto doc = open_pdf(root, "bug_1229106.pdf");
    require(doc && FPDF_GetPageCount(doc) == 4, "rotated document");
    auto page = FPDF_LoadPage(doc, 0);
    require(FPDFPage_GetRotation(page) == 1, "page rotation 90");
    require(FPDF_GetPageWidthF(page) == 792 && FPDF_GetPageHeightF(page) == 612, "rotated size");
    auto rotated = FPDFBitmap_Create(792, 612, 0);
    FPDFBitmap_FillRect(rotated, 0, 0, 792, 612, 0xffffffff);
    FPDF_RenderPageBitmap(rotated, page, 0, 0, 792, 612, 0, 0);
    int x, y;
    require(FPDF_PageToDevice(page, 0, 0, 792, 612, 0, 370, 175, &x, &y), "rotated coordinate");
    auto* data = static_cast<unsigned char*>(FPDFBitmap_GetBuffer(rotated));
    auto* pixel = data + y * FPDFBitmap_GetStride(rotated) + x * 4;
    require(pixel[0] < 128 && pixel[1] < 128 && pixel[2] < 128, "rotated coordinate and render agree");
    require(FPDF_PageToDevice(page, 0, 0, 792, 612, 0, 306, 396, &x, &y), "rotated background coordinate");
    pixel = data + y * FPDFBitmap_GetStride(rotated) + x * 4;
    require(pixel[0] == 255 && pixel[1] == 255 && pixel[2] == 255, "rotated background");
    FPDFBitmap_Destroy(rotated);
    FPDF_ClosePage(page);
    FPDF_CloseDocument(doc);
    doc = open_pdf(root, "rotated_image.pdf");
    page = FPDF_LoadPage(doc, 0);
    auto text = FPDFText_LoadPage(page);
    require(FPDFText_CountChars(text) == 0, "image-only text");
    auto bitmap = FPDFBitmap_Create(200, 200, 0);
    FPDFBitmap_FillRect(bitmap, 0, 0, 200, 200, 0xffffffff);
    FPDF_RenderPageBitmap(bitmap, page, 0, 0, 200, 200, 0, 0);
    auto* bytes = static_cast<unsigned char*>(FPDFBitmap_GetBuffer(bitmap));
    bool ink = false;
    for (int i = 0; i < FPDFBitmap_GetStride(bitmap) * 200; ++i) ink |= bytes[i] != 255;
    require(ink, "image rendering");
    FPDFBitmap_Destroy(bitmap);
    FPDFText_ClosePage(text);
    FPDF_ClosePage(page);
    FPDF_CloseDocument(doc);
}

static void rotated_text(const char* root) {
    auto doc = open_pdf(root, "rotated_text_90.pdf");
    require(doc, "rotated text document");
    auto page = FPDF_LoadPage(doc, 0);
    auto text = FPDFText_LoadPage(page);
    const unsigned int expected[] = {'H', 'e', 'l', 'l', 'o', ',', '\r', '\n', ' ', 'w', 'o',
        'r', 'l', 'd', '!', '\r', '\n', ' ', 'G', 'o', 'o', 'd', 'b', 'y', 'e', ',', ' ',
        'w', 'o', 'r', 'l', 'd', '!'};
    exact_text(text, expected, sizeof(expected) / sizeof(expected[0]));
    FPDFText_ClosePage(text);
    FPDF_ClosePage(page);
    FPDF_CloseDocument(doc);
}

static void passwords(const char* root) {
    require(!open_pdf(root, "encrypted.pdf"), "missing password rejected");
    require(FPDF_GetLastError() == FPDF_ERR_PASSWORD, "missing password error");
    require(!open_pdf(root, "encrypted.pdf", "wrong"), "wrong password rejected");
    require(FPDF_GetLastError() == FPDF_ERR_PASSWORD, "wrong password error");
    auto doc = open_pdf(root, "encrypted.pdf", "1234");
    require(doc && FPDF_GetPageCount(doc) == 1, "valid password");
    FPDF_CloseDocument(doc);
    require(!open_pdf(root, "corrupt-body.pdf"), "corrupt file rejected");
    require(FPDF_GetLastError() == FPDF_ERR_FORMAT, "corrupt error");
    int before = fd_count();
    for (int i = 0; i < 30; ++i) {
        require(!open_pdf(root, "encrypted.pdf", "wrong"), "repeat wrong password");
        require(!open_pdf(root, "corrupt-body.pdf"), "repeat corrupt file");
    }
    int delta = fd_count() - before;
    printf("PDF_NATIVE_FAILURES iterations=60 fd_delta=%d\n", delta);
    require(delta == 0, "failed open descriptors");
}

static void budget(const char* root) {
    long before = rss_kib();
    int fds = fd_count();
    for (int i = 0; i < 50; ++i) {
        auto doc = open_pdf(root, "hello_world.pdf");
        require(doc, "repeat open");
        auto page = FPDF_LoadPage(doc, 0);
        auto text = FPDFText_LoadPage(page);
        auto bitmap = FPDFBitmap_Create(256, 256, 0);
        FPDF_RenderPageBitmap(bitmap, page, 0, 0, 256, 256, 0, 0);
        FPDFBitmap_Destroy(bitmap);
        FPDFText_ClosePage(text);
        FPDF_ClosePage(page);
        FPDF_CloseDocument(doc);
    }
    long delta = rss_kib() - before;
    int fd_delta = fd_count() - fds;
    printf("PDF_NATIVE_BUDGET iterations=50 rss_before_kib=%ld rss_delta_kib=%ld fd_delta=%d\n",
           before, delta, fd_delta);
    require(delta < 48 * 1024 && fd_delta == 0, "native budget");
}

int main(int argc, char** argv) {
    require(argc == 2, "fixture directory argument");
    FPDF_InitLibrary();
    chinese(argv[1]);
    rotation_and_scan(argv[1]);
    rotated_text(argv[1]);
    passwords(argv[1]);
    budget(argv[1]);
    FPDF_DestroyLibrary();
    puts("PDF_NATIVE_PROBE PASS");
}
