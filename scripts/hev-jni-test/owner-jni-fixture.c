#define _GNU_SOURCE
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <hev-task.h>
#include <hev-task-system.h>
#include "hev-main.h"
#include "rr-app-routing.h"

static atomic_ulong completed, mismatches, coroutine_stacks, invalid_tokens;
static int test_mode;

static int on_pthread_stack(void)
{
    pthread_attr_t attr;
    void *base;
    size_t size;
    volatile unsigned char marker;
    uintptr_t current = (uintptr_t)&marker;
    if (pthread_getattr_np(pthread_self(), &attr) != 0) return 0;
    int result = pthread_attr_getstack(&attr, &base, &size);
    pthread_attr_destroy(&attr);
    return !result && current >= (uintptr_t)base && current < (uintptr_t)base + size;
}

JNIEXPORT jboolean JNICALL
Java_com_rr_client_vpn_HevTunnelNative_isNativeThreadStack(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return on_pthread_stack() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_rr_client_vpn_HevTunnelNative_invalidTokens(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jlong)atomic_load(&invalid_tokens);
}

static void owner_task(void *data)
{
    ip_addr_t source, destination;
    int protocol = (int)(intptr_t)data;
    int64_t expected = ((int64_t)10428 << 32) | ((int64_t)65534 << 16) | 20809;
    ipaddr_aton("198.18.0.1", &source);
    ipaddr_aton("203.0.113.21", &destination);
    if (!on_pthread_stack()) atomic_fetch_add(&coroutine_stacks, 1);
    if (test_mode == 1 || test_mode == 3 || test_mode == 4 || test_mode == 5) expected = -1;
    if (test_mode == 2) expected = 0;
    int source_port = test_mode == 1 ? 65001 : test_mode == 3 ? 65002 : test_mode == 5 ? 65003 : 32100;
    int64_t actual = rr_app_route_lookup(protocol, &source, source_port,
                                          &destination, 443);
    if (actual != expected) atomic_fetch_add(&mismatches, 1);
    if (actual != expected && !(test_mode == 6 && actual == -1)) atomic_fetch_add(&invalid_tokens, 1);
    atomic_fetch_add(&completed, 1);
}

/* Replace only TUN I/O: the real JNI lifecycle and HEV scheduler remain under test. */
int hev_socks5_tunnel_main(const char *path, int fd)
{
    (void)fd;
    test_mode = strcmp(path, "exception") == 0 ? 1 : strcmp(path, "disabled") == 0 ? 2 :
        strcmp(path, "slow-stop") == 0 ? 3 : strcmp(path, "busy") == 0 ? 4 :
        strcmp(path, "slow-timeout") == 0 ? 5 : strcmp(path, "recovery") == 0 ? 6 : 0;
    atomic_store(&completed, 0);
    atomic_store(&mismatches, 0);
    atomic_store(&coroutine_stacks, 0);
    if (hev_task_system_init() != 0) return -1;
    HevTask *tcp = hev_task_new(86016), *udp = hev_task_new(86016);
    if (!tcp || !udp) abort();
    hev_task_run(tcp, owner_task, (void *)(intptr_t)6);
    hev_task_run(udp, owner_task, (void *)(intptr_t)17);
    hev_task_system_run();
    hev_task_system_fini();
    return 0;
}

void hev_socks5_tunnel_quit(void) { }
void hev_socks5_tunnel_stats(size_t *tx_packets, size_t *tx_bytes,
                            size_t *rx_packets, size_t *rx_bytes)
{
    *tx_packets = 2;
    *tx_bytes = atomic_load(&completed);
    *rx_packets = atomic_load(&mismatches);
    *rx_bytes = atomic_load(&coroutine_stacks);
}
