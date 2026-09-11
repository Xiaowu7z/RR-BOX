/* RRBOX Root TUN supervisor. No shell, iptables, global sysctls or netd changes.
 * The ordinary app owns the core; only this bounded controller is privileged.
 * An authenticated IPC lease owns every route/rule and the nonpersistent TUN.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/if_tun.h>
#include <net/if.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "root_engine_owner.h"

#ifndef RRBOX_IP_PATH
#define RRBOX_IP_PATH "/system/bin/ip"
#endif
#ifndef IFF_TUN_EXCL
#define IFF_TUN_EXCL 0x8000
#endif

#define MAX_LINE 16384
#define MAX_UIDS 256
#define MAX_BUSINESS_RANGES 64
#define MAX_RANGES (MAX_UIDS + MAX_DNS * 4 + 2)
#define MAX_DNS 16
#define MAX_ROUTES 48
#define COMMAND_OUTPUT 65536
#define COMMAND_TIMEOUT_MS 2000
#define LEASE_MS 15000
#define CLEANUP_TIMEOUT_MS 12000
#define RULE_PRIORITY "9000"
#define SYSTEM_PEER_IPV4 "172.19.0.2/32"
#define SYSTEM_PEER_IPV6 "fdfe:dcba:9876::2/128"

struct uid_range {
    uint32_t first, last;
    int family; /* zero: both families; otherwise an explicit host destination. */
    char destination[INET6_ADDRSTRLEN + 8];
    int dns_protocol; /* 6/17: device resolver TCP/UDP port 53 only; zero: business/peer. */
    bool internal_peer; /* system-stack TCP return path, not application traffic. */
    bool added4, added6;
};
struct route_entry { int family; char prefix[INET6_ADDRSTRLEN + 8]; bool is_throw, added; };
struct engine {
    int socket_fd, tun_fd, lock_fd;
    int guardian_pipe;
    pid_t guardian_pid;
    pid_t app_pid;
    uid_t app_uid;
    uint64_t app_start;
    int64_t deadline_ms, lease_ms, cleanup_deadline_ms;
    char socket_name[80], tun_name[IFNAMSIZ], table[16];
    unsigned tun_index;
    struct uid_range ranges[MAX_RANGES];
    size_t range_count;
    struct route_entry routes[MAX_ROUTES];
    size_t route_count;
    char dns[MAX_DNS][INET6_ADDRSTRLEN];
    size_t dns_count;
    bool configured, active, activating, cleaned, cleanup_ok;
};

static struct engine current = { .socket_fd = -1, .tun_fd = -1, .lock_fd = -1, .guardian_pipe = -1 };
static volatile sig_atomic_t interrupted;
static const char *configuration_error = "invalid_config";
static const char *failure_stage = "startup";
static char command_failure[1024];

/* Only fixed ip argv and bounded command output; never node credentials/config.
 * Keep this on the control response so diagnostics cannot race a su pipe drain. */
static void describe_command_failure(const char *const *argv, int result, const char *output)
{
    size_t used = (size_t)snprintf(command_failure, sizeof(command_failure), "ip_exit=%d argv=", result);
    for (size_t i = 1; argv[i] && used < sizeof(command_failure) - 1; ++i) {
        int count = snprintf(command_failure + used, sizeof(command_failure) - used, "%s%s", i == 1 ? "" : " ", argv[i]);
        if (count < 0) break;
        size_t space = sizeof(command_failure) - used - 1;
        used += (size_t)count < space ? (size_t)count : space;
    }
    if (used < sizeof(command_failure) - 1)
        snprintf(command_failure + used, sizeof(command_failure) - used, " output=%s", output);
    for (char *p = command_failure; *p; ++p)
        if ((unsigned char)*p < 32 || (unsigned char)*p > 126) *p = ' ';
}

static void handle_signal(int number) { (void)number; interrupted = 1; }

static int64_t boot_ms(void)
{
    struct timespec value;
    if (clock_gettime(CLOCK_BOOTTIME, &value) != 0) return -1;
    return (int64_t)value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

static bool unsigned_number(const char *value, uint64_t maximum, uint64_t *answer)
{
    if (value == NULL || *value == '\0') return false;
    uint64_t result = 0;
    for (const unsigned char *p = (const unsigned char *)value; *p; ++p) {
        if (*p < '0' || *p > '9') return false;
        unsigned digit = *p - '0';
        if (result > maximum / 10 || (result == maximum / 10 && digit > maximum % 10)) return false;
        result = result * 10 + digit;
    }
    *answer = result;
    return true;
}

static bool app_alive(void)
{
    char path[64], buffer[4096];
    /* Android release processes may be nondumpable, making /proc inodes owned
     * by root. Process credentials, not inode ownership, establish identity. */
    snprintf(path, sizeof(path), "/proc/%d/status", (int)current.app_pid);
    int status_fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (status_fd < 0) return false;
    ssize_t status_count = read(status_fd, buffer, sizeof(buffer) - 1);
    close(status_fd);
    if (status_count <= 0 || status_count == (ssize_t)sizeof(buffer) - 1) return false;
    buffer[status_count] = '\0';
    const char *uid_line = strstr(buffer, "\nUid:");
    unsigned real_uid, effective_uid, saved_uid, filesystem_uid;
    if (uid_line == NULL || sscanf(uid_line + 5, "%u %u %u %u", &real_uid, &effective_uid, &saved_uid, &filesystem_uid) != 4 ||
        real_uid != current.app_uid || effective_uid != current.app_uid || saved_uid != current.app_uid || filesystem_uid != current.app_uid)
        return false;
    snprintf(path, sizeof(path), "/proc/%d/stat", (int)current.app_pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    ssize_t count = read(fd, buffer, sizeof(buffer) - 1);
    close(fd);
    if (count <= 0 || count == (ssize_t)sizeof(buffer) - 1) return false;
    buffer[count] = '\0';
    char *end = strrchr(buffer, ')');
    if (end == NULL || end[1] != ' ') return false;
    char *save = NULL;
    char *token = strtok_r(end + 2, " \n", &save);
    for (int field = 3; token != NULL; ++field, token = strtok_r(NULL, " \n", &save)) {
        if (field == 3 && (*token == 'Z' || *token == 'X')) return false;
        if (field == 22) {
            uint64_t start;
            return unsigned_number(token, UINT64_MAX, &start) && start == current.app_start;
        }
    }
    return false;
}

static bool session_alive(void)
{
    int64_t now = boot_ms();
    if (interrupted || now < 0 || !app_alive()) return false;
    if (!current.active && now >= current.deadline_ms) return false;
    if (current.socket_fd >= 0) {
        char byte;
        ssize_t count = recv(current.socket_fd, &byte, 1, MSG_PEEK | MSG_DONTWAIT);
        if (count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) return false;
    }
    if (current.guardian_pid > 0) {
        int status;
        pid_t found = waitpid(current.guardian_pid, &status, WNOHANG);
        if (found == current.guardian_pid || (found < 0 && errno != EINTR)) {
            current.guardian_pid = 0;
            return false;
        }
    }
    return current.activating || now < current.lease_ms;
}

static void guardian_pulse(void)
{
    if (current.guardian_pipe < 0) return;
    char byte = 'L';
    ssize_t ignored = write(current.guardian_pipe, &byte, 1);
    (void)ignored; /* Nonblocking; an existing queued pulse already renews the lease. */
}

/* Fixed argv only. Drain both outputs, bound child time and output, and reap only
 * our own child. A killed supervisor cannot leave a still-running ip command. */
static int run_ip(bool cleanup, char *output, size_t capacity, ...)
{
    if (!cleanup) command_failure[0] = '\0';
    if ((!cleanup && !session_alive()) ||
        (cleanup && current.cleanup_deadline_ms > 0 && boot_ms() >= current.cleanup_deadline_ms)) return -1;
    const char *arguments[24] = { RRBOX_IP_PATH };
    va_list values;
    va_start(values, capacity);
    size_t argc = 1;
    const char *argument;
    while ((argument = va_arg(values, const char *)) != NULL) {
        if (argc >= sizeof(arguments) / sizeof(arguments[0]) - 1) { va_end(values); return -1; }
        arguments[argc++] = argument;
    }
    va_end(values);
    arguments[argc] = NULL;
    if (capacity > 0) output[0] = '\0';
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC) != 0) {
        if (!cleanup) describe_command_failure(arguments, -1, strerror(errno));
        return -1;
    }
    pid_t parent = getpid();
    pid_t child = fork();
    if (child < 0) {
        if (!cleanup) describe_command_failure(arguments, -1, strerror(errno));
        close(pipes[0]); close(pipes[1]); return -1;
    }
    if (child == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(126);
        signal(SIGTERM, SIG_DFL); signal(SIGINT, SIG_DFL); signal(SIGHUP, SIG_DFL);
        close(pipes[0]);
        if (dup2(pipes[1], STDOUT_FILENO) < 0 || dup2(pipes[1], STDERR_FILENO) < 0) _exit(126);
        close(pipes[1]);
        execv(RRBOX_IP_PATH, (char *const *)arguments);
        dprintf(STDERR_FILENO, "exec failed: %s", strerror(errno));
        _exit(127);
    }
    close(pipes[1]);
    int flags = fcntl(pipes[0], F_GETFL);
    if (flags < 0 || fcntl(pipes[0], F_SETFL, flags | O_NONBLOCK) < 0) {
        if (!cleanup) describe_command_failure(arguments, -1, strerror(errno));
        kill(child, SIGKILL); (void)waitpid(child, NULL, 0); close(pipes[0]); return -1;
    }
    int64_t end = boot_ms() + COMMAND_TIMEOUT_MS;
    if (cleanup && current.cleanup_deadline_ms > 0 && current.cleanup_deadline_ms < end) end = current.cleanup_deadline_ms;
    bool failed = false, exited = false;
    int status = 0;
    size_t total = 0, observed = 0;
    char diagnostic[384] = "";
    size_t diagnostic_size = 0;
    for (;;) {
        char data[2048];
        ssize_t count;
        while ((count = read(pipes[0], data, sizeof(data))) > 0) {
            size_t capture = (size_t)count < sizeof(diagnostic) - diagnostic_size - 1
                ? (size_t)count : sizeof(diagnostic) - diagnostic_size - 1;
            memcpy(diagnostic + diagnostic_size, data, capture);
            diagnostic_size += capture;
            diagnostic[diagnostic_size] = '\0';
            observed += (size_t)count;
            if (observed > COMMAND_OUTPUT || boot_ms() >= end) { failed = true; break; }
            if (capacity > 0) {
                size_t space = total < capacity - 1 ? capacity - 1 - total : 0;
                size_t copy = (size_t)count < space ? (size_t)count : space;
                if (copy) memcpy(output + total, data, copy);
                total += copy;
                output[total] = '\0';
                if (copy != (size_t)count) failed = true;
            }
            if (failed) break;
        }
        if (!exited) {
            pid_t state = waitpid(child, &status, WNOHANG);
            if (state == child) exited = true;
            if (state < 0 && errno != EINTR) { failed = true; break; }
        }
        if (exited && count == 0) break;
        if (failed || boot_ms() >= end || (!cleanup && !session_alive())) { failed = true; break; }
        struct pollfd wait = { .fd = pipes[0], .events = POLLIN };
        (void)poll(&wait, 1, 20);
    }
    if (!exited) {
        kill(child, SIGKILL);
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    }
    close(pipes[0]);
    if (!cleanup && !session_alive()) failed = true;
    /* Initial activation is one authenticated command with an absolute boot
     * deadline. Renew the guardian only when a bounded ip operation completes. */
    if (!failed && !cleanup && current.activating && session_alive()) guardian_pulse();
    int result = failed || !WIFEXITED(status) ? -1 : WEXITSTATUS(status);
    if (!cleanup && result != 0)
        describe_command_failure(arguments, result, *diagnostic ? diagnostic : "no output (timeout/session closed if exit=-1)");
    return result;
}

static bool send_text(const char *text)
{
    size_t left = strlen(text);
    int64_t deadline = boot_ms() + 1000;
    while (left) {
        ssize_t count = send(current.socket_fd, text, left, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (count > 0) { text += count; left -= (size_t)count; continue; }
        if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) return false;
        if (boot_ms() >= deadline) return false;
        struct pollfd wait = { .fd = current.socket_fd, .events = POLLOUT };
        (void)poll(&wait, 1, 20);
    }
    return true;
}

static int read_line(char *line, size_t capacity)
{
    size_t used = 0;
    /* Fixed command deadline defeats a peer that drip-feeds an unfinished line. */
    int64_t command_deadline = boot_ms() + LEASE_MS;
    while (used < capacity - 1) {
        if (!session_alive() || boot_ms() >= command_deadline) return -1;
        struct pollfd wait = { .fd = current.socket_fd, .events = POLLIN };
        int ready = poll(&wait, 1, 200);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return -1;
        if (ready == 0) continue;
        if ((wait.revents & (POLLIN | POLLHUP)) == 0) return -1;
        char value;
        ssize_t count = recv(current.socket_fd, &value, 1, MSG_DONTWAIT);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count != 1) return -1;
        if (value == '\n') {
            line[used] = '\0'; current.lease_ms = boot_ms() + LEASE_MS; guardian_pulse(); return (int)used;
        }
        if (value < 32 || value > 126) return -1;
        line[used++] = value;
    }
    return -1;
}

static bool read_arguments(int argc, char **argv)
{
    if (argc != 11) return false;
    const char *names[] = { "--socket", "--uid", "--pid", "--start", "--deadline" };
    const char *values[5] = { NULL };
    for (int i = 1; i < argc; i += 2) {
        int index;
        for (index = 0; index < 5; ++index) if (strcmp(argv[i], names[index]) == 0) break;
        if (index == 5 || values[index] != NULL) return false;
        values[index] = argv[i + 1];
    }
    const char *prefix = "rrbox-root-";
    size_t prefix_length = strlen(prefix);
    if (strncmp(values[0], prefix, prefix_length) != 0) return false;
    size_t hex_length = strlen(values[0] + prefix_length);
    if (hex_length < 16 || hex_length > 32) return false;
    for (const char *p = values[0] + prefix_length; *p; ++p)
        if (!(*p >= '0' && *p <= '9') && !(*p >= 'a' && *p <= 'f')) return false;
    snprintf(current.socket_name, sizeof(current.socket_name), "%s", values[0]);
    snprintf(current.tun_name, sizeof(current.tun_name), "rr%.12s", values[0] + prefix_length);
    uint64_t uid, pid, start, deadline;
    if (!unsigned_number(values[1], INT_MAX, &uid) || uid < 10000 ||
        !unsigned_number(values[2], INT_MAX, &pid) || pid < 2 ||
        !unsigned_number(values[3], UINT64_MAX, &start) || start == 0 ||
        !unsigned_number(values[4], INT64_MAX / 1000, &deadline)) return false;
    current.app_uid = (uid_t)uid; current.app_pid = (pid_t)pid; current.app_start = start;
    current.deadline_ms = (int64_t)deadline * 1000;
    int64_t now = boot_ms();
    current.lease_ms = now + LEASE_MS;
    return now >= 0 && current.deadline_ms > now && current.deadline_ms <= now + 60000 && app_alive();
}

static bool connect_app(void)
{
    /* An abstract lock needs no persistent root-owned files or PID-file trust. */
    current.lock_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (current.lock_fd < 0) return false;
    struct sockaddr_un lock_address = { .sun_family = AF_UNIX };
    /* Priority 9000 is namespace-wide, including other Android user/profile
     * UIDs. The lock has exactly the same scope as that reserved resource. */
    int length = snprintf(lock_address.sun_path + 1, sizeof(lock_address.sun_path) - 1,
                          "rrbox-root-policy-owner");
    socklen_t lock_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + length);
    if (bind(current.lock_fd, (struct sockaddr *)&lock_address, lock_length) != 0) return false;
    current.socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (current.socket_fd < 0) return false;
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t name_length = strlen(current.socket_name);
    memcpy(address.sun_path + 1, current.socket_name, name_length);
    socklen_t address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_length);
    if (connect(current.socket_fd, (struct sockaddr *)&address, address_length) != 0) return false;
    struct ucred peer;
    socklen_t peer_length = sizeof(peer);
    return getsockopt(current.socket_fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_length) == 0 &&
           peer_length == sizeof(peer) && peer.uid == current.app_uid && peer.pid == current.app_pid && app_alive();
}

static bool create_tun(void)
{
    static const char *const nodes[] = { "/dev/tun", "/dev/net/tun" };
    for (size_t i = 0; i < sizeof(nodes) / sizeof(*nodes); ++i) {
        int fd = open(nodes[i], O_RDWR | O_CLOEXEC | O_NONBLOCK | O_NOFOLLOW);
        if (fd < 0) continue;
        struct stat info;
        if (fstat(fd, &info) == 0 && S_ISCHR(info.st_mode) && major(info.st_rdev) == 10 && minor(info.st_rdev) == 200) {
            current.tun_fd = fd;
            break;
        }
        close(fd);
    }
    if (current.tun_fd < 0) return false;
    struct ifreq request = { .ifr_flags = IFF_TUN | IFF_NO_PI | IFF_TUN_EXCL };
    snprintf(request.ifr_name, sizeof(request.ifr_name), "%s", current.tun_name);
    if (ioctl(current.tun_fd, TUNSETIFF, &request) != 0) return false;
    if (strcmp(request.ifr_name, current.tun_name) != 0) return false;
    current.tun_index = if_nametoindex(current.tun_name);
    if (current.tun_index == 0) return false;
    if (run_ip(false, NULL, 0, "link", "set", "dev", current.tun_name, "mtu", "1500", NULL) != 0 ||
        run_ip(false, NULL, 0, "-4", "addr", "add", "172.19.0.1/30", "dev", current.tun_name, NULL) != 0 ||
        run_ip(false, NULL, 0, "-6", "addr", "add", "fdfe:dcba:9876::1/126", "dev", current.tun_name, "nodad", NULL) != 0 ||
        run_ip(false, NULL, 0, "link", "set", "dev", current.tun_name, "up", NULL) != 0) return false;
    /* max(all, interface) applies to rp_filter. Loose mode tolerates asymmetric
     * ingress without weakening global or physical-interface settings. */
    char path[128];
    snprintf(path, sizeof(path), "/proc/sys/net/ipv4/conf/%s/rp_filter", current.tun_name);
    int fd = open(path, O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    ssize_t count = write(fd, "2\n", 2);
    close(fd);
    return count == 2;
}

static bool send_tun(void)
{
    char marker = 'F';
    struct iovec io = { .iov_base = &marker, .iov_len = 1 };
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(int))]; } ancillary;
    memset(&ancillary, 0, sizeof(ancillary));
    struct msghdr message = { .msg_iov = &io, .msg_iovlen = 1,
        .msg_control = ancillary.bytes, .msg_controllen = sizeof(ancillary.bytes) };
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET; header->cmsg_type = SCM_RIGHTS; header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &current.tun_fd, sizeof(int));
    if (sendmsg(current.socket_fd, &message, MSG_NOSIGNAL) != 1) return false;
    char line[64];
    snprintf(line, sizeof(line), "READY %s\n", current.tun_name);
    return send_text(line);
}

static int compare_uid(const void *left, const void *right)
{
    uint32_t a = *(const uint32_t *)left, b = *(const uint32_t *)right;
    return a > b ? 1 : a < b ? -1 : 0;
}

static bool parse_uid_list(char *value, uint32_t *uids, size_t *count)
{
    *count = 0;
    if (strcmp(value, "-") == 0) return true;
    if (*value == '\0' || *value == ',' || value[strlen(value) - 1] == ',' || strstr(value, ",,")) return false;
    char *save = NULL;
    for (char *token = strtok_r(value, ",", &save); token; token = strtok_r(NULL, ",", &save)) {
        uint64_t uid;
        if (*count >= MAX_UIDS || !unsigned_number(token, INT_MAX, &uid)) return false;
        uids[(*count)++] = (uint32_t)uid;
    }
    qsort(uids, *count, sizeof(*uids), compare_uid);
    size_t unique = 0;
    for (size_t i = 0; i < *count; ++i) if (unique == 0 || uids[i] != uids[unique - 1]) uids[unique++] = uids[i];
    *count = unique;
    return true;
}

static bool append_range(uint32_t first, uint32_t last)
{
    if (first > last) return true;
    if (current.range_count > 0 && current.ranges[current.range_count - 1].last + 1 == first) {
        current.ranges[current.range_count - 1].last = last;
        return true;
    }
    if (current.range_count >= MAX_RANGES) return false;
    current.ranges[current.range_count++] = (struct uid_range){ .first = first, .last = last };
    return true;
}

static bool configure(char *line)
{
    configuration_error = "invalid_config";
    char *fields[6], *save = NULL;
    size_t count = 0;
    for (char *part = strtok_r(line, " ", &save); part; part = strtok_r(NULL, " ", &save)) {
        if (count == 6) return false;
        fields[count++] = part;
    }
    if (count != 5 || strcmp(fields[0], "CONFIG") != 0 || current.configured) return false;
    bool all = strcmp(fields[1], "all") == 0;
    if (!all && strcmp(fields[1], "include") != 0) return false;
    uint32_t included[MAX_UIDS], excluded[MAX_UIDS + 1];
    size_t included_count, excluded_count;
    if (!parse_uid_list(fields[2], included, &included_count) ||
        !parse_uid_list(fields[3], excluded, &excluded_count) ||
        (all && included_count != 0) || (!all && included_count == 0)) return false;
    excluded[excluded_count++] = current.app_uid;
    qsort(excluded, excluded_count, sizeof(*excluded), compare_uid);
    current.range_count = 0;
    if (all) {
        uint64_t next = 0;
        for (size_t i = 0; i < excluded_count; ++i) {
            uint64_t stop = excluded[i];
            if (stop > next && !append_range((uint32_t)next, (uint32_t)(stop - 1))) return false;
            if (stop >= next) next = stop + 1;
        }
        if (next <= INT_MAX && !append_range((uint32_t)next, INT_MAX)) return false;
    } else {
        for (size_t i = 0; i < included_count; ++i) {
            bool skip = false;
            for (size_t j = 0; j < excluded_count; ++j) if (included[i] == excluded[j]) skip = true;
            if (!skip && !append_range(included[i], included[i])) return false;
        }
    }
    if (current.range_count == 0) return false;
    if (current.range_count > MAX_BUSINESS_RANGES) { configuration_error = "too_many_uid_ranges"; return false; }
    current.dns_count = 0;
    if (strcmp(fields[4], "-") != 0) {
        char *dns = fields[4];
        if (*dns == '\0' || *dns == ',' || dns[strlen(dns) - 1] == ',' || strstr(dns, ",,")) return false;
        char *dns_save = NULL;
        for (char *value = strtok_r(dns, ",", &dns_save); value; value = strtok_r(NULL, ",", &dns_save)) {
            if (current.dns_count >= MAX_DNS || strlen(value) >= INET6_ADDRSTRLEN) return false;
            unsigned char address[16];
            int family = strchr(value, ':') ? AF_INET6 : AF_INET;
            if (inet_pton(family, value, address) != 1) return false;
            char normalized[INET6_ADDRSTRLEN];
            if (inet_ntop(family, address, normalized, sizeof(normalized)) == NULL) return false;
            bool duplicate = false;
            for (size_t i = 0; i < current.dns_count; ++i) if (strcmp(current.dns[i], normalized) == 0) duplicate = true;
            if (!duplicate) snprintf(current.dns[current.dns_count++], INET6_ADDRSTRLEN, "%s", normalized);
        }
    }
    current.configured = true;
    return true;
}

static bool priority_free(const char *rules)
{
    const char *line = rules;
    while (*line) {
        char *end;
        unsigned long priority = strtoul(line, &end, 10);
        if (end != line && *end == ':' && priority == 9000) return false;
        const char *next = strchr(line, '\n');
        if (next == NULL) break;
        line = next + 1;
    }
    return true;
}

static bool reserve_table(void)
{
    failure_stage = "allocate_rule_snapshot";
    char *rules4 = malloc(COMMAND_OUTPUT), *rules6 = malloc(COMMAND_OUTPUT), *routes = malloc(COMMAND_OUTPUT);
    bool result = false;
    if (!rules4 || !rules6 || !routes) goto done;
    failure_stage = "read_ipv4_rules";
    if (run_ip(false, rules4, COMMAND_OUTPUT, "-4", "rule", "show", NULL) != 0) goto done;
    failure_stage = "read_ipv6_rules";
    if (run_ip(false, rules6, COMMAND_OUTPUT, "-6", "rule", "show", NULL) != 0) goto done;
    failure_stage = "priority_9000_in_use";
    if (!priority_free(rules4) || !priority_free(rules6)) goto done;
    unsigned hash = 0;
    for (const char *p = current.socket_name; *p; ++p) hash = hash * 33 + (unsigned char)*p;
    for (unsigned attempt = 0; attempt < 32; ++attempt) {
        snprintf(current.table, sizeof(current.table), "%u", 42000 + (hash + attempt) % 1000);
        bool free_table = true;
        for (int family = 4; family <= 6; family += 2) {
            /* Android's iproute2-ss171113 has no -N option. Filtering by numeric
             * table uses the kernel table ID even when output prints an alias. */
            failure_stage = family == 4 ? "check_ipv4_table_rules" : "check_ipv6_table_rules";
            if (run_ip(false, routes, COMMAND_OUTPUT, family == 4 ? "-4" : "-6",
                       "rule", "show", "table", current.table, NULL) != 0) goto done;
            if (*routes) { free_table = false; break; }
            failure_stage = family == 4 ? "check_ipv4_table_routes" : "check_ipv6_table_routes";
            int status = run_ip(false, routes, COMMAND_OUTPUT, family == 4 ? "-4" : "-6",
                                "route", "show", "table", current.table, NULL);
            if (status == 0 && *routes == '\0') continue;
            if (status > 0 && strstr(routes, "FIB table does not exist") != NULL) { command_failure[0] = '\0'; continue; }
            if (status != 0) goto done;
            free_table = false;
            break;
        }
        if (free_table) { result = true; break; }
        failure_stage = "no_free_route_table";
    }
done:
    free(rules4); free(rules6); free(routes);
    return result;
}

static bool plan_route(int family, const char *prefix, bool is_throw)
{
    for (size_t i = 0; i < current.route_count; ++i)
        if (current.routes[i].family == family && strcmp(current.routes[i].prefix, prefix) == 0)
            return current.routes[i].is_throw == is_throw;
    if (current.route_count == MAX_ROUTES) return false;
    struct route_entry *route = &current.routes[current.route_count++];
    *route = (struct route_entry){ .family = family, .is_throw = is_throw };
    snprintf(route->prefix, sizeof(route->prefix), "%s", prefix);
    return true;
}

static int change_route(struct route_entry *route, bool remove)
{
    const char *family = route->family == 4 ? "-4" : "-6";
    const char *operation = remove ? "del" : "add";
    if (route->is_throw)
        return run_ip(remove, NULL, 0, family, "route", operation, "throw", route->prefix, "table", current.table, NULL);
    return run_ip(remove, NULL, 0, family, "route", operation, route->prefix, "dev", current.tun_name,
                  "table", current.table, NULL);
}

#include "root_engine_rules.h"

static int change_rule(struct uid_range *range, int family, bool remove)
{
    if (range->internal_peer)
        return run_ip(remove, NULL, 0, family == 4 ? "-4" : "-6", "rule", remove ? "del" : "add",
                      "pref", RULE_PRIORITY, "to", range->destination, "lookup", current.table, NULL);
    char uid_range[32];
    snprintf(uid_range, sizeof(uid_range), "%u-%u", range->first, range->last);
    if (range->dns_protocol) return rr_change_dns_rule(range, family, remove);
    if (*range->destination)
        return run_ip(remove, NULL, 0, family == 4 ? "-4" : "-6", "rule", remove ? "del" : "add",
                      "pref", RULE_PRIORITY, "iif", "lo", "to", range->destination,
                      "uidrange", uid_range, "lookup", current.table, NULL);
    return run_ip(remove, NULL, 0, family == 4 ? "-4" : "-6", "rule", remove ? "del" : "add",
                  "pref", RULE_PRIORITY, "iif", "lo", "uidrange", uid_range, "lookup", current.table, NULL);
}

static bool verify_rules(int family, bool expect_present)
{
    /* Old Android ip also omits modern selectors when printing rules. Read the
     * kernel attributes directly so missing/widened DNS scope cannot pass. */
    return rr_verify_rule_snapshot(family, expect_present);
}

static bool start_guardian(void);

static bool activate(void)
{
    failure_stage = "activation_precondition";
    command_failure[0] = '\0';
    if (!current.configured || current.active || !session_alive()) return false;
    if (!reserve_table()) return false;
    failure_stage = "plan_routes";
    static const char *const throws4[] = { "0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4" };
    static const char *const throws6[] = { "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8" };
    if (!plan_route(4, "default", false) || !plan_route(6, "default", false)) return false;
    for (size_t i = 0; i < sizeof(throws4) / sizeof(*throws4); ++i) if (!plan_route(4, throws4[i], true)) return false;
    for (size_t i = 0; i < sizeof(throws6) / sizeof(*throws6); ++i) if (!plan_route(6, throws6[i], true)) return false;
    /* sing-tun's system stack rewrites TCP into a local listener at the first
     * TUN address, with its next address as the synthetic peer. Listener replies
     * belong to RRBOX's exempt UID. Android does not normally consult the main
     * routing table, so the connected routes installed by `ip addr` cannot
     * return these replies to TUN. Keep exactly these two internal hosts reachable
     * for every UID and kernel reverse-path lookup; Internet egress stays exempt.
     * These /32 and /128 routes override the LAN throws without capturing LANs.
     * Prepend the peer rules so setup establishes the return path before capture
     * and rollback removes them after all business/DNS selection rules. */
    if (!plan_route(4, SYSTEM_PEER_IPV4, false) || !plan_route(6, SYSTEM_PEER_IPV6, false) ||
        current.range_count > MAX_RANGES - 2) return false;
    memmove(current.ranges + 2, current.ranges, current.range_count * sizeof(*current.ranges));
    current.range_count += 2;
    current.ranges[0] = (struct uid_range){ .family = 4, .internal_peer = true };
    current.ranges[1] = (struct uid_range){ .family = 6, .internal_peer = true };
    snprintf(current.ranges[0].destination, sizeof(current.ranges[0].destination), "%s", SYSTEM_PEER_IPV4);
    snprintf(current.ranges[1].destination, sizeof(current.ranges[1].destination), "%s", SYSTEM_PEER_IPV6);
    for (size_t i = 0; i < current.dns_count; ++i) {
        bool v6 = strchr(current.dns[i], ':') != NULL;
        /* Local services remain local. Other resolver addresses override LAN throws. */
        struct in_addr v4;
        if ((!v6 && inet_pton(AF_INET, current.dns[i], &v4) == 1 &&
             (ntohl(v4.s_addr) >> 24 == 127 || v4.s_addr == INADDR_ANY)) ||
            (v6 && (strcmp(current.dns[i], "::1") == 0 || strcmp(current.dns[i], "::") == 0))) continue;
        char prefix[INET6_ADDRSTRLEN + 8];
        snprintf(prefix, sizeof(prefix), "%s/%d", current.dns[i], v6 ? 128 : 32);
        if (!plan_route(v6 ? 6 : 4, prefix, false)) return false;
        /* Android's shared resolver cannot reliably retain the requesting app's
         * socket UID. Only TCP/UDP DNS has shared scope; a resolver can also be
         * the LAN router or a public HTTPS endpoint. Capturing its entire IP
         * would proxy unselected apps' business traffic with smart routing off.
         * Never fall back to an unbounded host rule on unsupported kernels. */
        {
            uint32_t starts[] = { 0, (uint32_t)current.app_uid + 1 };
            uint32_t ends[] = { (uint32_t)current.app_uid - 1, INT_MAX };
            for (size_t j = 0; j < 2; ++j) {
                if (starts[j] > ends[j]) continue;
                for (int protocol = 6; protocol <= 17; protocol += 11) {
                    if (current.range_count >= MAX_RANGES) return false;
                    struct uid_range *range = &current.ranges[current.range_count++];
                    *range = (struct uid_range){ .first = starts[j], .last = ends[j],
                        .family = v6 ? 6 : 4, .dns_protocol = protocol };
                    snprintf(range->destination, sizeof(range->destination), "%s", prefix);
                }
            }
        }
    }
    current.activating = true;
    /* Fork with the entire validated intended transaction before the first
     * mutation. Exact idempotent rollback then covers partial setup and SIGKILL
     * at any operation boundary without a persistent privileged journal. */
    for (size_t i = 0; i < current.route_count; ++i) current.routes[i].added = true;
    for (size_t i = 0; i < current.range_count; ++i) {
        current.ranges[i].added4 = current.ranges[i].family != 6;
        current.ranges[i].added6 = current.ranges[i].family != 4;
    }
    failure_stage = "start_cleanup_guardian";
    if (!start_guardian()) return false;
    for (size_t i = 0; i < current.route_count; ++i) {
        /* Journal intent in memory before invoking ip: timeout can race a
         * successful kernel operation, so rollback must include that attempt. */
        current.routes[i].added = true;
        failure_stage = current.routes[i].family == 4 ? "install_ipv4_route" : "install_ipv6_route";
        if (change_route(&current.routes[i], false) != 0) return false;
    }
    for (size_t i = 0; i < current.range_count; ++i) {
        if (current.ranges[i].family != 6) {
            current.ranges[i].added4 = true;
            failure_stage = current.ranges[i].internal_peer ? "install_ipv4_system_peer_rule" :
                current.ranges[i].dns_protocol ? "install_ipv4_dns_port_rule" : "install_ipv4_uid_rule";
            if (change_rule(&current.ranges[i], 4, false) != 0) return false;
        }
        if (current.ranges[i].family != 4) {
            current.ranges[i].added6 = true;
            failure_stage = current.ranges[i].internal_peer ? "install_ipv6_system_peer_rule" :
                current.ranges[i].dns_protocol ? "install_ipv6_dns_port_rule" : "install_ipv6_uid_rule";
            if (change_rule(&current.ranges[i], 6, false) != 0) return false;
        }
    }
    failure_stage = "verify_ipv4_uid_rules";
    if (!verify_rules(4, true)) return false;
    failure_stage = "verify_ipv6_uid_rules";
    if (!verify_rules(6, true)) return false;
    failure_stage = "activation_session_closed";
    if (!session_alive()) return false;
    current.active = true;
    current.activating = false;
    current.lease_ms = boot_ms() + LEASE_MS;
    return true;
}

static bool cleanup(void);

static bool start_guardian(void)
{
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC | O_NONBLOCK) != 0) return false;
    pid_t controller_pid = getpid();
    pid_t child = fork();
    if (child < 0) { close(pipes[0]); close(pipes[1]); return false; }
    if (child == 0) {
        close(pipes[1]);
        /* Keep the abstract ownership lock, TUN and diagnostic stdout. Do not
         * keep the app connection alive if the controller is killed. */
        if (current.socket_fd >= 0) close(current.socket_fd);
        current.socket_fd = -1;
        current.guardian_pid = 0;
        current.guardian_pipe = -1;
        interrupted = 0;
        int64_t lease_deadline = boot_ms() + LEASE_MS;
        bool controller_failed = false;
        for (;;) {
            struct pollfd wait = { .fd = pipes[0], .events = POLLIN };
            int ready = poll(&wait, 1, 200);
            if (interrupted || !app_alive() || boot_ms() >= lease_deadline) { controller_failed = true; break; }
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0) break;
            if (ready > 0 && (wait.revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL))) {
                char bytes[256];
                ssize_t count = read(pipes[0], bytes, sizeof(bytes));
                if (count == 0 || (count < 0 && errno != EAGAIN && errno != EINTR)) break;
                if (count > 0) lease_deadline = boot_ms() + LEASE_MS;
            }
        }
        close(pipes[0]);
        /* A stopped/deadlocked controller must not retain the TUN/lock or resume
         * mutation after recovery. getppid authenticates this exact child-parent
         * relationship, preventing a reused PID from becoming a kill target.
         * Its ip children also receive their configured PDEATHSIG. */
        if (controller_failed && getppid() == controller_pid) (void)kill(controller_pid, SIGKILL);
        bool removed = cleanup();
        if (current.lock_fd >= 0) close(current.lock_fd);
        _exit(removed ? 0 : 4);
    }
    close(pipes[0]);
    current.guardian_pid = child;
    current.guardian_pipe = pipes[1];
    return true;
}

static bool finish_guardian(void)
{
    if (current.guardian_pipe >= 0) { close(current.guardian_pipe); current.guardian_pipe = -1; }
    if (current.guardian_pid <= 0) return true;
    int64_t end = current.cleanup_deadline_ms;
    do {
        int status;
        pid_t found = waitpid(current.guardian_pid, &status, WNOHANG);
        if (found == current.guardian_pid) {
            current.guardian_pid = 0;
            return WIFEXITED(status) && WEXITSTATUS(status) == 0;
        }
        if (found < 0 && errno != EINTR) return false;
        (void)poll(NULL, 0, 20);
    } while (boot_ms() < end);
    /* Leave an independently cleaning guardian alive; never destroy the only
     * remaining rollback actor merely to make a timeout look successful. */
    return false;
}

static bool table_empty(void)
{
    char *routes = malloc(COMMAND_OUTPUT);
    if (!routes) return false;
    bool empty = true;
    for (int family = 4; family <= 6; family += 2) {
        int status = run_ip(true, routes, COMMAND_OUTPUT, family == 4 ? "-4" : "-6",
                            "route", "show", "table", current.table, NULL);
        if (status == 0 && *routes == '\0') continue;
        if (status > 0 && strstr(routes, "FIB table does not exist")) continue;
        empty = false;
    }
    free(routes);
    return empty;
}

static bool cleanup(void)
{
    if (current.cleaned) return current.cleanup_ok;
    current.cleanup_deadline_ms = boot_ms() + CLEANUP_TIMEOUT_MS;
    bool result = true;
    /* Remove selection first, so data immediately returns to ordinary routing. */
    for (size_t i = current.range_count; i > 0; --i) {
        struct uid_range *range = &current.ranges[i - 1];
        /* Guardian and controller can race during app death. Exact deletion is
         * idempotent; final kernel inspection decides whether rollback worked. */
        if (range->added6) (void)change_rule(range, 6, true);
        if (range->added4) (void)change_rule(range, 4, true);
        range->added4 = range->added6 = false;
    }
    if (*current.table && (current.active || current.activating)) {
        if (!verify_rules(4, false) || !verify_rules(6, false)) result = false;
    }
    for (size_t i = current.route_count; i > 0; --i) {
        struct route_entry *route = &current.routes[i - 1];
        if (route->added) (void)change_route(route, true);
        route->added = false;
    }
    if (*current.table && (current.active || current.activating) && !table_empty()) result = false;
    if (current.tun_fd >= 0) { if (close(current.tun_fd) != 0) result = false; current.tun_fd = -1; }
    if (!finish_guardian()) result = false;
    current.cleaned = true;
    current.cleanup_ok = result;
    current.active = false;
    /* Small diagnostic, never secrets. Nonblocking prevents a lost su stdout
     * consumer from blocking privileged cleanup after its network work is done. */
    int flags = fcntl(STDOUT_FILENO, F_GETFL);
    if (flags >= 0) (void)fcntl(STDOUT_FILENO, F_SETFL, flags | O_NONBLOCK);
    const char *status = result ? "RRBOX_ROOT_CLEANUP=OK\n" : "RRBOX_ROOT_CLEANUP=FAILED\n";
    ssize_t ignored = write(STDOUT_FILENO, status, strlen(status));
    (void)ignored;
    return result;
}

static bool owner_command(char *line)
{
    char *fields[7], *save = NULL;
    size_t count = 0;
    for (char *part = strtok_r(line, " ", &save); part; part = strtok_r(NULL, " ", &save)) {
        if (count == 7) return false;
        fields[count++] = part;
    }
    uint64_t protocol, source_port, destination_port;
    unsigned char address[16];
    if (count != 6 || !unsigned_number(fields[1], 17, &protocol) || (protocol != 6 && protocol != 17) ||
        !unsigned_number(fields[3], 65535, &source_port) || source_port == 0 ||
        !unsigned_number(fields[5], 65535, &destination_port) || destination_port == 0 ||
        (inet_pton(AF_INET, fields[2], address) != 1 && inet_pton(AF_INET6, fields[2], address) != 1) ||
        (inet_pton(AF_INET, fields[4], address) != 1 && inet_pton(AF_INET6, fields[4], address) != 1)) return false;
    int uid = rr_owner_lookup((int)protocol, fields[2], (unsigned)source_port, fields[4], (unsigned)destination_port);
    if (uid < 0) return send_text("UNKNOWN\n");
    char result[48];
    snprintf(result, sizeof(result), "UID %d\n", uid);
    return send_text(result);
}

int main(int argc, char **argv)
{
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts("RRBOX root engine protocol 1"); return 0;
    }
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, handle_signal); signal(SIGINT, handle_signal); signal(SIGHUP, handle_signal);
    if (getuid() != 0 || geteuid() != 0 || !read_arguments(argc, argv)) {
        fputs("RRBOX_ROOT_ERROR=identity_or_deadline\n", stderr); return 2;
    }
    const char *error = NULL;
    if (!connect_app()) { error = "ipc_identity_or_busy"; goto done; }
    if (!create_tun()) { error = "tun_setup_failed"; goto done; }
    if (!send_tun()) { error = "fd_transfer_failed"; goto done; }
    char line[MAX_LINE + 1];
    for (;;) {
        if (read_line(line, sizeof(line)) < 0) { error = "lease_or_ipc_closed"; break; }
        if (strcmp(line, "STOP") == 0) {
            bool removed = cleanup();
            (void)send_text(removed ? "STOPPED\n" : "ERROR cleanup_failed\n");
            if (!removed) error = "cleanup_failed";
            break;
        }
        if (strcmp(line, "HEARTBEAT") == 0) {
            if (!send_text("OK\n")) { error = "ipc_write_failed"; break; }
        } else if (strncmp(line, "CONFIG ", 7) == 0) {
            if (!configure(line)) { error = configuration_error; break; }
            if (!send_text("CONFIGURED\n")) { error = "ipc_write_failed"; break; }
        } else if (strcmp(line, "ACTIVATE") == 0) {
            if (!activate()) { error = "activation_failed"; break; }
            if (!send_text("ACTIVE\n")) { error = "ipc_write_failed"; break; }
        } else if (strncmp(line, "OWNER ", 6) == 0 && current.configured) {
            if (!owner_command(line)) { error = "invalid_owner_query"; break; }
        } else { error = "invalid_command"; break; }
    }
done:
    if (error) {
        char message[1280];
        snprintf(message, sizeof(message), "ERROR %s stage=%s%s%s\n", error, failure_stage,
                 *command_failure ? " " : "", command_failure);
        if (current.socket_fd >= 0) (void)send_text(message);
        int flags = fcntl(STDERR_FILENO, F_GETFL);
        if (flags >= 0) (void)fcntl(STDERR_FILENO, F_SETFL, flags | O_NONBLOCK);
        ssize_t ignored = write(STDERR_FILENO, message, strlen(message));
        (void)ignored;
    }
    bool removed = current.cleaned ? current.cleanup_ok : cleanup();
    if (current.socket_fd >= 0) close(current.socket_fd);
    if (current.lock_fd >= 0) close(current.lock_fd);
    return error == NULL && removed ? 0 : 3;
}
