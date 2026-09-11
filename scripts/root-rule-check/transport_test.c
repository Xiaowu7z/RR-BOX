/* Test-only syscall boundary fixture. Production has no injected networking path. */
#define main rrbox_production_main
#include "root_engine.c"
#undef main

#include <assert.h>

#define TEST_FD 23000
#define TEST_PORT 12345
static const char *scenario;
static struct nlmsghdr sent_header;
static int sockets, closes, receives, passed;

int __real_socket(int, int, int);
int __real_bind(int, const struct sockaddr *, socklen_t);
int __real_getsockname(int, struct sockaddr *, socklen_t *);
ssize_t __real_sendto(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
ssize_t __real_recvmsg(int, struct msghdr *, int);
int __real_poll(struct pollfd *, nfds_t, int);
int __real_close(int);

int __wrap_socket(int family, int type, int protocol)
{
    if (family != AF_NETLINK) return __real_socket(family, type, protocol);
    assert(type == (SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK) && protocol == NETLINK_ROUTE);
    if (!strcmp(scenario, "socket_denied")) { errno = EPERM; return -1; }
    ++sockets;
    return TEST_FD;
}
int __wrap_bind(int fd, const struct sockaddr *address, socklen_t length)
{
    if (fd != TEST_FD) return __real_bind(fd, address, length);
    const struct sockaddr_nl *local = (const struct sockaddr_nl *)address;
    assert(length == sizeof(*local) && local->nl_family == AF_NETLINK && !local->nl_pid && !local->nl_groups);
    if (!strcmp(scenario, "bind_denied")) { errno = EACCES; return -1; }
    return 0;
}
int __wrap_getsockname(int fd, struct sockaddr *address, socklen_t *length)
{
    if (fd != TEST_FD) return __real_getsockname(fd, address, length);
    assert(*length == sizeof(struct sockaddr_nl));
    *(struct sockaddr_nl *)address = (struct sockaddr_nl){ .nl_family = AF_NETLINK, .nl_pid = TEST_PORT };
    return 0;
}
ssize_t __wrap_sendto(int fd, const void *buffer, size_t length, int flags,
                      const struct sockaddr *address, socklen_t address_length)
{
    if (fd != TEST_FD) return __real_sendto(fd, buffer, length, flags, address, address_length);
    const struct sockaddr_nl *kernel = (const struct sockaddr_nl *)address;
    assert(address_length == sizeof(*kernel) && kernel->nl_family == AF_NETLINK && !kernel->nl_pid && !kernel->nl_groups);
    assert(flags == 0 && length >= sizeof(sent_header));
    memcpy(&sent_header, buffer, sizeof(sent_header));
    assert(sent_header.nlmsg_pid == TEST_PORT && sent_header.nlmsg_seq && sent_header.nlmsg_len == length);
    if (!strcmp(scenario, "send_denied")) { errno = EPERM; return -1; }
    return (ssize_t)length;
}
int __wrap_poll(struct pollfd *wait, nfds_t count, int timeout)
{
    if (count != 1 || wait->fd != TEST_FD) return __real_poll(wait, count, timeout);
    assert(timeout > 0 && timeout <= 50);
    if (!strcmp(scenario, "timeout")) return __real_poll(NULL, 0, timeout);
    wait->revents = POLLIN;
    return 1;
}
ssize_t __wrap_recvmsg(int fd, struct msghdr *message, int flags)
{
    if (fd != TEST_FD) return __real_recvmsg(fd, message, flags);
    assert(flags == MSG_DONTWAIT && message->msg_namelen == sizeof(struct sockaddr_nl) && message->msg_iovlen == 1);
    ++receives;
    *(struct sockaddr_nl *)message->msg_name = (struct sockaddr_nl){ .nl_family = AF_NETLINK };
    message->msg_flags = !strcmp(scenario, "truncated") ? MSG_TRUNC : 0;
    if (!strcmp(scenario, "foreign_sender")) ((struct sockaddr_nl *)message->msg_name)->nl_pid = 100;
    if (!strcmp(scenario, "short_sender")) message->msg_namelen -= 1;
    if (!strcmp(scenario, "receive_denied")) { errno = ENOBUFS; return -1; }
    struct { struct nlmsghdr header; struct nlmsgerr error; } reply = { 0 };
    reply.header.nlmsg_len = NLMSG_LENGTH(sizeof(reply.error));
    reply.header.nlmsg_type = NLMSG_ERROR;
    reply.header.nlmsg_seq = sent_header.nlmsg_seq;
    reply.header.nlmsg_pid = TEST_PORT;
    reply.error.msg = sent_header;
    if (!strcmp(scenario, "kernel_invalid")) reply.error.error = -EINVAL;
    if (!strcmp(scenario, "kernel_unsupported")) reply.error.error = -EOPNOTSUPP;
    if (!strcmp(scenario, "kernel_denied")) reply.error.error = -EPERM;
    if (!strcmp(scenario, "wrong_sequence")) ++reply.header.nlmsg_seq;
    if (!strcmp(scenario, "wrong_port")) ++reply.header.nlmsg_pid;
    if (!strcmp(scenario, "wrong_ack_sequence")) ++reply.error.msg.nlmsg_seq;
    if (!strcmp(scenario, "wrong_ack_operation")) reply.error.msg.nlmsg_type = RTM_GETRULE;
    if (!strcmp(scenario, "positive_error")) reply.error.error = EINVAL;
    if (!strcmp(scenario, "out_of_range_error")) reply.error.error = INT_MIN;
    if (!strcmp(scenario, "short_ack")) reply.header.nlmsg_len = NLMSG_LENGTH(sizeof(int));
    if (!strcmp(scenario, "unknown_message")) reply.header.nlmsg_type = NLMSG_NOOP;
    if (!strcmp(scenario, "dump_done") || !strcmp(scenario, "dump_interrupted") ||
        !strcmp(scenario, "dump_error") || !strcmp(scenario, "short_done")) {
        reply.header.nlmsg_type = NLMSG_DONE;
        reply.header.nlmsg_len = NLMSG_LENGTH(sizeof(int));
        if (!strcmp(scenario, "dump_interrupted")) reply.header.nlmsg_flags = NLM_F_DUMP_INTR;
        if (!strcmp(scenario, "dump_error")) reply.error.error = -EINTR;
        if (!strcmp(scenario, "short_done")) --reply.header.nlmsg_len;
    }
    assert(reply.header.nlmsg_len <= message->msg_iov->iov_len);
    memcpy(message->msg_iov->iov_base, &reply, reply.header.nlmsg_len);
    if (!strcmp(scenario, "ack_trailing_byte")) {
        ((unsigned char *)message->msg_iov->iov_base)[reply.header.nlmsg_len] = 1;
        return reply.header.nlmsg_len + 1;
    }
    if (!strcmp(scenario, "ack_trailing_header")) {
        struct nlmsghdr trailer = { .nlmsg_len = NLMSG_LENGTH(0), .nlmsg_type = NLMSG_NOOP,
            .nlmsg_seq = sent_header.nlmsg_seq, .nlmsg_pid = TEST_PORT };
        memcpy((char *)message->msg_iov->iov_base + reply.header.nlmsg_len, &trailer, sizeof(trailer));
        return reply.header.nlmsg_len + sizeof(trailer);
    }
    return reply.header.nlmsg_len;
}
int __wrap_close(int fd)
{
    if (fd != TEST_FD) return __real_close(fd);
    ++closes;
    return 0;
}

static void initialize(void)
{
    current = (struct engine){ .socket_fd = -1, .tun_fd = -1, .lock_fd = -1,
        .guardian_pipe = -1, .app_uid = getuid(), .deadline_ms = boot_ms() + 60000,
        .lease_ms = boot_ms() + 60000, .cleanup_deadline_ms = boot_ms() + 10000 };
    FILE *file = fopen("/proc/self/stat", "r");
    char buffer[4096];
    assert(file && fgets(buffer, sizeof(buffer), file));
    fclose(file);
    current.app_pid = (pid_t)strtol(buffer, NULL, 10);
    char *save = NULL, *end = strrchr(buffer, ')');
    assert(end);
    char *token = strtok_r(end + 2, " \n", &save);
    for (int field = 3; token; ++field, token = strtok_r(NULL, " \n", &save))
        if (field == 22) assert(unsigned_number(token, UINT64_MAX, &current.app_start));
    assert(app_alive());
    snprintf(current.table, sizeof(current.table), "42000");
    sockets = closes = receives = 0;
}
static void check(const char *name, int expected, bool cleanup, bool dump)
{
    initialize();
    scenario = name;
    struct uid_range range = { .first = 0, .last = 10000, .family = 4, .dns_protocol = 17 };
    snprintf(range.destination, sizeof(range.destination), "1.1.1.1/32");
    struct rr_rule_request request;
    assert(rr_rule_encode(&request, &range, 4, 42000, cleanup));
    struct rr_rule_snapshot snapshot = { .family = 4, .table = 42000, .present = true };
    if (dump) {
        request.header.nlmsg_type = RTM_GETRULE;
        request.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    }
    snprintf(command_failure, sizeof(command_failure), "first activation error");
    if (cleanup) current.app_pid = -1; /* cleanup must work after app identity disappears */
    int64_t before = boot_ms();
    int result = rr_rule_exchange(&request, cleanup, dump ? &snapshot : NULL);
    assert(result == expected);
    assert(sockets == closes && sockets <= 1);
    if (!strcmp(name, "timeout")) assert(boot_ms() - before >= COMMAND_TIMEOUT_MS && boot_ms() - before < COMMAND_TIMEOUT_MS + 500);
    if (cleanup) assert(!strcmp(command_failure, "first activation error"));
    else if (expected) {
        char prefix[48];
        snprintf(prefix, sizeof(prefix), "netlink_errno=%d ", expected);
        assert(strstr(command_failure, prefix));
    } else assert(!*command_failure);
    ++passed;
}
int main(void)
{
    check("ack_success", 0, false, false);
    check("ack_success", 0, true, false);
    check("socket_denied", EPERM, false, false);
    check("bind_denied", EACCES, false, false);
    check("send_denied", EPERM, false, false);
    check("receive_denied", ENOBUFS, false, false);
    check("kernel_invalid", EINVAL, false, false);
    check("kernel_unsupported", EOPNOTSUPP, false, false);
    check("kernel_denied", EPERM, false, false);
    check("kernel_invalid", EINVAL, true, false);
    const char *malformed[] = { "wrong_sequence", "wrong_port", "wrong_ack_sequence", "wrong_ack_operation",
        "positive_error", "out_of_range_error", "short_ack", "unknown_message", "truncated", "foreign_sender", "short_sender", "ack_trailing_byte", "ack_trailing_header" };
    for (size_t i = 0; i < sizeof(malformed) / sizeof(*malformed); ++i) check(malformed[i], EPROTO, false, false);
    check("dump_done", 0, false, true);
    check("dump_interrupted", EPROTO, false, true);
    check("dump_error", EINTR, false, true);
    check("short_done", EPROTO, false, true);
    check("ack_success", EPROTO, false, true);
    check("dump_done", EPROTO, false, false);
    check("timeout", ETIMEDOUT, false, false);
    printf("{\"checks\":%d,\"passed\":true,\"network_mutations\":false}\n", passed);
    return 0;
}
