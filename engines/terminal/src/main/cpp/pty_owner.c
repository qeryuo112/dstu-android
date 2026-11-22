#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

static _Thread_local pid_t created_pid = -1;
static _Thread_local int created_fd = -1;

int __real_forkpty(int *, char *, const struct termios *, const struct winsize *);
int __wrap_forkpty(int *master, char *name, const struct termios *term, const struct winsize *size) {
    int pid = __real_forkpty(master, name, term, size);
    if (pid > 0) {
        created_pid = pid;
        created_fd = *master;
    }
    return pid;
}

jintArray operit_create_subprocess(JNIEnv *, jobject, jobjectArray, jobjectArray, jstring);

JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess(
        JNIEnv *env, jobject object, jobjectArray command, jobjectArray environment, jstring cwd) {
    created_pid = -1;
    created_fd = -1;
    jintArray result = operit_create_subprocess(env, object, command, environment, cwd);
    if (result == NULL || (*env)->ExceptionCheck(env)) {
        if (created_pid > 0) {
            kill(created_pid, SIGKILL);
            while (waitpid(created_pid, NULL, 0) == -1 && errno == EINTR) {}
        }
        if (created_fd >= 0) close(created_fd);
    }
    created_pid = -1;
    created_fd = -1;
    return result;
}

/* forkpty creates a session led by pid. Never signal a group outside that session. */
static void signal_owned_group(pid_t group, pid_t session, int signal_number) {
    if (group > 0 && getsid(group) == session) {
        kill(-group, signal_number);
    }
}

static int is_child(pid_t pid) {
    char path[64];
    char data[2048];
    snprintf(path, sizeof(path), "/proc/%d/stat", (int)pid);
    FILE *file = fopen(path, "r");
    if (file == NULL) return 0;
    size_t count = fread(data, 1, sizeof(data) - 1, file);
    fclose(file);
    data[count] = '\0';
    char *end = strrchr(data, ')');
    char state;
    int parent;
    return end != NULL && sscanf(end + 1, " %c %d", &state, &parent) == 2 && parent == getpid();
}

static void signal_session(pid_t foreground, pid_t pid, int signal_number) {
    if (!is_child(pid)) return;
    signal_owned_group(foreground, pid, signal_number);
    if (foreground != pid) signal_owned_group(pid, pid, signal_number);
    kill(pid, signal_number);
}

JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_setCloseOnExec(
        JNIEnv *env, jobject unused, jint fd) {
    (void)env;
    (void)unused;
    int flags;
    do {
        flags = fcntl(fd, F_GETFD);
    } while (flags == -1 && errno == EINTR);
    if (flags == -1) return errno;
    int result;
    do {
        result = fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
    } while (result == -1 && errno == EINTR);
    return result == -1 ? errno : 0;
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_signalSession(
        JNIEnv *env, jobject unused, jint fd, jint pid, jint signal_number) {
    (void)env;
    (void)unused;
    pid_t foreground = tcgetpgrp(fd);
    /* The parent may request stop before forkpty's child has called setsid(). */
    signal_session(foreground, pid, signal_number);
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_terminateSession(
        JNIEnv *env, jobject unused, jint fd, jint pid) {
    (void)env;
    (void)unused;
    pid_t foreground = tcgetpgrp(fd);
    signal_session(foreground, pid, SIGTERM);
    struct timespec remaining = { .tv_sec = 0, .tv_nsec = 250000000 };
    while (nanosleep(&remaining, &remaining) == -1 && errno == EINTR) {}
    signal_session(foreground, pid, SIGKILL);
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_closeRaw(
        JNIEnv *env, jobject unused, jint fd) {
    (void)env;
    (void)unused;
    close(fd);
}

JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_waitForNoHang(
        JNIEnv *env, jobject unused, jint pid) {
    (void)env;
    (void)unused;
    int status;
    pid_t result;
    do {
        result = waitpid(pid, &status, WNOHANG);
    } while (result == -1 && errno == EINTR);
    if (result == 0) return INT_MIN;
    if (result != pid) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
