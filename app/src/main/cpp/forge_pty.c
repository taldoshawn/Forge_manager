#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static int set_window(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    return ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jlong JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_spawn(
        JNIEnv *env, jobject thiz, jstring executable_, jstring cwd_, jint rows, jint cols) {
    (void)thiz;
    const char *executable = (*env)->GetStringUTFChars(env, executable_, NULL);
    if (!executable) return 0;
    const char *cwd = (*env)->GetStringUTFChars(env, cwd_, NULL);
    if (!cwd) {
        (*env)->ReleaseStringUTFChars(env, executable_, executable);
        return 0;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) goto fail;
    if (grantpt(master) != 0 || unlockpt(master) != 0) { close(master); goto fail; }

    char slave_name[128];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) { close(master); goto fail; }
    set_window(master, rows, cols);

    pid_t pid = fork();
    if (pid < 0) { close(master); goto fail; }
    if (pid == 0) {
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        set_window(slave, rows, cols);

        struct termios tio;
        if (tcgetattr(slave, &tio) == 0) {
            tio.c_iflag |= ICRNL | IXON;
            tio.c_oflag |= OPOST | ONLCR;
            tio.c_lflag |= ISIG | ICANON | ECHO | ECHOE;
            tcsetattr(slave, TCSANOW, &tio);
        }

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        chdir(cwd);
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("SHELL", executable, 1);
        execlp(executable, executable, (char *)NULL);
        _exit(127);
    }

    (*env)->ReleaseStringUTFChars(env, executable_, executable);
    (*env)->ReleaseStringUTFChars(env, cwd_, cwd);
    return (((jlong)(uint32_t)pid) << 32) | (uint32_t)master;

fail:
    (*env)->ReleaseStringUTFChars(env, executable_, executable);
    (*env)->ReleaseStringUTFChars(env, cwd_, cwd);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_resize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void)env; (void)thiz;
    return set_window(fd, rows, cols);
}

JNIEXPORT jint JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_signal(
        JNIEnv *env, jobject thiz, jint pid, jint sig) {
    (void)env; (void)thiz;
    if (pid <= 0) return -1;
    return kill((pid_t)pid, sig);
}

JNIEXPORT jint JNICALL
Java_com_forgemanager_app_features_terminal_PtyBridge_waitPid(
        JNIEnv *env, jobject thiz, jint pid) {
    (void)env; (void)thiz;
    int status = 0;
    pid_t result;
    do { result = waitpid((pid_t)pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}
