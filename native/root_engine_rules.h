/* Exact DNS policy rules without Android's old ip command parser.
 * Linux rtnetlink ABI: UID/port ranges are native endian, addresses are network
 * order. Every mutation is ACKed and the complete table is read back before
 * activation. This file is private to root_engine.c (same lease/rollback).
 */
#ifndef RRBOX_ROOT_ENGINE_RULES_H
#define RRBOX_ROOT_ENGINE_RULES_H
#include <linux/fib_rules.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>

/* Stable UAPI ID, absent from older Android NDK headers. New kernels may
 * print this full mask for an exact port even when the request had no mask. */
#define RR_FRA_DPORT_MASK 29

struct rr_rule_request {
    struct nlmsghdr header;
    struct fib_rule_hdr rule;
    unsigned char attributes[256];
};

static bool rr_rule_attribute(struct rr_rule_request *request, unsigned type,
                              const void *value, size_t size)
{
    size_t offset = NLMSG_ALIGN(request->header.nlmsg_len);
    size_t length = RTA_LENGTH(size);
    if (offset + RTA_ALIGN(length) > sizeof(*request)) return false;
    struct rtattr *attribute = (struct rtattr *)((char *)request + offset);
    attribute->rta_type = (unsigned short)type;
    attribute->rta_len = (unsigned short)length;
    memcpy(RTA_DATA(attribute), value, size);
    request->header.nlmsg_len = (unsigned)(offset + RTA_ALIGN(length));
    return true;
}

static bool rr_rule_encode(struct rr_rule_request *request, const struct uid_range *range,
                           int family, uint32_t table, bool remove)
{
    if ((family != 4 && family != 6) || table < 42000 || table >= 43000 ||
        range->first > range->last || range->last > INT_MAX ||
        (range->dns_protocol && range->dns_protocol != 6 && range->dns_protocol != 17)) return false;
    memset(request, 0, sizeof(*request));
    request->header.nlmsg_len = NLMSG_LENGTH(sizeof(request->rule));
    request->header.nlmsg_type = remove ? RTM_DELRULE : RTM_NEWRULE;
    request->header.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
    if (!remove) request->header.nlmsg_flags |= NLM_F_CREATE | NLM_F_EXCL;
    request->rule.family = family == 4 ? AF_INET : AF_INET6;
    request->rule.action = FR_ACT_TO_TBL;
    uint32_t priority = 9000;
    if (!rr_rule_attribute(request, FRA_PRIORITY, &priority, sizeof(priority)) ||
        !rr_rule_attribute(request, FRA_TABLE, &table, sizeof(table))) return false;
    if (*range->destination) {
        char address[sizeof(range->destination)];
        snprintf(address, sizeof(address), "%s", range->destination);
        char *suffix = strchr(address, '/');
        if (!suffix || strcmp(suffix, family == 4 ? "/32" : "/128") != 0) return false;
        *suffix = '\0';
        unsigned char binary[16];
        if (inet_pton(request->rule.family, address, binary) != 1) return false;
        request->rule.dst_len = family == 4 ? 32 : 128;
        if (!rr_rule_attribute(request, FRA_DST, binary, family == 4 ? 4 : 16)) return false;
    }
    if (!range->internal_peer) {
        struct fib_rule_uid_range uids = { .start = range->first, .end = range->last };
        if (!rr_rule_attribute(request, FRA_IIFNAME, "lo", 3) ||
            !rr_rule_attribute(request, FRA_UID_RANGE, &uids, sizeof(uids))) return false;
    }
    if (range->dns_protocol) {
        if (range->internal_peer || !*range->destination) return false;
        uint8_t protocol = (uint8_t)range->dns_protocol;
        struct fib_rule_port_range ports = { .start = 53, .end = 53 };
        if (!rr_rule_attribute(request, FRA_IP_PROTO, &protocol, sizeof(protocol)) ||
            !rr_rule_attribute(request, FRA_DPORT_RANGE, &ports, sizeof(ports))) return false;
    }
    return true;
}

/* Parse attributes structurally before considering the table. Unknown selectors
 * on our rules fail verification, while protocol-origin/padding and kernel
 * default suppress values are metadata, not traffic selectors. */
struct rr_rule_view {
    const struct fib_rule_hdr *rule;
    const struct rtattr *attributes[RR_FRA_DPORT_MASK + 1];
    uint32_t table;
    bool unknown;
};

static bool rr_rule_view(const struct nlmsghdr *header, struct rr_rule_view *view)
{
    if (header->nlmsg_type != RTM_NEWRULE || header->nlmsg_len < NLMSG_LENGTH(sizeof(struct fib_rule_hdr))) return false;
    memset(view, 0, sizeof(*view));
    view->rule = NLMSG_DATA(header);
    view->table = view->rule->table;
    int left = (int)header->nlmsg_len - (int)NLMSG_LENGTH(sizeof(struct fib_rule_hdr));
    const struct rtattr *attribute = (const struct rtattr *)((const char *)view->rule + NLMSG_ALIGN(sizeof(*view->rule)));
    for (; left > 0 && RTA_OK(attribute, left); attribute = RTA_NEXT(attribute, left)) {
        unsigned type = attribute->rta_type;
        if (type == FRA_PAD) continue;
        if (type > RR_FRA_DPORT_MASK || type == FRA_UNSPEC) { view->unknown = true; continue; }
        if (view->attributes[type]) return false;
        view->attributes[type] = attribute;
    }
    if (left != 0) return false;
    if (view->attributes[FRA_TABLE]) {
        if (RTA_PAYLOAD(view->attributes[FRA_TABLE]) != sizeof(uint32_t)) return false;
        memcpy(&view->table, RTA_DATA(view->attributes[FRA_TABLE]), sizeof(view->table));
    }
    return true;
}

static bool rr_rule_value(const struct rr_rule_view *view, unsigned type, const void *value, size_t size)
{
    const struct rtattr *attribute = view->attributes[type];
    return attribute && RTA_PAYLOAD(attribute) == size && memcmp(RTA_DATA(attribute), value, size) == 0;
}

static bool rr_rule_matches(const struct rr_rule_view *view, const struct uid_range *range, int family)
{
    struct rr_rule_request expected;
    if (!rr_rule_encode(&expected, range, family, view->table, false)) return false;
    struct rr_rule_view wanted;
    if (!rr_rule_view(&expected.header, &wanted)) return false;
    const struct fib_rule_hdr *rule = view->rule;
    if (view->unknown || rule->family != expected.rule.family || rule->src_len ||
        rule->dst_len != expected.rule.dst_len || rule->tos || rule->flags ||
        rule->action != FR_ACT_TO_TBL || rule->res1 || rule->res2 ||
        (rule->table && rule->table != view->table &&
         !(rule->table == RT_TABLE_COMPAT && view->table >= 256))) return false;
    for (unsigned type = 1; type <= RR_FRA_DPORT_MASK; ++type) {
        const struct rtattr *actual = view->attributes[type], *needed = wanted.attributes[type];
        if (needed) {
            if (!rr_rule_value(view, type, RTA_DATA(needed), RTA_PAYLOAD(needed))) return false;
        } else if (actual) {
            if (type == FRA_PROTOCOL && RTA_PAYLOAD(actual) == 1) continue;
            uint16_t full_port_mask = UINT16_MAX;
            if (type == RR_FRA_DPORT_MASK && range->dns_protocol &&
                rr_rule_value(view, type, &full_port_mask, sizeof(full_port_mask))) continue;
            uint32_t unset = UINT32_MAX;
            uint8_t zero = 0;
            if ((type == FRA_SUPPRESS_IFGROUP || type == FRA_SUPPRESS_PREFIXLEN) &&
                rr_rule_value(view, type, &unset, sizeof(unset))) continue;
            if (type == FRA_L3MDEV && rr_rule_value(view, type, &zero, sizeof(zero))) continue;
            return false;
        }
    }
    return true;
}

struct rr_rule_snapshot {
    int family;
    uint32_t table;
    bool present, seen[MAX_RANGES];
    size_t found;
};

static bool rr_rule_observe(const struct nlmsghdr *header, struct rr_rule_snapshot *snapshot)
{
    struct rr_rule_view view;
    if (!rr_rule_view(header, &view)) return false;
    if (view.rule->family != (snapshot->family == 4 ? AF_INET : AF_INET6)) return false;
    if (view.table != snapshot->table) return true;
    if (!snapshot->present) return false;
    for (size_t i = 0; i < current.range_count; ++i) {
        const struct uid_range *range = &current.ranges[i];
        if (snapshot->seen[i] || (range->family && range->family != snapshot->family)) continue;
        if (rr_rule_matches(&view, range, snapshot->family)) {
            snapshot->seen[i] = true;
            ++snapshot->found;
            return true;
        }
    }
    return false;
}

/* One private socket per bounded operation: nothing survives fork or crosses
 * sessions. Accept replies only from the kernel and our exact sequence/port.
 * NLM_F_DUMP_INTR, truncation, invalid ACKs and timeout never mean success. */
static int rr_rule_exchange(struct rr_rule_request *request, bool cleanup, struct rr_rule_snapshot *snapshot)
{
    int result = ECANCELED, fd = -1;
    const char *operation = snapshot ? "dump" : request->header.nlmsg_type == RTM_DELRULE ? "delete" : "add";
    if (!cleanup) command_failure[0] = '\0';
    int64_t now = boot_ms(), end = now + COMMAND_TIMEOUT_MS;
    if (now < 0 || (!cleanup && !session_alive())) goto done;
    if (cleanup && current.cleanup_deadline_ms > 0 && current.cleanup_deadline_ms < end) end = current.cleanup_deadline_ms;
    if (now >= end) goto done;
    fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_ROUTE);
    if (fd < 0) { result = errno; goto done; }
    struct sockaddr_nl local = { .nl_family = AF_NETLINK }, kernel = { .nl_family = AF_NETLINK };
    if (bind(fd, (struct sockaddr *)&local, sizeof(local)) != 0) { result = errno; goto done; }
    socklen_t length = sizeof(local);
    if (getsockname(fd, (struct sockaddr *)&local, &length) != 0) { result = errno; goto done; }
    if (length != sizeof(local) || local.nl_family != AF_NETLINK || !local.nl_pid) { result = EPROTO; goto done; }
    request->header.nlmsg_seq = 1; /* unique socket/port for each request */
    request->header.nlmsg_pid = local.nl_pid;
    ssize_t sent = sendto(fd, request, request->header.nlmsg_len, 0, (struct sockaddr *)&kernel, sizeof(kernel));
    if (sent != (ssize_t)request->header.nlmsg_len) { result = sent < 0 ? errno : EIO; goto done; }
    size_t observed = 0;
    for (;;) {
        now = boot_ms();
        if (now < 0 || now >= end) { result = ETIMEDOUT; break; }
        if (!cleanup && !session_alive()) { result = ECANCELED; break; }
        struct pollfd wait = { .fd = fd, .events = POLLIN };
        int ready = poll(&wait, 1, (int)(end - now < 50 ? end - now : 50));
        if (ready < 0) { if (errno == EINTR) continue; result = errno; break; }
        if (!ready) continue;
        if (wait.revents & (POLLERR | POLLHUP | POLLNVAL)) { result = EIO; break; }
        union { struct nlmsghdr align; unsigned char bytes[32768]; } buffer;
        struct sockaddr_nl peer = { 0 };
        struct iovec vector = { .iov_base = buffer.bytes, .iov_len = sizeof(buffer.bytes) };
        struct msghdr message = { .msg_name = &peer, .msg_namelen = sizeof(peer), .msg_iov = &vector, .msg_iovlen = 1 };
        ssize_t count = recvmsg(fd, &message, MSG_DONTWAIT);
        if (count < 0) { if (errno == EINTR || errno == EAGAIN) continue; result = errno; break; }
        result = EPROTO;
        if (count == 0 || message.msg_flags & (MSG_TRUNC | MSG_CTRUNC) ||
            message.msg_namelen != sizeof(peer) || peer.nl_family != AF_NETLINK || peer.nl_pid || peer.nl_groups) break;
        observed += (size_t)count;
        if (observed > 1024 * 1024) { result = EOVERFLOW; break; }
        /* Validate the entire datagram before accepting a terminal ACK/DONE.
         * Linux/NDK alignment macros can make a signed remainder negative. */
        int validated = (int)count;
        struct nlmsghdr *check = (struct nlmsghdr *)buffer.bytes;
        for (; validated > 0 && NLMSG_OK(check, validated); check = NLMSG_NEXT(check, validated)) {}
        if (validated != 0) break;
        int left = (int)count;
        struct nlmsghdr *header = (struct nlmsghdr *)buffer.bytes;
        for (; left > 0 && NLMSG_OK(header, left); header = NLMSG_NEXT(header, left)) {
            if (header->nlmsg_seq != request->header.nlmsg_seq || header->nlmsg_pid != local.nl_pid ||
                header->nlmsg_flags & NLM_F_DUMP_INTR) goto done;
            if (header->nlmsg_type == NLMSG_ERROR) {
                if (header->nlmsg_len < NLMSG_LENGTH(sizeof(struct nlmsgerr)) ||
                    (unsigned)left != NLMSG_ALIGN(header->nlmsg_len)) goto done;
                struct nlmsgerr error;
                memcpy(&error, NLMSG_DATA(header), sizeof(error));
                if (error.msg.nlmsg_type != request->header.nlmsg_type || error.msg.nlmsg_seq != request->header.nlmsg_seq ||
                    error.error > 0 || error.error < -4095 || (!error.error && snapshot)) goto done;
                result = -error.error;
                goto done;
            }
            if (header->nlmsg_type == NLMSG_DONE) {
                if (!snapshot || header->nlmsg_len < NLMSG_LENGTH(sizeof(int)) ||
                    (unsigned)left != NLMSG_ALIGN(header->nlmsg_len)) goto done;
                int error;
                memcpy(&error, NLMSG_DATA(header), sizeof(error));
                if (error > 0 || error < -4095) goto done;
                result = -error;
                goto done;
            }
            if (!snapshot || !rr_rule_observe(header, snapshot)) goto done;
        }
        if (left) break;
    }
 done:
    if (fd >= 0) close(fd);
    if (!cleanup && result == 0 && !session_alive()) result = ECANCELED;
    if (!cleanup && result == 0 && current.activating) guardian_pulse();
    if (!cleanup && result != 0) {
        snprintf(command_failure, sizeof(command_failure), "netlink_errno=%d operation=%s family=%u table=%s output=%s",
                 result, operation, (unsigned)request->rule.family, current.table, strerror(result));
        for (char *p = command_failure; *p; ++p) if ((unsigned char)*p < 32 || (unsigned char)*p > 126) *p = ' ';
    }
    return result;
}

static int rr_change_dns_rule(const struct uid_range *range, int family, bool remove)
{
    uint64_t table;
    struct rr_rule_request request;
    if (!unsigned_number(current.table, 42999, &table) ||
        !rr_rule_encode(&request, range, family, (uint32_t)table, remove)) return EINVAL;
    return rr_rule_exchange(&request, remove, NULL);
}

static bool rr_verify_rule_snapshot(int family, bool present)
{
    uint64_t table;
    if (!unsigned_number(current.table, 42999, &table)) return false;
    struct rr_rule_request request = { 0 };
    request.header.nlmsg_len = NLMSG_LENGTH(sizeof(request.rule));
    request.header.nlmsg_type = RTM_GETRULE;
    request.header.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    request.rule.family = family == 4 ? AF_INET : AF_INET6;
    struct rr_rule_snapshot snapshot = { .family = family, .table = (uint32_t)table, .present = present };
    if (rr_rule_exchange(&request, !present, &snapshot) != 0) return false;
    size_t expected = 0;
    if (present) for (size_t i = 0; i < current.range_count; ++i)
        if (!current.ranges[i].family || current.ranges[i].family == family) ++expected;
    return snapshot.found == expected;
}
#endif
