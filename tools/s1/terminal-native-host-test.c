#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <termios.h>

static int emulate_fork;
static int created_fd;
static pid_t created_pid;

int __wrap_forkpty(int *, char *, const struct termios *, const struct winsize *);
int __real_forkpty(int *master, char *name, const struct termios *term, const struct winsize *size) {
    (void)name;
    (void)term;
    (void)size;
    if (emulate_fork) {
        int pipe_fds[2];
        if (pipe(pipe_fds) != 0) return -1;
        pid_t pid = fork();
        if (pid == 0) {
            close(pipe_fds[0]);
            close(pipe_fds[1]);
            for (;;) pause();
        }
        close(pipe_fds[1]);
        if (pid < 0) {
            close(pipe_fds[0]);
            return -1;
        }
        *master = created_fd = pipe_fds[0];
        created_pid = pid;
        return pid;
    }
    return -1;
}

jintArray operit_create_subprocess(JNIEnv *env, jobject object, jobjectArray command,
                                   jobjectArray environment, jstring cwd) {
    (void)env;
    (void)object;
    (void)command;
    (void)environment;
    (void)cwd;
    int master;
    if (emulate_fork) __wrap_forkpty(&master, NULL, NULL, NULL);
    return NULL;
}

JNIEXPORT jint JNICALL Java_com_ai_assistance_operit_terminal_Pty_00024Companion_setCloseOnExec(
    JNIEnv *, jobject, jint);
JNIEXPORT void JNICALL Java_com_ai_assistance_operit_terminal_Pty_00024Companion_signalSession(
    JNIEnv *, jobject, jint, jint, jint);
JNIEXPORT void JNICALL Java_com_ai_assistance_operit_terminal_Pty_00024Companion_terminateSession(
    JNIEnv *, jobject, jint, jint);
JNIEXPORT jint JNICALL Java_com_ai_assistance_operit_terminal_Pty_00024Companion_waitForNoHang(
    JNIEnv *, jobject, jint);
JNIEXPORT jintArray JNICALL Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess(
    JNIEnv *, jobject, jobjectArray, jobjectArray, jstring);

static void require(int result, const char *what) {
    if (!result) {
        perror(what);
        exit(1);
    }
}

static void child_session(int ready) {
    require(setsid() > 0, "setsid");
    require(write(ready, "x", 1) == 1, "ready");
    for (;;) pause();
}

static pid_t spawn_child(void) {
    int ready[2];
    require(pipe(ready) == 0, "pipe");
    pid_t pid = fork();
    require(pid >= 0, "fork");
    if (pid == 0) {
        close(ready[0]);
        child_session(ready[1]);
        _exit(1);
    }
    close(ready[1]);
    char byte;
    require(read(ready[0], &byte, 1) == 1, "child ready");
    close(ready[0]);
    return pid;
}

int main(void) {
    int descriptors[2];
    require(pipe(descriptors) == 0, "pipe");
    require(Java_com_ai_assistance_operit_terminal_Pty_00024Companion_setCloseOnExec(
        NULL, NULL, descriptors[0]) == 0, "cloexec");
    require((fcntl(descriptors[0], F_GETFD) & FD_CLOEXEC) != 0, "cloexec flag");
    close(descriptors[0]);
    close(descriptors[1]);

    emulate_fork = 1;
    require(Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess(
        NULL, NULL, NULL, NULL, NULL) == NULL, "failed JNI allocation");
    require(fcntl(created_fd, F_GETFD) == -1 && errno == EBADF, "failed create closes master");
    require(waitpid(created_pid, NULL, WNOHANG) == -1 && errno == ECHILD, "failed create reaps child");
    emulate_fork = 0;

    pid_t pid = spawn_child();
    Java_com_ai_assistance_operit_terminal_Pty_00024Companion_terminateSession(NULL, NULL, -1, pid);
    int status = INT_MIN;
    for (int i = 0; i < 200 && status == INT_MIN; i++) {
        status = Java_com_ai_assistance_operit_terminal_Pty_00024Companion_waitForNoHang(NULL, NULL, pid);
        if (status == INT_MIN) usleep(10000);
    }
    require(status == 128 + SIGTERM || status == 128 + SIGKILL, "terminate and reap");
    require(waitpid(pid, NULL, WNOHANG) == -1 && errno == ECHILD, "already reaped");

    int ready[2];
    require(pipe(ready) == 0, "pipe");
    pid_t intermediary = fork();
    require(intermediary >= 0, "fork intermediary");
    if (intermediary == 0) {
        close(ready[0]);
        pid_t unrelated = spawn_child();
        require(write(ready[1], &unrelated, sizeof(unrelated)) == sizeof(unrelated), "report pid");
        close(ready[1]);
        int result;
        require(waitpid(unrelated, &result, 0) == unrelated, "reap unrelated");
        _exit(WIFSIGNALED(result) && WTERMSIG(result) == SIGTERM ? 0 : 1);
    }
    close(ready[1]);
    pid_t unrelated;
    require(read(ready[0], &unrelated, sizeof(unrelated)) == sizeof(unrelated), "read pid");
    close(ready[0]);
    Java_com_ai_assistance_operit_terminal_Pty_00024Companion_signalSession(NULL, NULL, -1, unrelated, SIGKILL);
    require(kill(unrelated, 0) == 0, "unrelated child must survive");
    require(kill(unrelated, SIGTERM) == 0, "finish unrelated");
    int result;
    require(waitpid(intermediary, &result, 0) == intermediary && WIFEXITED(result) && WEXITSTATUS(result) == 0,
        "unrelated child reaped by parent");
    puts("native host: failed-create cleanup, close-on-exec, termination, reaping and unrelated PID guard verified");
    return 0;
}
