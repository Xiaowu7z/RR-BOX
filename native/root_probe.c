/*
 * RRBOX phase-2 isolation probe, protocol version 1.
 * Run without arguments as uid/euid 0; --version has no side effects.
 * No shell, external peer, interface configuration, persistence, route,
 * firewall, namespace, DNS or sysctl operation is performed here.
 * TUNSETIFF with IFF_TUN_EXCL is the sole network-configuration operation:
 * the newly allocated interface stays DOWN/unaddressed and dies with its fd.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/inet_diag.h>
#include <linux/if_tun.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/sock_diag.h>
#include <net/if.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#ifndef IFF_TUN_EXCL
#define IFF_TUN_EXCL 0x8000
#endif
#ifndef IP_TRANSPARENT
#define IP_TRANSPARENT 19
#endif
#ifndef IPV6_TRANSPARENT
#define IPV6_TRANSPARENT 75
#endif

#define PROBE_TIMEOUT_SECONDS 12
#define NETLINK_TIMEOUT_MS 700
#define NETLINK_MAX_DATAGRAMS 64
#define NETLINK_BUFFER_SIZE 32768
#define PROC_MAX_BYTES (1024U * 1024U)
#define PROC_LINE_SIZE 1024

static const char *const test_keys[] = {
    "selinux_context", "tun_create", "tun_down", "tun_unaddressed", "tun_cleanup",
    "proc_tcp", "proc_tcp6", "proc_udp", "proc_udp6",
    "sock_diag_tcp", "sock_diag_udp", "ip_transparent_v4", "ip_transparent_v6"
};

static void result(const char *key, const char *status, int error, const char *reason)
{
    printf("%s=%s\n", key, status);
    if (error != 0) printf("%s_errno=%d\n", key, error);
    if (reason != NULL) printf("%s_reason=%s\n", key, reason);
}

static const char *error_status(int error)
{
    switch (error) {
    case ENOENT: case ENODEV: case ENXIO:
        return "UNAVAILABLE";
    case ENOSYS:
    case EAFNOSUPPORT: case EPROTONOSUPPORT: case ENOPROTOOPT: case EOPNOTSUPP:
        return "UNSUPPORTED";
    default:
        return "FAIL";
    }
}

static void alarm_handler(int signal_number)
{
    (void)signal_number;
    /* No writes: a full stdout pipe must not block the watchdog's process exit.
     * Death closes the nonpersistent TUN. Missing COMPLETE makes this incomplete. */
    _exit(124);
}

static int64_t monotonic_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int open_netlink(int protocol)
{
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, protocol);
    if (fd < 0) return -1;
    struct sockaddr_nl address;
    memset(&address, 0, sizeof(address));
    address.nl_family = AF_NETLINK;
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        int error = errno;
        close(fd);
        errno = error;
        return -1;
    }
    return fd;
}

static int send_netlink(int fd, const void *request, size_t size)
{
    struct sockaddr_nl kernel;
    memset(&kernel, 0, sizeof(kernel));
    kernel.nl_family = AF_NETLINK;
    ssize_t sent = sendto(fd, request, size, MSG_DONTWAIT,
                         (struct sockaddr *)&kernel, sizeof(kernel));
    if (sent == (ssize_t)size) return 0;
    if (sent >= 0) errno = EIO;
    return -1;
}

static ssize_t receive_netlink(int fd, void *buffer, size_t size, int64_t deadline)
{
    for (;;) {
        int64_t now = monotonic_ms();
        if (now < 0) return -1;
        if (now >= deadline) { errno = ETIMEDOUT; return -1; }
        struct pollfd wait_fd = { .fd = fd, .events = POLLIN, .revents = 0 };
        int ready = poll(&wait_fd, 1, (int)(deadline - now));
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return -1;
        if (ready == 0) { errno = ETIMEDOUT; return -1; }
        if ((wait_fd.revents & POLLIN) == 0) { errno = EIO; return -1; }
        struct sockaddr_nl sender;
        struct iovec iov = { .iov_base = buffer, .iov_len = size };
        struct msghdr message;
        memset(&sender, 0, sizeof(sender));
        memset(&message, 0, sizeof(message));
        message.msg_name = &sender;
        message.msg_namelen = sizeof(sender);
        message.msg_iov = &iov;
        message.msg_iovlen = 1;
        ssize_t count = recvmsg(fd, &message, MSG_DONTWAIT);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count < 0) return -1;
        if ((message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0) {
            errno = EMSGSIZE; return -1;
        }
        if (count == 0 || message.msg_namelen != sizeof(sender) ||
            sender.nl_family != AF_NETLINK || sender.nl_pid != 0) {
            errno = EPROTO; return -1;
        }
        return count;
    }
}

/* Return 0 for ordinary messages, 1 for successful DONE, -1 for kernel errors. */
static int netlink_control(const struct nlmsghdr *header)
{
    if ((header->nlmsg_flags & NLM_F_DUMP_INTR) != 0) { errno = EINTR; return -1; }
    if (header->nlmsg_type == NLMSG_ERROR) {
        if (header->nlmsg_len < NLMSG_LENGTH(sizeof(struct nlmsgerr))) {
            errno = EPROTO; return -1;
        }
        const struct nlmsgerr *error = NLMSG_DATA(header);
        if (error->error != 0) {
            errno = error->error < 0 && error->error != INT_MIN ? -error->error : EPROTO;
            return -1;
        }
        return 0;
    }
    if (header->nlmsg_type == NLMSG_DONE) {
        size_t payload_size = header->nlmsg_len - NLMSG_HDRLEN;
        if (payload_size != 0 && payload_size < sizeof(int)) { errno = EPROTO; return -1; }
        if (payload_size >= sizeof(int)) {
            int error;
            memcpy(&error, NLMSG_DATA(header), sizeof(error));
            if (error != 0) { errno = error < 0 && error != INT_MIN ? -error : EPROTO; return -1; }
        }
        return 1;
    }
    if (header->nlmsg_type == NLMSG_OVERRUN) { errno = ENOBUFS; return -1; }
    return 0;
}

static int verify_no_addresses(unsigned int interface_index)
{
    int fd = open_netlink(NETLINK_ROUTE);
    if (fd < 0) return -1;
    struct {
        struct nlmsghdr header;
        struct ifaddrmsg address;
    } request;
    memset(&request, 0, sizeof(request));
    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(request.address));
    request.header.nlmsg_type = RTM_GETADDR;
    request.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    request.header.nlmsg_seq = 1;
    request.address.ifa_family = AF_UNSPEC;
    int answer = -1;
    int error = 0;
    if (send_netlink(fd, &request, request.header.nlmsg_len) != 0) goto done;
    int64_t start = monotonic_ms();
    if (start < 0) goto done;
    for (int batch = 0; batch < NETLINK_MAX_DATAGRAMS; ++batch) {
        union { struct nlmsghdr alignment; char bytes[NETLINK_BUFFER_SIZE]; } buffer;
        ssize_t size = receive_netlink(fd, buffer.bytes, sizeof(buffer.bytes), start + NETLINK_TIMEOUT_MS);
        if (size < 0) goto done;
        int remaining = (int)size;
        for (struct nlmsghdr *header = (struct nlmsghdr *)buffer.bytes;
             NLMSG_OK(header, remaining); header = NLMSG_NEXT(header, remaining)) {
            if (header->nlmsg_seq != request.header.nlmsg_seq) { errno = EPROTO; goto done; }
            int control = netlink_control(header);
            if (control < 0) goto done;
            if (control == 1) { answer = 0; goto done; }
            if (header->nlmsg_type == RTM_NEWADDR) {
                if (header->nlmsg_len < NLMSG_LENGTH(sizeof(struct ifaddrmsg))) { errno = EPROTO; goto done; }
                const struct ifaddrmsg *address = NLMSG_DATA(header);
                if (address->ifa_index == interface_index &&
                    (address->ifa_family == AF_INET || address->ifa_family == AF_INET6)) {
                    errno = EADDRINUSE; goto done;
                }
            } else if (header->nlmsg_type != NLMSG_ERROR && header->nlmsg_type != NLMSG_NOOP) {
                errno = EPROTO; goto done;
            }
        }
        if (remaining != 0) { errno = EPROTO; goto done; }
    }
    errno = EOVERFLOW;
done:
    error = errno;
    close(fd);
    errno = error;
    return answer;
}

static int verify_removed(int control_fd, unsigned int interface_index, const char *name)
{
    int64_t start = monotonic_ms();
    if (start < 0) return -1;
    for (int attempt = 0; attempt < 25; ++attempt) {
        struct ifreq by_index;
        memset(&by_index, 0, sizeof(by_index));
        by_index.ifr_ifindex = (int)interface_index;
        int found = ioctl(control_fd, SIOCGIFNAME, &by_index);
        if (found < 0) {
            if (errno != ENODEV && errno != ENXIO) return -1;
            struct ifreq by_name;
            memset(&by_name, 0, sizeof(by_name));
            snprintf(by_name.ifr_name, sizeof(by_name.ifr_name), "%s", name);
            if (ioctl(control_fd, SIOCGIFINDEX, &by_name) < 0) {
                if (errno == ENODEV || errno == ENXIO) return 0;
                return -1;
            }
            /* Reuse of the unique name is not sufficient evidence of cleanup. */
            errno = EEXIST;
            return -1;
        }
        int64_t now = monotonic_ms();
        if (now < 0) return -1;
        if (now - start >= 500) break;
        (void)poll(NULL, 0, 20);
    }
    errno = ETIMEDOUT;
    return -1;
}

static bool probe_tun(void)
{
    int control_fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    int tun_fd = -1;
    int error = 0;
    const char *reason = "control_socket";
    if (control_fd < 0) { error = errno; goto unavailable; }
    tun_fd = open("/dev/net/tun", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (tun_fd < 0) {
        int primary_error = errno;
        printf("tun_primary_open_errno=%d\n", primary_error);
        tun_fd = open("/dev/tun", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    }
    if (tun_fd < 0) { error = errno; reason = "open_device"; goto unavailable; }
    struct ifreq interface;
    memset(&interface, 0, sizeof(interface));
    int64_t stamp = monotonic_ms();
    if (stamp < 0) { error = errno; reason = "clock"; goto unavailable; }
    bool created = false;
    for (unsigned int attempt = 0; attempt < 4; ++attempt) {
        memset(&interface, 0, sizeof(interface));
        snprintf(interface.ifr_name, sizeof(interface.ifr_name), "rrp%05x%06x",
                 (unsigned int)getpid() & 0xfffffU, ((unsigned int)stamp + attempt) & 0xffffffU);
        interface.ifr_flags = (short)(IFF_TUN | IFF_NO_PI | IFF_TUN_EXCL);
        if (ioctl(tun_fd, TUNSETIFF, &interface) == 0) { created = true; break; }
        error = errno;
        if (error != EBUSY && error != EEXIST) break;
    }
    if (!created) { reason = "exclusive_create"; goto unavailable; }
    result("tun_create", "PASS", 0, "exclusive_nonpersistent");
    char name[IFNAMSIZ];
    snprintf(name, sizeof(name), "%s", interface.ifr_name);
    unsigned int index = 0;
    bool down = false;
    bool unaddressed = false;
    if (ioctl(control_fd, SIOCGIFINDEX, &interface) < 0) {
        result("tun_down", "FAIL", errno, "interface_index");
        result("tun_unaddressed", "SKIP", 0, "prerequisite_failed");
    } else {
        index = (unsigned int)interface.ifr_ifindex;
        memset(&interface, 0, sizeof(interface));
        snprintf(interface.ifr_name, sizeof(interface.ifr_name), "%s", name);
        if (ioctl(control_fd, SIOCGIFFLAGS, &interface) < 0) {
            result("tun_down", "FAIL", errno, "read_flags");
        } else if ((interface.ifr_flags & IFF_UP) != 0) {
            result("tun_down", "FAIL", 0, "unexpected_up");
        } else {
            down = true;
            result("tun_down", "PASS", 0, NULL);
        }
        if (down) {
            if (verify_no_addresses(index) == 0) {
                unaddressed = true;
                result("tun_unaddressed", "PASS", 0, "ipv4_ipv6");
            } else {
                result("tun_unaddressed", "FAIL", errno, "address_dump_verification");
            }
        } else {
            result("tun_unaddressed", "SKIP", 0, "prerequisite_failed");
        }
    }
    /* Never retry close after EINTR: Linux has already released the descriptor. */
    int closed = close(tun_fd);
    int close_error = errno;
    bool removed = false;
    if (index == 0) {
        result("tun_cleanup", "FAIL", 0, "unknown_interface_index");
    } else if (verify_removed(control_fd, index, name) != 0) {
        result("tun_cleanup", "FAIL", errno, "removal_unverified");
    } else if (closed != 0) {
        result("tun_cleanup", "FAIL", close_error, "close_error_but_removal_verified");
    } else {
        removed = true;
        result("tun_cleanup", "PASS", 0, "index_and_name_absent");
    }
    close(control_fd);
    return down && unaddressed && removed;
unavailable:
    if (tun_fd >= 0) close(tun_fd);
    if (control_fd >= 0) close(control_fd);
    result("tun_create", error_status(error), error, reason);
    result("tun_down", "SKIP", 0, "prerequisite_failed");
    result("tun_unaddressed", "SKIP", 0, "prerequisite_failed");
    result("tun_cleanup", "SKIP", 0, "no_interface_created");
    return false;
}

static void probe_context(void)
{
    int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { int error = errno; result("selinux_context", error_status(error), error, "open_context"); return; }
    char context[256];
    ssize_t size = read(fd, context, sizeof(context) - 1);
    int error = errno;
    close(fd);
    if (size <= 0 || size == (ssize_t)sizeof(context) - 1) {
        result("selinux_context", "FAIL", size < 0 ? error : EOVERFLOW, "read_context"); return;
    }
    while (size > 0 && (context[size - 1] == '\n' || context[size - 1] == '\0')) --size;
    context[size] = '\0';
    for (ssize_t i = 0; i < size; ++i) {
        unsigned char byte = (unsigned char)context[i];
        if (!isalnum(byte) && byte != ':' && byte != '_' && byte != '-' && byte != '.' && byte != ',') context[i] = '_';
    }
    result("selinux_context", size > 0 ? "PASS" : "FAIL", 0, size > 0 ? NULL : "empty_context");
    if (size > 0) printf("selinux_context_value=%s\n", context);
}

static void report_namespace(const char *key, const char *path)
{
    char target[96];
    ssize_t size = readlink(path, target, sizeof(target) - 1);
    if (size < 0 || size >= (ssize_t)sizeof(target) - 1) {
        printf("%s=UNKNOWN\n", key);
        if (size < 0) printf("%s_errno=%d\n", key, errno);
        return;
    }
    target[size] = '\0';
    /* Only the kernel namespace-inode form may enter the report. */
    bool valid = size >= 7 && strncmp(target, "net:[", 5) == 0 && target[size - 1] == ']';
    for (ssize_t i = 5; valid && i < size - 1; ++i)
        if (!isdigit((unsigned char)target[i])) valid = false;
    printf("%s=%s\n", key, valid ? target : "UNKNOWN");
}

static int count_proc_line(char *line, bool *header_seen, unsigned int *count)
{
    char *cursor = line;
    while (isspace((unsigned char)*cursor)) ++cursor;
    if (*cursor == '\0') return 0;
    if (!*header_seen) {
        if (strncmp(cursor, "sl", 2) != 0 || !isspace((unsigned char)cursor[2])) { errno = EPROTO; return -1; }
        *header_seen = true;
        return 0;
    }
    if (!isdigit((unsigned char)*cursor)) { errno = EPROTO; return -1; }
    char *end;
    errno = 0;
    (void)strtoul(cursor, &end, 10);
    if (errno != 0 || *end != ':') { errno = EPROTO; return -1; }
    /* Validate the fixed columns without ever emitting addresses or destinations. */
    unsigned int row, state, uid, timeout;
    unsigned long long inode;
    char local[65], remote[65], queues[65], timer[65], retransmits[65];
    int fields = sscanf(cursor, "%u: %64s %64s %x %64s %64s %64s %u %u %llu",
                        &row, local, remote, &state, queues, timer, retransmits, &uid, &timeout, &inode);
    if (fields != 10) { errno = EPROTO; return -1; }
    ++*count;
    return 0;
}

static void probe_proc(const char *key, const char *path)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { int error = errno; result(key, error_status(error), error, "open_proc"); return; }
    char bytes[4096], line[PROC_LINE_SIZE];
    size_t total = 0, line_size = 0;
    unsigned int count = 0;
    bool header_seen = false;
    int error = 0;
    for (;;) {
        if (total >= PROC_MAX_BYTES) { error = EOVERFLOW; break; }
        size_t capacity = PROC_MAX_BYTES - total;
        if (capacity > sizeof(bytes)) capacity = sizeof(bytes);
        ssize_t size = read(fd, bytes, capacity);
        if (size < 0) { error = errno; break; }
        if (size == 0) {
            if (line_size != 0) {
                line[line_size] = '\0';
                if (count_proc_line(line, &header_seen, &count) != 0) error = errno;
            }
            if (!header_seen && error == 0) error = EPROTO;
            break;
        }
        total += (size_t)size;
        for (ssize_t i = 0; i < size; ++i) {
            if (bytes[i] == '\n') {
                line[line_size] = '\0';
                if (count_proc_line(line, &header_seen, &count) != 0) { error = errno; break; }
                line_size = 0;
            } else if (line_size < sizeof(line) - 1) {
                line[line_size++] = bytes[i];
            } else {
                error = EOVERFLOW; break;
            }
        }
        if (error != 0) break;
    }
    close(fd);
    result(key, error == 0 ? "PASS" : "FAIL", error, error == 0 ? "namespace_table_count" : "read_or_parse_proc");
    if (error == 0) printf("%s_count=%u\n", key, count);
}

static int lookup_own_socket(int socket_fd, int protocol, uint16_t local_port)
{
    struct stat owned;
    if (fstat(socket_fd, &owned) != 0) return -1;
    if (owned.st_ino > UINT32_MAX) { errno = EOVERFLOW; return -1; }
    int fd = open_netlink(NETLINK_SOCK_DIAG);
    if (fd < 0) return -1;
    struct {
        struct nlmsghdr header;
        struct inet_diag_req_v2 diagnostic;
    } request;
    memset(&request, 0, sizeof(request));
    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(request.diagnostic));
    request.header.nlmsg_type = SOCK_DIAG_BY_FAMILY;
    request.header.nlmsg_flags = NLM_F_REQUEST;
    request.header.nlmsg_seq = 2;
    request.diagnostic.sdiag_family = AF_INET;
    request.diagnostic.sdiag_protocol = (uint8_t)protocol;
    request.diagnostic.idiag_states = UINT32_MAX;
    request.diagnostic.id.idiag_sport = local_port;
    request.diagnostic.id.idiag_src[0] = htonl(INADDR_LOOPBACK);
    request.diagnostic.id.idiag_cookie[0] = INET_DIAG_NOCOOKIE;
    request.diagnostic.id.idiag_cookie[1] = INET_DIAG_NOCOOKIE;
    int answer = -1;
    int error = 0;
    if (send_netlink(fd, &request, request.header.nlmsg_len) != 0) goto done;
    int64_t start = monotonic_ms();
    if (start < 0) goto done;
    for (int batch = 0; batch < NETLINK_MAX_DATAGRAMS; ++batch) {
        union { struct nlmsghdr alignment; char bytes[NETLINK_BUFFER_SIZE]; } buffer;
        ssize_t size = receive_netlink(fd, buffer.bytes, sizeof(buffer.bytes), start + NETLINK_TIMEOUT_MS);
        if (size < 0) goto done;
        int remaining = (int)size;
        for (struct nlmsghdr *header = (struct nlmsghdr *)buffer.bytes;
             NLMSG_OK(header, remaining); header = NLMSG_NEXT(header, remaining)) {
            if (header->nlmsg_seq != request.header.nlmsg_seq) { errno = EPROTO; goto done; }
            int control = netlink_control(header);
            if (control < 0) goto done;
            if (control == 1) { errno = ENOENT; goto done; }
            if (header->nlmsg_type == SOCK_DIAG_BY_FAMILY) {
                if (header->nlmsg_len < NLMSG_LENGTH(sizeof(struct inet_diag_msg))) { errno = EPROTO; goto done; }
                const struct inet_diag_msg *diagnostic = NLMSG_DATA(header);
                const uint32_t zero_address[4] = { 0, 0, 0, 0 };
                const uint32_t source_address[4] = { htonl(INADDR_LOOPBACK), 0, 0, 0 };
                if (diagnostic->idiag_family != AF_INET || diagnostic->id.idiag_sport != local_port ||
                    diagnostic->id.idiag_dport != 0 || diagnostic->id.idiag_if != 0 ||
                    memcmp(diagnostic->id.idiag_src, source_address, sizeof(source_address)) != 0 ||
                    memcmp(diagnostic->id.idiag_dst, zero_address, sizeof(zero_address)) != 0 ||
                    diagnostic->idiag_inode != (uint32_t)owned.st_ino || diagnostic->idiag_uid != 0) {
                    errno = EPROTO; goto done;
                }
                answer = 0;
                goto done;
            }
            if (header->nlmsg_type != NLMSG_ERROR && header->nlmsg_type != NLMSG_NOOP) {
                errno = EPROTO; goto done;
            }
        }
        if (remaining != 0) { errno = EPROTO; goto done; }
    }
    errno = EOVERFLOW;
done:
    error = errno;
    close(fd);
    errno = error;
    return answer;
}

static void probe_socket_diag(const char *key, int type, int protocol)
{
    int fd = socket(AF_INET, type | SOCK_CLOEXEC | SOCK_NONBLOCK, protocol);
    if (fd < 0) { int error = errno; result(key, error_status(error), error, "local_socket"); return; }
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int error = 0;
    const char *reason = "loopback_bind";
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0) { error = errno; goto done; }
    if (type == SOCK_STREAM && listen(fd, 1) != 0) { error = errno; reason = "local_listen"; goto done; }
    socklen_t size = sizeof(address);
    if (getsockname(fd, (struct sockaddr *)&address, &size) != 0) { error = errno; reason = "local_socket_name"; goto done; }
    if (size != sizeof(address) || address.sin_family != AF_INET || address.sin_port == 0 ||
        address.sin_addr.s_addr != htonl(INADDR_LOOPBACK)) { error = EPROTO; reason = "local_tuple"; goto done; }
    if (lookup_own_socket(fd, protocol, address.sin_port) != 0) { error = errno; reason = "self_inode_uid0_lookup"; goto done; }
    reason = "self_inode_uid0_only";
done:
    close(fd);
    result(key, error == 0 ? "PASS" : error_status(error), error, reason);
}

static void probe_transparent(const char *key, int family, int level, int option)
{
    int fd = socket(family, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { int error = errno; result(key, error_status(error), error, "unbound_socket"); return; }
    int enabled = 1;
    int error = 0;
    const char *reason = "setsockopt";
    if (setsockopt(fd, level, option, &enabled, sizeof(enabled)) != 0) { error = errno; goto done; }
    enabled = 0;
    socklen_t size = sizeof(enabled);
    reason = "getsockopt";
    if (getsockopt(fd, level, option, &enabled, &size) != 0) { error = errno; goto done; }
    if (size != sizeof(enabled) || enabled != 1) { error = EPROTO; goto done; }
    reason = "unbound_socket_set_and_read_back";
done:
    close(fd);
    result(key, error == 0 ? "PASS" : error_status(error), error, reason);
}

int main(int argc, char **argv)
{
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts("RRBOX_ROOT_PROBE_VERSION=1");
        return 0;
    }
    if (argc != 1) { fputs("usage: rrbox-root-probe [--version]\n", stderr); return 64; }
    setvbuf(stdout, NULL, _IONBF, 0);
    struct sigaction alarm_action;
    memset(&alarm_action, 0, sizeof(alarm_action));
    alarm_action.sa_handler = alarm_handler;
    sigemptyset(&alarm_action.sa_mask);
    if (sigaction(SIGALRM, &alarm_action, NULL) != 0) {
        result("timeout", "FAIL", errno, "install_alarm"); return 70;
    }
    sigset_t alarm_set;
    sigemptyset(&alarm_set);
    sigaddset(&alarm_set, SIGALRM);
    if (sigprocmask(SIG_UNBLOCK, &alarm_set, NULL) != 0) {
        result("timeout", "FAIL", errno, "unblock_alarm"); return 70;
    }
    alarm(PROBE_TIMEOUT_SECONDS);
    puts("RRBOX_ROOT_PROBE_VERSION=1");
    puts("probe_version=1");
    printf("uid=%lu\neuid=%lu\n", (unsigned long)getuid(), (unsigned long)geteuid());
    puts("scope=isolated_capabilities_only");
    puts("network_verified=NOT_TESTED");
    puts("app_uid_mapping=NOT_TESTED");
    puts("traffic_takeover=NOT_TESTED");
    puts("proc_scope=current_network_namespace");
    if (getuid() != 0 || geteuid() != 0) {
        result("root", "FAIL", EPERM, "uid_and_euid_0_required");
        for (size_t i = 0; i < sizeof(test_keys) / sizeof(test_keys[0]); ++i)
            result(test_keys[i], "SKIP", 0, "root_required");
        puts("RRBOX_ROOT_PROBE_COMPLETE");
        alarm(0);
        return 77;
    }
    result("root", "PASS", 0, NULL);
    report_namespace("root_netns", "/proc/self/ns/net");
    report_namespace("init_netns", "/proc/1/ns/net");
    probe_context();
    bool isolated_tun_verified = probe_tun();
    probe_proc("proc_tcp", "/proc/net/tcp");
    probe_proc("proc_tcp6", "/proc/net/tcp6");
    probe_proc("proc_udp", "/proc/net/udp");
    probe_proc("proc_udp6", "/proc/net/udp6");
    probe_socket_diag("sock_diag_tcp", SOCK_STREAM, IPPROTO_TCP);
    probe_socket_diag("sock_diag_udp", SOCK_DGRAM, IPPROTO_UDP);
    probe_transparent("ip_transparent_v4", AF_INET, SOL_IP, IP_TRANSPARENT);
    probe_transparent("ip_transparent_v6", AF_INET6, IPPROTO_IPV6, IPV6_TRANSPARENT);
    result("isolation_verified", isolated_tun_verified ? "PASS" : "FAIL", 0, NULL);
    puts("RRBOX_ROOT_PROBE_COMPLETE");
    alarm(0);
    return isolated_tun_verified ? 0 : 1;
}
