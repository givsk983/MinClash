#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

static void fail(const char *operation) {
    perror(operation);
    exit(EXIT_FAILURE);
}

static void write_all(int fd, const char *buffer, size_t size) {
    while (size > 0) {
        ssize_t written = write(fd, buffer, size);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) fail("write");
        buffer += written;
        size -= written;
    }
}

static int keepalive_check(const char *mode) {
    int idle, interval;
    if (strcmp(mode, "conservative") == 0) { idle = 180; interval = 30; }
    else if (strcmp(mode, "balanced") == 0) { idle = 120; interval = 20; }
    else if (strcmp(mode, "aggressive") == 0) { idle = 60; interval = 15; }
    else return EXIT_FAILURE;
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return EXIT_FAILURE;
    const int enabled = 1, count = 3;
    int result = setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &enabled, sizeof(enabled)) == 0 &&
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle)) == 0 &&
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval)) == 0 &&
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count)) == 0;
    close(fd);
    return result ? EXIT_SUCCESS : EXIT_FAILURE;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--keepalive-check") == 0) {
        if (argc > 3) return EXIT_FAILURE;
        return keepalive_check(argc == 3 ? argv[2] : "balanced");
    }
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (argc != 2 || strlen(argv[1]) >= sizeof(address.sun_path)) {
        fputs("Expected a Unix socket path.\n", stderr);
        return EXIT_FAILURE;
    }
    strcpy(address.sun_path, argv[1]);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) fail("socket");
    struct timeval timeout = {.tv_sec = 8};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0 ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) < 0) fail("setsockopt");
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) fail("connect");

    // Requests include a content length and ask the server to close the connection.
    char buffer[8192];
    ssize_t count;
    while ((count = read(STDIN_FILENO, buffer, sizeof(buffer))) != 0) {
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) fail("read request");
        write_all(fd, buffer, count);
    }
    while ((count = read(fd, buffer, sizeof(buffer))) != 0) {
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) fail("read response");
        write_all(STDOUT_FILENO, buffer, count);
    }
    close(fd);
    return EXIT_SUCCESS;
}
