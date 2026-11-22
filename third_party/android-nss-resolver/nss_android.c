#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <nss.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define ANDROID_DNS_SOCKET "io.github.mohammedteir.opencodemobile.android-dns"
#define MAX_HOSTNAME 253
#define MAX_RESPONSE 4096
#define MAX_ADDRESSES 64

static enum nss_status lookup_android(const char *name, int family,
                                      struct hostent *result, char *buffer,
                                      size_t buffer_length, int *errnop,
                                      int *h_errnop) {
    if (!name || !*name || strlen(name) > MAX_HOSTNAME ||
        (family != AF_INET && family != AF_INET6)) {
        *errnop = EINVAL;
        *h_errnop = NO_RECOVERY;
        return NSS_STATUS_UNAVAIL;
    }
    for (const char *p = name; *p; ++p) {
        if ((unsigned char)*p <= 0x20 || *p == '/' || *p == '\\') {
            *errnop = EINVAL;
            *h_errnop = NO_RECOVERY;
            return NSS_STATUS_UNAVAIL;
        }
    }

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        *errnop = errno;
        *h_errnop = NO_RECOVERY;
        return NSS_STATUS_UNAVAIL;
    }
    struct timeval timeout = {.tv_sec = 3, .tv_usec = 0};
    (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1, "%s", ANDROID_DNS_SOCKET);
    socklen_t socket_address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) +
                                                  1 + strlen(ANDROID_DNS_SOCKET));
    if (connect(fd, (struct sockaddr *)&address, socket_address_length) != 0) {
        *errnop = errno;
        *h_errnop = NO_RECOVERY;
        close(fd);
        return NSS_STATUS_UNAVAIL;
    }

    char request[MAX_HOSTNAME + 8];
    int request_length = snprintf(request, sizeof(request), "%s %s\n",
                                  family == AF_INET ? "A" : "AAAA", name);
    if (request_length <= 0 || (size_t)request_length >= sizeof(request)) {
        *errnop = EINVAL;
        *h_errnop = NO_RECOVERY;
        close(fd);
        return NSS_STATUS_UNAVAIL;
    }
    size_t written = 0;
    while (written < (size_t)request_length) {
        ssize_t count = write(fd, request + written, (size_t)request_length - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            *errnop = errno ? errno : EIO;
            *h_errnop = TRY_AGAIN;
            close(fd);
            return NSS_STATUS_UNAVAIL;
        }
        written += (size_t)count;
    }

    char response[MAX_RESPONSE];
    size_t used = 0;
    while (used + 1 < sizeof(response)) {
        ssize_t count = read(fd, response + used, sizeof(response) - used - 1);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        used += (size_t)count;
        if (memchr(response, '\n', used)) break;
    }
    close(fd);
    response[used] = '\0';
    if (used == 0) {
        *errnop = EHOSTUNREACH;
        *h_errnop = TRY_AGAIN;
        return NSS_STATUS_UNAVAIL;
    }
    if (strncmp(response, "NOTFOUND\n", 9) == 0) {
        *errnop = EHOSTUNREACH;
        *h_errnop = HOST_NOT_FOUND;
        return NSS_STATUS_NOTFOUND;
    }
    if (strncmp(response, "OK ", 3) != 0) {
        *errnop = EHOSTUNREACH;
        *h_errnop = TRY_AGAIN;
        return NSS_STATUS_UNAVAIL;
    }

    const int address_length = family == AF_INET ? 4 : 16;
    unsigned char addresses[MAX_ADDRESSES][16];
    size_t address_count = 0;
    char *save = NULL;
    char *token = strtok_r(response + 3, " \t\r\n", &save);
    while (token && address_count < MAX_ADDRESSES) {
        unsigned char candidate[16];
        if (inet_pton(family, token, candidate) == 1) {
            memcpy(addresses[address_count++], candidate, (size_t)address_length);
        }
        token = strtok_r(NULL, " \t\r\n", &save);
    }
    if (address_count == 0) {
        *errnop = 0;
        *h_errnop = HOST_NOT_FOUND;
        return NSS_STATUS_NOTFOUND;
    }

    size_t pointer_bytes = (address_count + 1) * sizeof(char *);
    size_t alias_bytes = sizeof(char *);
    size_t name_bytes = strlen(name) + 1;
    uintptr_t base = (uintptr_t)buffer;
    uintptr_t aligned = (base + sizeof(char *) - 1) & ~(uintptr_t)(sizeof(char *) - 1);
    size_t padding = (size_t)(aligned - base);
    size_t needed = padding + pointer_bytes + alias_bytes + name_bytes +
                    address_count * (size_t)address_length;
    if (needed > buffer_length) {
        *errnop = ERANGE;
        *h_errnop = NETDB_INTERNAL;
        return NSS_STATUS_TRYAGAIN;
    }

    char **address_list = (char **)aligned;
    char **aliases = (char **)((char *)address_list + pointer_bytes);
    char *cursor = (char *)aliases + alias_bytes;
    char *canonical_name = cursor;
    memcpy(canonical_name, name, name_bytes);
    cursor += name_bytes;
    for (size_t i = 0; i < address_count; ++i) {
        address_list[i] = cursor;
        memcpy(cursor, addresses[i], (size_t)address_length);
        cursor += address_length;
    }
    address_list[address_count] = NULL;
    aliases[0] = NULL;

    result->h_name = canonical_name;
    result->h_aliases = aliases;
    result->h_addrtype = family;
    result->h_length = address_length;
    result->h_addr_list = address_list;
    *errnop = 0;
    *h_errnop = NETDB_SUCCESS;
    return NSS_STATUS_SUCCESS;
}

__attribute__((visibility("default")))
 enum nss_status _nss_android_gethostbyname2_r(const char *name, int family,
                                               struct hostent *result,
                                               char *buffer, size_t buffer_length,
                                               int *errnop, int *h_errnop) {
    return lookup_android(name, family, result, buffer, buffer_length, errnop, h_errnop);
}

__attribute__((visibility("default")))
 enum nss_status _nss_android_gethostbyname_r(const char *name,
                                              struct hostent *result,
                                              char *buffer, size_t buffer_length,
                                              int *errnop, int *h_errnop) {
    return lookup_android(name, AF_INET, result, buffer, buffer_length, errnop, h_errnop);
}
