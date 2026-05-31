/*
 * fileops.c — Native file operations for TwoPaneFM.
 *
 * All filesystem I/O happens here: readdir, stat, copy, delete,
 * rename, mkdir, search, disk-usage. One JNI call per bulk operation.
 */
#include <jni.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/sendfile.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <ftw.h>

/* ═══════════════════════════════════════════════════════════════
 * 1. DIRECTORY SCAN — readdir + stat in one native call
 * ═══════════════════════════════════════════════════════════════ */

typedef struct {
    int count, cap;
    long   *sizes, *mtimes;
    int    *flags, *nameOffsets, *nameLens;
    char   *namesBuf;
    int     namesLen, namesCap;
} DirResult;

static void dr_init(DirResult *dr) {
    dr->count = 0; dr->cap = 256;
    dr->sizes       = (long*) malloc(sizeof(long) * dr->cap);
    dr->mtimes      = (long*) malloc(sizeof(long) * dr->cap);
    dr->flags       = (int*)  malloc(sizeof(int)  * dr->cap);
    dr->nameOffsets = (int*)  malloc(sizeof(int)  * dr->cap);
    dr->nameLens    = (int*)  malloc(sizeof(int)  * dr->cap);
    dr->namesBuf    = (char*) malloc(65536);
    dr->namesLen = 0; dr->namesCap = 65536;
}

static void dr_grow(DirResult *dr) {
    int nc = dr->cap * 2;
    dr->sizes       = (long*) realloc(dr->sizes,       sizeof(long) * nc);
    dr->mtimes      = (long*) realloc(dr->mtimes,      sizeof(long) * nc);
    dr->flags       = (int*)  realloc(dr->flags,       sizeof(int)  * nc);
    dr->nameOffsets = (int*)  realloc(dr->nameOffsets,  sizeof(int)  * nc);
    dr->nameLens    = (int*)  realloc(dr->nameLens,    sizeof(int)  * nc);
    dr->cap = nc;
}

static void dr_grow_names(DirResult *dr, int need) {
    while (dr->namesLen + need > dr->namesCap) {
        dr->namesCap *= 2;
        dr->namesBuf = (char*) realloc(dr->namesBuf, dr->namesCap);
    }
}

static void dr_free(DirResult *dr) {
    free(dr->sizes); free(dr->mtimes); free(dr->flags);
    free(dr->nameOffsets); free(dr->nameLens); free(dr->namesBuf);
    memset(dr, 0, sizeof(*dr));
}

static void dr_add(DirResult *dr, const char *name, int nlen,
                   long size, long mtime, int isDir, int isHidden,
                   int cr, int cw, int cx) {
    if (dr->count >= dr->cap) dr_grow(dr);
    dr_grow_names(dr, nlen + 1);
    int i = dr->count;
    dr->sizes[i]  = size;
    dr->mtimes[i] = mtime;
    dr->flags[i]  = (isDir?1:0)|(isHidden?2:0)|(cr?4:0)|(cw?8:0)|(cx?16:0);
    dr->nameOffsets[i] = dr->namesLen;
    dr->nameLens[i]    = nlen;
    memcpy(dr->namesBuf + dr->namesLen, name, nlen);
    dr->namesLen += nlen;
    dr->namesBuf[dr->namesLen++] = '\0';
    dr->count++;
}

#define MAX_SLOTS 4
static DirResult g_slots[MAX_SLOTS];
static int g_slot_used[MAX_SLOTS] = {0};

static int alloc_slot(void) {
    for (int i = 0; i < MAX_SLOTS; i++)
        if (!g_slot_used[i]) { g_slot_used[i]=1; dr_init(&g_slots[i]); return i; }
    return -1;
}

static void free_slot(int s) {
    if (s>=0 && s<MAX_SLOTS && g_slot_used[s]) { dr_free(&g_slots[s]); g_slot_used[s]=0; }
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeScanDir(
        JNIEnv *env, jclass cls, jstring jpath, jboolean showHidden) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -1;
    DIR *dir = opendir(path);
    if (!dir) { (*env)->ReleaseStringUTFChars(env, jpath, path); return -1; }

    int slot = alloc_slot();
    if (slot < 0) { closedir(dir); (*env)->ReleaseStringUTFChars(env, jpath, path); return -1; }

    DirResult *dr = &g_slots[slot];
    int plen = strlen(path);
    char *fp = (char*)malloc(plen + 258);
    memcpy(fp, path, plen);
    if (path[plen-1] != '/') { fp[plen] = '/'; plen++; }

    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        const char *n = ent->d_name;
        int nl = strlen(n);
        if (n[0]=='.' && (nl==1 || (nl==2 && n[1]=='.'))) continue;
        int hid = (n[0]=='.');
        if (!showHidden && hid) continue;

        memcpy(fp+plen, n, nl); fp[plen+nl] = '\0';
        struct stat st;
        long sz=0, mt=0; int d=0, cr=0,cw=0,cx=0;
        if (stat(fp, &st)==0) {
            sz = (long)st.st_size;
            mt = (long)st.st_mtime * 1000L;
            d  = S_ISDIR(st.st_mode) ? 1 : 0;
            cr = (st.st_mode & S_IRUSR) ? 1 : 0;
            cw = (st.st_mode & S_IWUSR) ? 1 : 0;
            cx = (st.st_mode & S_IXUSR) ? 1 : 0;
        } else {
            d = (ent->d_type == DT_DIR) ? 1 : 0;
        }
        dr_add(dr, n, nl, sz, mt, d, hid, cr, cw, cx);
    }
    closedir(dir); free(fp);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return slot;
}

JNIEXPORT jint JNICALL Java_com_twopane_fm_util_NativeFileOps_nativeGetCount(JNIEnv *e, jclass c, jint s) {
    return (s>=0&&s<MAX_SLOTS&&g_slot_used[s]) ? g_slots[s].count : 0;
}
JNIEXPORT jlongArray JNICALL Java_com_twopane_fm_util_NativeFileOps_nativeGetSizes(JNIEnv *e, jclass c, jint s) {
    if(s<0||s>=MAX_SLOTS||!g_slot_used[s]) return NULL; DirResult *d=&g_slots[s];
    jlongArray a=(*e)->NewLongArray(e,d->count); if(a) (*e)->SetLongArrayRegion(e,a,0,d->count,d->sizes); return a;
}
JNIEXPORT jlongArray JNICALL Java_com_twopane_fm_util_NativeFileOps_nativeGetMtimes(JNIEnv *e, jclass c, jint s) {
    if(s<0||s>=MAX_SLOTS||!g_slot_used[s]) return NULL; DirResult *d=&g_slots[s];
    jlongArray a=(*e)->NewLongArray(e,d->count); if(a) (*e)->SetLongArrayRegion(e,a,0,d->count,d->mtimes); return a;
}
JNIEXPORT jintArray JNICALL Java_com_twopane_fm_util_NativeFileOps_nativeGetFlags(JNIEnv *e, jclass c, jint s) {
    if(s<0||s>=MAX_SLOTS||!g_slot_used[s]) return NULL; DirResult *d=&g_slots[s];
    jintArray a=(*e)->NewIntArray(e,d->count); if(a) (*e)->SetIntArrayRegion(e,a,0,d->count,d->flags); return a;
}
JNIEXPORT jbyteArray JNICALL Java_com_twopane_fm_util_NativeFileOps_nativeGetNames(JNIEnv *e, jclass c, jint s) {
    if(s<0||s>=MAX_SLOTS||!g_slot_used[s]) return NULL; DirResult *d=&g_slots[s];
    jbyteArray a=(*e)->NewByteArray(e,d->namesLen); if(a) (*e)->SetByteArrayRegion(e,a,0,d->namesLen,(jbyte*)d->namesBuf); return a;
}
JNIEXPORT jintArray JNICALL Java_com_twopane_fm_util_NativeFileOps_nativeGetNameOffsets(JNIEnv *e, jclass c, jint s) {
    if(s<0||s>=MAX_SLOTS||!g_slot_used[s]) return NULL; DirResult *d=&g_slots[s];
    jintArray a=(*e)->NewIntArray(e,d->count); if(a) (*e)->SetIntArrayRegion(e,a,0,d->count,d->nameOffsets); return a;
}
JNIEXPORT jintArray JNICALL Java_com_twopane_fm_util_NativeFileOps_nativeGetNameLens(JNIEnv *e, jclass c, jint s) {
    if(s<0||s>=MAX_SLOTS||!g_slot_used[s]) return NULL; DirResult *d=&g_slots[s];
    jintArray a=(*e)->NewIntArray(e,d->count); if(a) (*e)->SetIntArrayRegion(e,a,0,d->count,d->nameLens); return a;
}
JNIEXPORT void JNICALL Java_com_twopane_fm_util_NativeFileOps_nativeFreeSlot(JNIEnv *e, jclass c, jint s) {
    free_slot(s);
}

JNIEXPORT jboolean JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeIsEmptyDir(JNIEnv *env, jclass cls, jstring jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return JNI_TRUE;
    DIR *d = opendir(p);
    if (!d) { (*env)->ReleaseStringUTFChars(env, jpath, p); return JNI_TRUE; }
    struct dirent *ent; jboolean empty = JNI_TRUE;
    while ((ent=readdir(d))!=NULL) {
        const char *n=ent->d_name;
        if (n[0]=='.' && (n[1]=='\0' || (n[1]=='.' && n[2]=='\0'))) continue;
        empty = JNI_FALSE; break;
    }
    closedir(d); (*env)->ReleaseStringUTFChars(env, jpath, p);
    return empty;
}

/* ═══════════════════════════════════════════════════════════════
 * 2. FILE COPY — sendfile() loop, zero userspace buffer copies
 * ═══════════════════════════════════════════════════════════════ */

static int copy_file(const char *src, const char *dst) {
    int fd_in = open(src, O_RDONLY);
    if (fd_in < 0) return -1;

    struct stat st;
    if (fstat(fd_in, &st) < 0) { close(fd_in); return -1; }

    int fd_out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, st.st_mode & 0777);
    if (fd_out < 0) { close(fd_in); return -1; }

    off_t offset = 0;
    size_t remaining = st.st_size;
    int ret = 0;

    while (remaining > 0) {
        ssize_t sent = sendfile(fd_out, fd_in, &offset, remaining);
        if (sent <= 0) {
            /* sendfile may fail for certain FS combos, fall back to read/write */
            if (sent < 0 && (errno == EINVAL || errno == ENOSYS)) {
                lseek(fd_in, 0, SEEK_SET);
                char buf[65536];
                ssize_t rd;
                while ((rd = read(fd_in, buf, sizeof(buf))) > 0) {
                    char *wp = buf;
                    ssize_t left = rd;
                    while (left > 0) {
                        ssize_t wr = write(fd_out, wp, left);
                        if (wr < 0) { ret = -1; goto done; }
                        wp += wr; left -= wr;
                    }
                }
                break;
            }
            ret = -1; break;
        }
        remaining -= sent;
    }

done:
    close(fd_in); close(fd_out);
    /* preserve mtime */
    struct timespec ts[2] = { st.st_atim, st.st_mtim };
    utimensat(AT_FDCWD, dst, ts, 0);
    return ret;
}

static int copy_dir_recursive(const char *src, const char *dst);

static int copy_entry(const char *src, const char *dst) {
    struct stat st;
    if (lstat(src, &st) < 0) return -1;

    if (S_ISDIR(st.st_mode)) {
        return copy_dir_recursive(src, dst);
    } else if (S_ISREG(st.st_mode)) {
        return copy_file(src, dst);
    } else if (S_ISLNK(st.st_mode)) {
        char target[4096];
        ssize_t len = readlink(src, target, sizeof(target)-1);
        if (len < 0) return -1;
        target[len] = '\0';
        return symlink(target, dst);
    }
    return 0; /* skip special files */
}

static int copy_dir_recursive(const char *src, const char *dst) {
    struct stat st;
    if (stat(src, &st) < 0) return -1;
    mkdir(dst, st.st_mode & 0777);

    DIR *dir = opendir(src);
    if (!dir) return -1;

    int slen = strlen(src), dlen = strlen(dst);
    char *sp = (char*)malloc(slen + 258);
    char *dp = (char*)malloc(dlen + 258);
    memcpy(sp, src, slen); sp[slen] = '/';
    memcpy(dp, dst, dlen); dp[dlen] = '/';

    int ret = 0;
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        const char *n = ent->d_name;
        if (n[0]=='.' && (n[1]=='\0' || (n[1]=='.' && n[2]=='\0'))) continue;
        int nl = strlen(n);
        memcpy(sp+slen+1, n, nl); sp[slen+1+nl] = '\0';
        memcpy(dp+dlen+1, n, nl); dp[dlen+1+nl] = '\0';
        if (copy_entry(sp, dp) < 0) ret = -1;
    }
    closedir(dir); free(sp); free(dp);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeCopy(JNIEnv *env, jclass cls,
        jstring jsrc, jstring jdst) {
    const char *src = (*env)->GetStringUTFChars(env, jsrc, NULL);
    const char *dst = (*env)->GetStringUTFChars(env, jdst, NULL);
    if (!src || !dst) { 
        if (src) (*env)->ReleaseStringUTFChars(env, jsrc, src);
        if (dst) (*env)->ReleaseStringUTFChars(env, jdst, dst);
        return -1;
    }

    struct stat st;
    int ret;
    if (stat(src, &st) < 0) {
        ret = -1;
    } else if (S_ISDIR(st.st_mode)) {
        ret = copy_dir_recursive(src, dst);
    } else {
        ret = copy_file(src, dst);
    }

    (*env)->ReleaseStringUTFChars(env, jsrc, src);
    (*env)->ReleaseStringUTFChars(env, jdst, dst);
    return ret;
}

/* ═══════════════════════════════════════════════════════════════
 * 3. RECURSIVE DELETE — unlink/rmdir in C, no Java objects
 * ═══════════════════════════════════════════════════════════════ */

static int remove_recursive(const char *path) {
    struct stat st;
    if (lstat(path, &st) < 0) return -1;

    if (!S_ISDIR(st.st_mode)) return unlink(path);

    DIR *dir = opendir(path);
    if (!dir) return -1;

    int plen = strlen(path);
    char *fp = (char*)malloc(plen + 258);
    memcpy(fp, path, plen); fp[plen] = '/';

    struct dirent *ent;
    int ret = 0;
    while ((ent = readdir(dir)) != NULL) {
        const char *n = ent->d_name;
        if (n[0]=='.' && (n[1]=='\0' || (n[1]=='.' && n[2]=='\0'))) continue;
        int nl = strlen(n);
        memcpy(fp+plen+1, n, nl); fp[plen+1+nl] = '\0';
        if (remove_recursive(fp) < 0) ret = -1;
    }
    closedir(dir); free(fp);
    if (rmdir(path) < 0) ret = -1;
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeDelete(JNIEnv *env, jclass cls, jstring jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    int ret = remove_recursive(p);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ret;
}

/* ═══════════════════════════════════════════════════════════════
 * 4. RENAME / MKDIR — single syscalls
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeRename(JNIEnv *env, jclass cls,
        jstring jold, jstring jnew) {
    const char *o = (*env)->GetStringUTFChars(env, jold, NULL);
    const char *n = (*env)->GetStringUTFChars(env, jnew, NULL);
    int ret = rename(o, n);
    (*env)->ReleaseStringUTFChars(env, jold, o);
    (*env)->ReleaseStringUTFChars(env, jnew, n);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeMkdir(JNIEnv *env, jclass cls, jstring jpath, jint mode) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    int ret = mkdir(p, (mode_t)mode);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeMkdirs(JNIEnv *env, jclass cls, jstring jpath, jint mode) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    /* mkdir -p: walk path components, create each */
    char tmp[4096];
    strncpy(tmp, p, sizeof(tmp)-1); tmp[sizeof(tmp)-1]='\0';
    int ret = 0;
    for (char *t = tmp+1; *t; t++) {
        if (*t == '/') {
            *t = '\0';
            if (mkdir(tmp, (mode_t)mode) < 0 && errno != EEXIST) { ret = -1; break; }
            *t = '/';
        }
    }
    if (ret == 0) {
        if (mkdir(tmp, (mode_t)mode) < 0 && errno != EEXIST) ret = -1;
    }
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ret;
}

/* ═══════════════════════════════════════════════════════════════
 * 5. FILE EXISTS / SINGLE STAT
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jboolean JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeExists(JNIEnv *env, jclass cls, jstring jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return JNI_FALSE;
    struct stat st;
    jboolean ok = (stat(p, &st) == 0) ? JNI_TRUE : JNI_FALSE;
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeIsDir(JNIEnv *env, jclass cls, jstring jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return JNI_FALSE;
    struct stat st;
    jboolean ok = (stat(p, &st) == 0 && S_ISDIR(st.st_mode)) ? JNI_TRUE : JNI_FALSE;
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ok;
}

/* ═══════════════════════════════════════════════════════════════
 * 6. RECURSIVE SEARCH — readdir + strstr, return all matches
 * ═══════════════════════════════════════════════════════════════ */

/* Search results stored in a static buffer */
#define SEARCH_MAX 200
static char g_search_results[SEARCH_MAX][4096];
static int  g_search_count = 0;

static void search_recursive(const char *dir_path, const char *query, int qlen) {
    if (g_search_count >= SEARCH_MAX) return;

    DIR *dir = opendir(dir_path);
    if (!dir) return;

    int dlen = strlen(dir_path);
    char *fp = (char*)malloc(dlen + 258);
    memcpy(fp, dir_path, dlen);
    if (dir_path[dlen-1] != '/') { fp[dlen] = '/'; dlen++; }

    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL && g_search_count < SEARCH_MAX) {
        const char *n = ent->d_name;
        int nl = strlen(n);
        if (n[0]=='.' && (nl==1 || (nl==2 && n[1]=='.'))) continue;

        /* Case-insensitive substring match */
        int matched = 0;
        if (nl >= qlen) {
            for (int i = 0; i <= nl - qlen; i++) {
                int j;
                for (j = 0; j < qlen; j++) {
                    char a = n[i+j], b = query[j];
                    if (a >= 'A' && a <= 'Z') a += 32;
                    if (b >= 'A' && b <= 'Z') b += 32;
                    if (a != b) break;
                }
                if (j == qlen) { matched = 1; break; }
            }
        }

        if (matched) {
            memcpy(fp+dlen, n, nl); fp[dlen+nl] = '\0';
            strncpy(g_search_results[g_search_count], fp, 4095);
            g_search_results[g_search_count][4095] = '\0';
            g_search_count++;
        }

        /* Recurse into directories */
        if (ent->d_type == DT_DIR && g_search_count < SEARCH_MAX) {
            memcpy(fp+dlen, n, nl); fp[dlen+nl] = '\0';
            search_recursive(fp, query, qlen);
        }
    }
    closedir(dir); free(fp);
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeSearch(JNIEnv *env, jclass cls,
        jstring jpath, jstring jquery) {
    const char *path  = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *query = (*env)->GetStringUTFChars(env, jquery, NULL);
    if (!path || !query) {
        if (path)  (*env)->ReleaseStringUTFChars(env, jpath, path);
        if (query) (*env)->ReleaseStringUTFChars(env, jquery, query);
        return 0;
    }

    g_search_count = 0;
    search_recursive(path, query, strlen(query));

    (*env)->ReleaseStringUTFChars(env, jpath, path);
    (*env)->ReleaseStringUTFChars(env, jquery, query);
    return g_search_count;
}

JNIEXPORT jstring JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeGetSearchResult(JNIEnv *env, jclass cls, jint idx) {
    if (idx < 0 || idx >= g_search_count) return NULL;
    return (*env)->NewStringUTF(env, g_search_results[idx]);
}

/* ═══════════════════════════════════════════════════════════════
 * 7. RECURSIVE DISK USAGE — stat sum in C
 * ═══════════════════════════════════════════════════════════════ */

static long long du_recursive(const char *path) {
    struct stat st;
    if (lstat(path, &st) < 0) return 0;

    long long total = st.st_blocks * 512; /* actual disk usage */

    if (S_ISDIR(st.st_mode)) {
        DIR *dir = opendir(path);
        if (!dir) return total;
        int plen = strlen(path);
        char *fp = (char*)malloc(plen + 258);
        memcpy(fp, path, plen); fp[plen] = '/';
        struct dirent *ent;
        while ((ent = readdir(dir)) != NULL) {
            const char *n = ent->d_name;
            if (n[0]=='.' && (n[1]=='\0' || (n[1]=='.' && n[2]=='\0'))) continue;
            int nl = strlen(n);
            memcpy(fp+plen+1, n, nl); fp[plen+1+nl] = '\0';
            total += du_recursive(fp);
        }
        closedir(dir); free(fp);
    }
    return total;
}

JNIEXPORT jlong JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeDiskUsage(JNIEnv *env, jclass cls, jstring jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return 0;
    long long total = du_recursive(p);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return (jlong)total;
}

/* ═══════════════════════════════════════════════════════════════
 * 8. TOUCH — set mtime
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeTouch(JNIEnv *env, jclass cls, jstring jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    int ret = utimensat(AT_FDCWD, p, NULL, 0); /* set to current time */
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ret;
}

/* ═══════════════════════════════════════════════════════════════
 * 9. CHMOD / CHOWN / SYMLINK / SET MODTIME
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeChmod(JNIEnv *env, jclass cls,
        jstring jpath, jint mode) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    int ret = chmod(p, (mode_t)mode);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeChown(JNIEnv *env, jclass cls,
        jstring jpath, jint uid, jint gid) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    int ret = chown(p, (uid_t)uid, (gid_t)gid);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeSymlink(JNIEnv *env, jclass cls,
        jstring jtarget, jstring jlink) {
    const char *target = (*env)->GetStringUTFChars(env, jtarget, NULL);
    const char *link   = (*env)->GetStringUTFChars(env, jlink, NULL);
    if (!target || !link) {
        if (target) (*env)->ReleaseStringUTFChars(env, jtarget, target);
        if (link)   (*env)->ReleaseStringUTFChars(env, jlink, link);
        return -1;
    }
    int ret = symlink(target, link);
    (*env)->ReleaseStringUTFChars(env, jtarget, target);
    (*env)->ReleaseStringUTFChars(env, jlink, link);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeSetModTime(JNIEnv *env, jclass cls,
        jstring jpath, jlong millis) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    struct timespec times[2];
    times[0].tv_sec  = (time_t)(millis / 1000);
    times[0].tv_nsec = (long)((millis % 1000) * 1000000);
    times[1].tv_sec  = times[0].tv_sec;
    times[1].tv_nsec = times[0].tv_nsec;
    int ret = utimensat(AT_FDCWD, p, times, 0);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ret;
}

JNIEXPORT jstring JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeReadlink(JNIEnv *env, jclass cls,
        jstring jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return NULL;
    char target[4096];
    ssize_t len = readlink(p, target, sizeof(target) - 1);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    if (len < 0) return NULL;
    target[len] = '\0';
    return (*env)->NewStringUTF(env, target);
}

JNIEXPORT jint JNICALL
Java_com_twopane_fm_util_NativeFileOps_nativeStatMode(JNIEnv *env, jclass cls,
        jstring jpath) {
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    struct stat st;
    int ret = (lstat(p, &st) == 0) ? (int)st.st_mode : -1;
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return ret;
}
