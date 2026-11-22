#include "pdf_internal.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <cerrno>
#include <climits>
#include <mutex>
#include <vector>

std::mutex pdf_mutex;
std::unordered_map<jlong, std::unique_ptr<PdfFile>> pdf_documents;
static jlong next_id = 1;
static std::once_flag library_init;
static constexpr long long max_document_bytes = 1073741824LL;

PdfFile::~PdfFile() {
    if (document) FPDF_CloseDocument(document);
    if (fd >= 0) close(fd);
}

void pdf_fail(JNIEnv* env, int code) {
    jclass type = env->FindClass("com/deepstudent/engines/pdf/PdfNative");
    if (!type) return;
    jmethodID raise = env->GetStaticMethodID(type, "raise", "(I)V");
    if (raise) env->CallStaticVoidMethod(type, raise, code);
    env->DeleteLocalRef(type);
}

PdfFile* pdf_find(JNIEnv* env, jlong id) {
    auto it = pdf_documents.find(id);
    if (it != pdf_documents.end()) return it->second.get();
    pdf_fail(env, -6);
    return nullptr;
}

Page pdf_page(JNIEnv* env, jlong id, jint index) {
    PdfFile* file = pdf_find(env, id);
    FPDF_PAGE page = nullptr;
    if (file && index >= 0 && index < FPDF_GetPageCount(file->document)) {
        page = FPDF_LoadPage(file->document, index);
    }
    if (!page && !env->ExceptionCheck()) pdf_fail(env, -6);
    return Page(page, FPDF_ClosePage);
}

static int read_block(void* param, unsigned long offset, unsigned char* data, unsigned long size) {
    auto* file = static_cast<PdfFile*>(param);
    unsigned long done = 0;
    while (done < size) {
        ssize_t n = pread(file->fd, data + done, size - done, static_cast<off_t>(offset + done));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        done += static_cast<unsigned long>(n);
    }
    return 1;
}

static std::unique_ptr<PdfFile> duplicate_file(int fd, int& error) {
    auto file = std::make_unique<PdfFile>();
    file->fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    struct stat status{};
    if (file->fd < 0 || fstat(file->fd, &status) || !S_ISREG(status.st_mode) || status.st_size < 0) return nullptr;
    if (status.st_size > max_document_bytes) { error = -101; return nullptr; }
    file->access.m_FileLen = static_cast<unsigned long>(status.st_size);
    file->access.m_GetBlock = read_block;
    file->access.m_Param = file.get();
    return file;
}

static void erase_secret(std::vector<char>& secret) {
    volatile char* data = secret.data();
    for (size_t i = 0; i < secret.size(); ++i) data[i] = 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deepstudent_engines_pdf_PdfNative_open(JNIEnv* env, jobject, jint fd, jbyteArray password) {
    std::lock_guard<std::mutex> guard(pdf_mutex);
    if (pdf_documents.size() >= 8) return -101;
    int error = -100;
    auto file = duplicate_file(fd, error);
    if (!file) return error;
    std::vector<char> secret;
    if (password) {
        jsize size = env->GetArrayLength(password);
        if (size > 1024) return -101;
        secret.resize(static_cast<size_t>(size) + 1, 0);
        env->GetByteArrayRegion(password, 0, size, reinterpret_cast<jbyte*>(secret.data()));
        if (env->ExceptionCheck()) return -1;
    }
    std::call_once(library_init, [] { FPDF_InitLibrary(); });
    file->document = FPDF_LoadCustomDocument(&file->access, password ? secret.data() : nullptr);
    erase_secret(secret);
    if (!file->document) {
        unsigned long error = FPDF_GetLastError();
        return error ? -static_cast<jlong>(error) : -1;
    }
    if (next_id == LLONG_MAX) return -101;
    jlong id = next_id++;
    pdf_documents.emplace(id, std::move(file));
    return id;
}

extern "C" JNIEXPORT void JNICALL
Java_com_deepstudent_engines_pdf_PdfNative_close(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> guard(pdf_mutex);
    pdf_documents.erase(id);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deepstudent_engines_pdf_PdfNative_pageCount(JNIEnv* env, jobject, jlong id) {
    std::lock_guard<std::mutex> guard(pdf_mutex);
    PdfFile* file = pdf_find(env, id);
    return file ? FPDF_GetPageCount(file->document) : 0;
}
