#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static void throw_io(JNIEnv *env, const char *prefix) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls == NULL) return;
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "%s: %s", prefix, strerror(errno));
    (*env)->ThrowNew(env, cls, buffer);
}

static char *copy_jstring(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *raw = (*env)->GetStringUTFChars(env, value, NULL);
    if (raw == NULL) return NULL;
    char *copy = strdup(raw);
    (*env)->ReleaseStringUTFChars(env, value, raw);
    return copy;
}

static char **copy_string_array(JNIEnv *env, jobjectArray array, int *count_out) {
    jsize count = array == NULL ? 0 : (*env)->GetArrayLength(env, array);
    char **values = calloc((size_t) count + 1, sizeof(char *));
    if (values == NULL) return NULL;
    for (jsize i = 0; i < count; ++i) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        values[i] = copy_jstring(env, item);
        (*env)->DeleteLocalRef(env, item);
        if (values[i] == NULL) {
            for (jsize j = 0; j < i; ++j) free(values[j]);
            free(values);
            return NULL;
        }
    }
    values[count] = NULL;
    *count_out = (int) count;
    return values;
}

static void free_string_array(char **values, int count) {
    if (values == NULL) return;
    for (int i = 0; i < count; ++i) free(values[i]);
    free(values);
}

JNIEXPORT jlongArray JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_nativeSpawn(
        JNIEnv *env, jobject thiz, jobjectArray argv_array, jstring cwd_string,
        jobjectArray env_array, jint rows, jint cols) {
    (void) thiz;
    int argc = 0;
    int envc = 0;
    char **argv = copy_string_array(env, argv_array, &argc);
    char **environment = copy_string_array(env, env_array, &envc);
    char *cwd = copy_jstring(env, cwd_string);
    if (argv == NULL || argc == 0 || argv[0] == NULL) {
        free_string_array(argv, argc);
        free_string_array(environment, envc);
        free(cwd);
        errno = EINVAL;
        throw_io(env, "invalid PTY argv");
        return NULL;
    }

    struct winsize window;
    memset(&window, 0, sizeof(window));
    window.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    window.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, NULL, NULL, &window);
    if (pid < 0) {
        free_string_array(argv, argc);
        free_string_array(environment, envc);
        free(cwd);
        throw_io(env, "forkpty failed");
        return NULL;
    }

    if (pid == 0) {
        if (cwd != NULL && cwd[0] != '\0') {
            (void) chdir(cwd);
        }
        for (int i = 0; i < envc; ++i) {
            char *entry = environment[i];
            char *equals = entry == NULL ? NULL : strchr(entry, '=');
            if (equals == NULL) continue;
            *equals = '\0';
            setenv(entry, equals + 1, 1);
            *equals = '=';
        }
        setenv("TERM", "xterm-256color", 0);
        setenv("COLORTERM", "truecolor", 0);
        execvp(argv[0], argv);
        _exit(127);
    }

    free_string_array(argv, argc);
    free_string_array(environment, envc);
    free(cwd);

    jlong values[2];
    values[0] = (jlong) master_fd;
    values[1] = (jlong) pid;
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result == NULL) {
        close(master_fd);
        kill(pid, SIGKILL);
        return NULL;
    }
    (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_nativeRead(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray buffer, jint offset, jint length) {
    (void) thiz;
    if (length <= 0) return 0;
    jsize size = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        errno = EINVAL;
        throw_io(env, "invalid read range");
        return -1;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return -1;
    ssize_t count;
    do {
        count = read(fd, bytes + offset, (size_t) length);
    } while (count < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    if (count < 0) {
        if (errno == EIO) return -1; /* common PTY EOF */
        throw_io(env, "PTY read failed");
        return -1;
    }
    return (jint) count;
}

JNIEXPORT jint JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_nativeWrite(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray buffer, jint offset, jint length) {
    (void) thiz;
    if (length <= 0) return 0;
    jsize size = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        errno = EINVAL;
        throw_io(env, "invalid write range");
        return -1;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return -1;
    ssize_t total = 0;
    while (total < length) {
        ssize_t count = write(fd, bytes + offset + total, (size_t) (length - total));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
            throw_io(env, "PTY write failed");
            return -1;
        }
        total += count;
    }
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return (jint) total;
}

JNIEXPORT void JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_nativeResize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) thiz;
    struct winsize window;
    memset(&window, 0, sizeof(window));
    window.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    window.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    (void) ioctl(fd, TIOCSWINSZ, &window);
}

JNIEXPORT jint JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_nativeWait(
        JNIEnv *env, jobject thiz, jint pid, jboolean block) {
    (void) thiz;
    int status = 0;
    pid_t result;
    do {
        result = waitpid((pid_t) pid, &status, block ? 0 : WNOHANG);
    } while (result < 0 && errno == EINTR);
    if (result == 0) return -1;
    if (result < 0) {
        if (errno == ECHILD) return 0;
        throw_io(env, "waitpid failed");
        return -2;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_nativeSignal(
        JNIEnv *env, jobject thiz, jint pid, jint signal_number) {
    (void) env;
    (void) thiz;
    if (pid <= 0) return;
    if (kill(-(pid_t) pid, signal_number) != 0) {
        (void) kill((pid_t) pid, signal_number);
    }
}

JNIEXPORT void JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_nativeClose(
        JNIEnv *env, jobject thiz, jint fd) {
    (void) env;
    (void) thiz;
    if (fd >= 0) close(fd);
}
