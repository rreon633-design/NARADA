/* SPDX-License-Identifier: LicenseRef-Proprietary
 * App-private immutable-fragment publication only. No crypto, content, logs,
 * long-lived handles, hard links or overwrite-capable rename fallback.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1U
#endif

/* Stable bridge-v1 status codes; never return a path or an OS error message. */
enum { STORAGE_OK = 0, STORAGE_COLLISION = 1, STORAGE_FULL = 2,
       STORAGE_UNSAFE = 3, STORAGE_UNAVAILABLE = 4 };

static int failure(int error) {
    if (error == EEXIST || error == ENOTEMPTY) return STORAGE_COLLISION;
    if (error == ENOSPC || error == EDQUOT) return STORAGE_FULL;
    /* A bounded errno number is diagnostic only: no path, content or OS text. */
    return error > 0 && error < 4096 ? 1000 + error : STORAGE_UNAVAILABLE;
}

static int same_inode(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
}

static int private_regular(const struct stat *s) {
    return S_ISREG(s->st_mode) && s->st_uid == geteuid() &&
           (s->st_mode & 077) == 0 && s->st_nlink == 1;
}

static int sibling_names(const char *source, const char *destination) {
    if (strncmp(source, "chunk-", 6) != 0) return 0;
    char *end = NULL;
    errno = 0;
    unsigned long index = strtoul(source + 6, &end, 10);
    if (errno != 0 || end == source + 6 || index > 255 ||
        strcmp(end, ".pending") != 0) return 0;
    char pending[32], installed[32];
    (void)snprintf(pending, sizeof(pending), "chunk-%lu.pending", index);
    (void)snprintf(installed, sizeof(installed), "chunk-%lu.enc", index);
    return strcmp(source, pending) == 0 && strcmp(destination, installed) == 0;
}

static int absolute_directory(const char *path) {
    if (path[0] != '/') return 0;
    const char *part = path + 1;
    for (const char *p = part;; ++p) {
        if (*p != '/' && *p != '\0') continue;
        size_t size = (size_t)(p - part);
        if (size == 0 || (size == 1 && part[0] == '.') ||
            (size == 2 && part[0] == '.' && part[1] == '.')) return 0;
        if (*p == '\0') return 1;
        part = p + 1;
    }
}

static int publish_fragment(const char *directory, const char *source,
                            const char *destination) {
    if (!absolute_directory(directory) || !sibling_names(source, destination))
        return STORAGE_UNSAFE;
    int parent = -1, input = -1, reopened = -1;
    int result = STORAGE_UNAVAILABLE;
    struct stat parent_stat, input_stat, named_stat, final_stat, current_parent;
    parent = open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0) return failure(errno);
    if (fstat(parent, &parent_stat) != 0) goto done;
    if (!S_ISDIR(parent_stat.st_mode) || parent_stat.st_uid != geteuid() ||
        (parent_stat.st_mode & 077) != 0) { result = STORAGE_UNSAFE; goto done; }
    /* O_NONBLOCK prevents an unexpected FIFO from blocking before fstat. */
    input = openat(parent, source, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (input < 0) { result = failure(errno); goto done; }
    if (fstat(input, &input_stat) != 0) goto done;
    if (!private_regular(&input_stat)) { result = STORAGE_UNSAFE; goto done; }
    if (fstatat(parent, source, &named_stat, AT_SYMLINK_NOFOLLOW) != 0) goto done;
    if (!same_inode(&input_stat, &named_stat) || !private_regular(&named_stat)) {
        result = STORAGE_UNSAFE; goto done;
    }
    if (fsync(input) != 0) { result = failure(errno); goto done; }
    /* Android prohibits hard links. This supported rename primitive keeps the
     * atomic no-replace property; ENOSYS/EINVAL/denial is fatal, never retried
     * with ordinary rename(), copy(), unlink() or a check-then-overwrite path. */
    if (syscall(SYS_renameat2, parent, source, parent, destination,
                RENAME_NOREPLACE) != 0) { result = failure(errno); goto done; }
    if (fstatat(parent, destination, &final_stat, AT_SYMLINK_NOFOLLOW) != 0)
        goto done;
    if (!same_inode(&input_stat, &final_stat) || !private_regular(&final_stat)) {
        result = STORAGE_UNSAFE; goto done;
    }
    if (fsync(parent) != 0) { result = failure(errno); goto done; }
    /* Do not acknowledge a directory that moved away from the checked path. */
    reopened = open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (reopened < 0 || fstat(reopened, &current_parent) != 0) goto done;
    if (!same_inode(&parent_stat, &current_parent)) { result = STORAGE_UNSAFE; goto done; }
    result = STORAGE_OK;
done:
    /* If rename succeeded but a later check/fsync/close failed, leave the new
     * immutable object untouched and report uncertainty. No rollback/delete. */
    if (reopened >= 0 && close(reopened) != 0 && result == STORAGE_OK)
        result = STORAGE_UNAVAILABLE;
    if (input >= 0 && close(input) != 0 && result == STORAGE_OK)
        result = STORAGE_UNAVAILABLE;
    if (close(parent) != 0 && result == STORAGE_OK) result = STORAGE_UNAVAILABLE;
    return result;
}

static int ascii_string(JNIEnv *env, jstring value, char *buffer, size_t capacity) {
    if (value == NULL) return 0;
    jsize bytes = (*env)->GetStringUTFLength(env, value);
    if ((*env)->ExceptionCheck(env) || bytes <= 0 || (size_t)bytes >= capacity) return 0;
    jsize characters = (*env)->GetStringLength(env, value);
    (*env)->GetStringUTFRegion(env, value, 0, characters, buffer);
    if ((*env)->ExceptionCheck(env)) return 0;
    buffer[bytes] = '\0';
    for (jsize i = 0; i < bytes; ++i)
        if ((unsigned char)buffer[i] < 32 || (unsigned char)buffer[i] > 126) return 0;
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_narada_connection_NativeStorageBridge_moveNoReplace(
        JNIEnv *env, jclass type, jstring directory, jstring source, jstring destination) {
    (void)type;
    char parent[4096], pending[32], installed[32];
    if (!ascii_string(env, directory, parent, sizeof(parent)) ||
        !ascii_string(env, source, pending, sizeof(pending)) ||
        !ascii_string(env, destination, installed, sizeof(installed))) return STORAGE_UNSAFE;
    return publish_fragment(parent, pending, installed);
}
