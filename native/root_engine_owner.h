/* Bounded, conservative original-tuple ownership lookup for the root helper. */
#ifndef RRBOX_ROOT_ENGINE_OWNER_H
#define RRBOX_ROOT_ENGINE_OWNER_H

#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif

#include <arpa/inet.h>
#include <errno.h>
#include <limits.h>
#include <linux/inet_diag.h>
#include <linux/netlink.h>
#include <linux/sock_diag.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#define RR_OWNER_TIMEOUT_MS 500
#define RR_OWNER_MAX_DATAGRAMS 64U
#define RR_OWNER_BUFFER_SIZE 32768U

struct rr_owner_address {
    int family;
    unsigned char bytes[16];
};

static int64_t rr_owner_now_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

/* Canonicalize mapped IPv6 so either textual representation can find both
 * AF_INET sockets and AF_INET6 dual-stack sockets. No name resolution occurs. */
static int rr_owner_parse_address(const char *text, struct rr_owner_address *out)
{
    static const unsigned char mapped_prefix[12] = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff
    };
    if (text == NULL) return -1;
    memset(out, 0, sizeof(*out));
    if (inet_pton(AF_INET, text, out->bytes) == 1) {
        out->family = AF_INET;
        return 0;
    }
    if (inet_pton(AF_INET6, text, out->bytes) != 1) return -1;
    out->family = AF_INET6;
    if (memcmp(out->bytes, mapped_prefix, sizeof(mapped_prefix)) == 0) {
        memmove(out->bytes, out->bytes + 12, 4);
        memset(out->bytes + 4, 0, 12);
        out->family = AF_INET;
    }
    return 0;
}

static bool rr_owner_zero_address(const void *address, int family, int expected_family)
{
    static const unsigned char zero[16] = { 0 };
    static const unsigned char mapped_zero[16] = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 0, 0, 0, 0
    };
    return memcmp(address, zero, family == AF_INET ? 4U : 16U) == 0 ||
           (family == AF_INET6 && expected_family == AF_INET &&
            memcmp(address, mapped_zero, sizeof(mapped_zero)) == 0);
}

static bool rr_owner_equal_address(const void *address, int family,
                                   const struct rr_owner_address *expected)
{
    if (family == expected->family)
        return memcmp(address, expected->bytes, family == AF_INET ? 4U : 16U) == 0;
    if (family == AF_INET6 && expected->family == AF_INET) {
        static const unsigned char mapped_prefix[12] = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff
        };
        const unsigned char *bytes = address;
        return memcmp(bytes, mapped_prefix, sizeof(mapped_prefix)) == 0 &&
               memcmp(bytes + 12, expected->bytes, 4) == 0;
    }
    return false;
}

static bool rr_owner_matches(const struct inet_diag_msg *socket_info,
                             int protocol, int family,
                             const struct rr_owner_address *source, unsigned source_port,
                             const struct rr_owner_address *destination, unsigned destination_port)
{
    if (socket_info->idiag_family != family ||
        socket_info->id.idiag_sport != htons((uint16_t)source_port)) return false;
    /* TIME_WAIT and request sockets can report uid 0 without a live owner. */
    if (socket_info->idiag_inode == 0) return false;
    bool local_exact = rr_owner_equal_address(socket_info->id.idiag_src, family, source);
    bool remote_exact = socket_info->id.idiag_dport == htons((uint16_t)destination_port) &&
                        rr_owner_equal_address(socket_info->id.idiag_dst, family, destination);
    if (protocol == IPPROTO_TCP) return local_exact && remote_exact;
    bool local_wildcard = rr_owner_zero_address(socket_info->id.idiag_src, family, source->family);
    bool remote_wildcard = socket_info->id.idiag_dport == 0 &&
                           rr_owner_zero_address(socket_info->id.idiag_dst, family, destination->family);
    return (local_exact || local_wildcard) && (remote_exact || remote_wildcard);
}

static ssize_t rr_owner_receive(int fd, void *buffer, size_t capacity, int64_t deadline)
{
    for (;;) {
        int64_t now = rr_owner_now_ms();
        if (now < 0) return -1;
        if (now >= deadline) { errno = ETIMEDOUT; return -1; }
        struct pollfd waiter = { .fd = fd, .events = POLLIN, .revents = 0 };
        int ready = poll(&waiter, 1, (int)(deadline - now));
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return -1;
        if (ready == 0) { errno = ETIMEDOUT; return -1; }
        if ((waiter.revents & POLLIN) == 0) { errno = EIO; return -1; }
        struct sockaddr_nl sender;
        struct iovec iov = { .iov_base = buffer, .iov_len = capacity };
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
            errno = EMSGSIZE;
            return -1;
        }
        if (count == 0 || message.msg_namelen != sizeof(sender) ||
            sender.nl_family != AF_NETLINK || sender.nl_pid != 0 || sender.nl_groups != 0) {
            errno = EPROTO;
            return -1;
        }
        return count;
    }
}

/* A successful complete dump is required: returning an early match could miss
 * a second UID with a reused UDP port. All families share the same budget. */
static int rr_owner_dump(int fd, int protocol, int family, uint32_t sequence,
                         const struct rr_owner_address *source, unsigned source_port,
                         const struct rr_owner_address *destination, unsigned destination_port,
                         int64_t deadline, unsigned *datagrams, int *owner)
{
    struct {
        struct nlmsghdr header;
        struct inet_diag_req_v2 query;
    } request;
    struct sockaddr_nl kernel;
    memset(&request, 0, sizeof(request));
    memset(&kernel, 0, sizeof(kernel));
    kernel.nl_family = AF_NETLINK;
    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(request.query));
    request.header.nlmsg_type = SOCK_DIAG_BY_FAMILY;
    request.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    request.header.nlmsg_seq = sequence;
    request.query.sdiag_family = (uint8_t)family;
    request.query.sdiag_protocol = (uint8_t)protocol;
    request.query.idiag_states = UINT32_MAX;
    request.query.id.idiag_sport = htons((uint16_t)source_port);
    request.query.id.idiag_cookie[0] = INET_DIAG_NOCOOKIE;
    request.query.id.idiag_cookie[1] = INET_DIAG_NOCOOKIE;
    ssize_t sent = sendto(fd, &request, request.header.nlmsg_len, MSG_DONTWAIT,
                          (struct sockaddr *)&kernel, sizeof(kernel));
    if (sent != (ssize_t)request.header.nlmsg_len) {
        if (sent >= 0) errno = EIO;
        return -1;
    }
    while (*datagrams < RR_OWNER_MAX_DATAGRAMS) {
        union { struct nlmsghdr alignment; unsigned char bytes[RR_OWNER_BUFFER_SIZE]; } buffer;
        ssize_t count = rr_owner_receive(fd, buffer.bytes, sizeof(buffer.bytes), deadline);
        if (count < 0) return -1;
        ++*datagrams;
        int remaining = (int)count;
        /* NLMSG_NEXT may make this signed remainder negative on bad padding.
         * Guard it before the unsigned length comparison in NDK NLMSG_OK. */
        for (struct nlmsghdr *header = (struct nlmsghdr *)buffer.bytes;
             remaining > 0 && NLMSG_OK(header, (unsigned int)remaining);
             header = NLMSG_NEXT(header, remaining)) {
            if (header->nlmsg_seq != sequence) { errno = EPROTO; return -1; }
            if ((header->nlmsg_flags & NLM_F_DUMP_INTR) != 0) { errno = EINTR; return -1; }
            if (header->nlmsg_type == NLMSG_ERROR) {
                if (header->nlmsg_len < NLMSG_LENGTH(sizeof(struct nlmsgerr))) {
                    errno = EPROTO;
                    return -1;
                }
                const struct nlmsgerr *error = NLMSG_DATA(header);
                if (error->error != 0) {
                    errno = error->error < 0 && error->error != INT_MIN ? -error->error : EPROTO;
                    return -1;
                }
                continue;
            }
            if (header->nlmsg_type == NLMSG_DONE) {
                size_t payload = header->nlmsg_len - NLMSG_HDRLEN;
                if (payload != 0 && payload < sizeof(int)) { errno = EPROTO; return -1; }
                if (payload >= sizeof(int)) {
                    int error;
                    memcpy(&error, NLMSG_DATA(header), sizeof(error));
                    if (error != 0) {
                        errno = error < 0 && error != INT_MIN ? -error : EPROTO;
                        return -1;
                    }
                }
                return 0;
            }
            if (header->nlmsg_type == NLMSG_NOOP) continue;
            if (header->nlmsg_type == NLMSG_OVERRUN) { errno = ENOBUFS; return -1; }
            if (header->nlmsg_type != SOCK_DIAG_BY_FAMILY ||
                header->nlmsg_len < NLMSG_LENGTH(sizeof(struct inet_diag_msg))) {
                errno = EPROTO;
                return -1;
            }
            const struct inet_diag_msg *socket_info = NLMSG_DATA(header);
            if (!rr_owner_matches(socket_info, protocol, family, source, source_port,
                                   destination, destination_port)) continue;
            if (socket_info->idiag_uid > INT_MAX) { errno = EOVERFLOW; return -1; }
            int candidate = (int)socket_info->idiag_uid;
            if (*owner >= 0 && *owner != candidate) { errno = ENOTUNIQ; return -1; }
            *owner = candidate;
        }
        if (remaining != 0) { errno = EPROTO; return -1; }
    }
    errno = EOVERFLOW;
    return -1;
}

/* protocol is IPPROTO_TCP/IPPROTO_UDP; ports are in host byte order. UID 0 is
 * valid. -1 means absent, ambiguous, unsupported, invalid, or incomplete.
 * IPv4 queries also scan mapped IPv6 sockets and conservative IPv6 UDP
 * wildcard candidates. A dump failure never turns a partial match into a UID.
 * This is a snapshot: callers must not cache ownership indefinitely. */
static int rr_owner_lookup(int protocol, const char *srcIP, unsigned srcPort,
                           const char *dstIP, unsigned dstPort)
{
    struct rr_owner_address source, destination;
    if ((protocol != IPPROTO_TCP && protocol != IPPROTO_UDP) ||
        srcPort == 0 || srcPort > UINT16_MAX || dstPort > UINT16_MAX ||
        rr_owner_parse_address(srcIP, &source) != 0 ||
        rr_owner_parse_address(dstIP, &destination) != 0 || source.family != destination.family) {
        errno = EINVAL;
        return -1;
    }
    int64_t start = rr_owner_now_ms();
    if (start < 0) return -1;
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_SOCK_DIAG);
    if (fd < 0) return -1;
    struct sockaddr_nl local;
    memset(&local, 0, sizeof(local));
    local.nl_family = AF_NETLINK;
    int owner = -1;
    unsigned datagrams = 0;
    if (bind(fd, (struct sockaddr *)&local, sizeof(local)) != 0) goto failed;
    if (rr_owner_dump(fd, protocol, source.family, 1, &source, srcPort, &destination, dstPort,
                      start + RR_OWNER_TIMEOUT_MS, &datagrams, &owner) != 0) goto failed;
    if (source.family == AF_INET &&
        rr_owner_dump(fd, protocol, AF_INET6, 2, &source, srcPort, &destination, dstPort,
                      start + RR_OWNER_TIMEOUT_MS, &datagrams, &owner) != 0) goto failed;
    close(fd);
    if (owner < 0) errno = ENOENT;
    return owner;
failed:
    {
        int error = errno;
        close(fd);
        errno = error;
    }
    return -1;
}

#endif /* RRBOX_ROOT_ENGINE_OWNER_H */
