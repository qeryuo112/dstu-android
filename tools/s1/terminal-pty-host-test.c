#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define BUFFER_SIZE 65536

static void fail(const char *message) {
    perror(message);
    exit(1);
}

static void write_all(int fd, const char *data, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t count = write(fd, data + offset, length - offset);
        if (count > 0) {
            offset += (size_t) count;
            continue;
        }
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) {
            struct pollfd pollfd = { .fd = fd, .events = POLLOUT };
            if (poll(&pollfd, 1, 1000) < 0 && errno != EINTR) fail("poll write");
            continue;
        }
        fail("write pty");
    }
}

static long now_ms(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec * 1000L + time.tv_nsec / 1000000L;
}

static void read_until(int fd, char *buffer, size_t size, const char *needle) {
    size_t used = 0;
    long deadline = now_ms() + 10000;
    while (now_ms() < deadline) {
        struct pollfd pollfd = { .fd = fd, .events = POLLIN };
        int ready = poll(&pollfd, 1, 100);
        if (ready < 0) {
            if (errno == EINTR) continue;
            fail("poll read");
        }
        if (ready == 0) continue;
        ssize_t count = read(fd, buffer + used, size - used - 1);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count <= 0) break;
        used += (size_t) count;
        buffer[used] = '\0';
        if (strstr(buffer, needle) != NULL) return;
        if (used + 1 >= size) {
            fprintf(stderr, "PTY output buffer exhausted before marker\n");
            exit(1);
        }
    }
    fprintf(stderr, "Timed out waiting for PTY marker; buffer=%s\n", buffer);
    exit(1);
}

static void set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) fail("fcntl");
}

int main(void) {
    struct termios term;
    memset(&term, 0, sizeof(term));
    term.c_iflag = ICRNL | IXON | IXANY;
    term.c_oflag = OPOST | ONLCR;
    term.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHONL | IEXTEN;
    term.c_cflag = CS8 | CREAD;
    term.c_cc[VINTR] = 'C' - '@';
    term.c_cc[VQUIT] = '\\' - '@';
    term.c_cc[VERASE] = 0x7f;
    term.c_cc[VKILL] = 'U' - '@';
    term.c_cc[VEOF] = 'D' - '@';
    term.c_cc[VSTOP] = 'S' - '@';
    term.c_cc[VSUSP] = 'Z' - '@';
    term.c_cc[VSTART] = 'Q' - '@';
    term.c_cc[VMIN] = 1;
    term.c_cc[VTIME] = 0;

    struct winsize size = { .ws_row = 24, .ws_col = 80, .ws_xpixel = 0, .ws_ypixel = 0 };
    int master = -1;
    pid_t pid = forkpty(&master, NULL, &term, &size);
    if (pid < 0) fail("forkpty");
    if (pid == 0) {
        setenv("TERM", "xterm-256color", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("PATH", "/usr/local/bin:/usr/bin:/bin", 1);
        execl("/bin/sh", "sh", "-i", (char *) NULL);
        _exit(127);
    }

    set_nonblocking(master);
    char buffer[BUFFER_SIZE] = { 0 };
    const char *unicode_command = "stty -echo; printf '中文😀\\n'; stty size\n";
    write_all(master, unicode_command, strlen(unicode_command));
    read_until(master, buffer, sizeof(buffer), "中文😀");
    read_until(master, buffer, sizeof(buffer), "24 80");

    struct winsize resized = { .ws_row = 31, .ws_col = 91, .ws_xpixel = 0, .ws_ypixel = 0 };
    if (ioctl(master, TIOCSWINSZ, &resized) != 0) fail("TIOCSWINSZ");
    const char *size_command = "stty size\n";
    write_all(master, size_command, strlen(size_command));
    read_until(master, buffer, sizeof(buffer), "31 91");

    const char *sleep_command = "sleep 30\n";
    write_all(master, sleep_command, strlen(sleep_command));
    struct timespec pause = { .tv_sec = 0, .tv_nsec = 300000000 };
    nanosleep(&pause, NULL);
    write_all(master, "\003", 1);
    const char *after_interrupt = "printf 'AFTER_INTERRUPT\\n'; exit 37\n";
    write_all(master, after_interrupt, strlen(after_interrupt));
    read_until(master, buffer, sizeof(buffer), "AFTER_INTERRUPT");

    int status = 0;
    if (waitpid(pid, &status, 0) != pid) fail("waitpid");
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 37) {
        fprintf(stderr, "Unexpected shell exit status: %d\n", status);
        return 1;
    }
    close(master);
    puts("pty host: stty size, resize, UTF-8, Ctrl+C and exit 37 verified");
    return 0;
}
