#ifndef AIRLIFT_POSIX_H
#define AIRLIFT_POSIX_H
#define _GNU_SOURCE
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <dirent.h>
#include <pthread.h>

static inline void airlift_close(int fd) { if (fd >= 0) close(fd); }
/* Keep managed descriptors above stdin/out/err, even if the parent has closed them. */
static inline int airlift_safe_fd(int fd) {
    if(fd<0 || fd>=3) return fd;
    int safe=fcntl(fd,F_DUPFD_CLOEXEC,3); int saved=errno; close(fd); errno=saved; return safe;
}
static inline int airlift_pipe(int fds[2]) {
    if(pipe2(fds,O_CLOEXEC)<0) return -1;
    fds[0]=airlift_safe_fd(fds[0]);
    if(fds[0]<0) { int e=errno; close(fds[1]); fds[1]=-1; errno=e; return -1; }
    fds[1]=airlift_safe_fd(fds[1]);
    if(fds[1]<0) { int e=errno; close(fds[0]); fds[0]=-1; errno=e; return -1; }
    return 0;
}
static inline void airlift_child_error(int fd, int error) {
    const char *p = (const char *)&error; size_t left = sizeof(error);
    while (left) { ssize_t n = write(fd, p, left); if (n < 0 && errno == EINTR) continue; if (n <= 0) break; p += n; left -= n; }
    _exit(127);
}
/* Modes: 0 pipe, 1 inherit, 2 file/truncate, 3 file/append.
 * No Kotlin/runtime code executes between fork and execve. All descriptors are CLOEXEC.
 */
static inline int airlift_spawn(const char *exe, char *const argv[], char *const envp[], const char *cwd,
    const int modes[3], const char *const paths[3], int merge, int result[3], int *error) {
    int pipes[3][2] = {{-1,-1},{-1,-1},{-1,-1}}, files[3] = {-1,-1,-1}, report[2] = {-1,-1};
    int pid = -1; *error = 0;
    for (int i=0;i<3;i++) result[i] = -1;
    for (int i=0;i<3;i++) {
        if (i==2 && merge) continue;
        if (modes[i]==0) { if (airlift_pipe(pipes[i])<0) goto failed; }
        else if (modes[i]>=2) {
            files[i] = airlift_safe_fd(open(paths[i], (i==0 ? O_RDONLY : O_WRONLY|O_CREAT|(modes[i]==3 ? O_APPEND : O_TRUNC))|O_CLOEXEC, 0666));
            if (files[i]<0) goto failed;
        }
    }
    if (airlift_pipe(report)<0) goto failed;
    pid = fork();
    if (pid<0) goto failed;
    if (pid==0) {
        close(report[0]);
        if (cwd && chdir(cwd)<0) airlift_child_error(report[1], errno);
        for (int i=0;i<3;i++) {
            if (i==2 && merge) { if (dup2(1,2)<0) airlift_child_error(report[1],errno); continue; }
            int fd = modes[i]==0 ? pipes[i][i==0 ? 0 : 1] : files[i];
            if (fd>=0 && dup2(fd,i)<0) airlift_child_error(report[1],errno);
        }
        for (int i=0;i<3;i++) { airlift_close(pipes[i][0]); airlift_close(pipes[i][1]); airlift_close(files[i]); }
        execve(exe, argv, envp); airlift_child_error(report[1], errno);
    }
    close(report[1]); report[1]=-1;
    for (int i=0;i<3;i++) {
        airlift_close(files[i]); files[i]=-1;
        if (pipes[i][0]>=0) {
            result[i]=pipes[i][i==0 ? 1 : 0];
            close(pipes[i][i==0 ? 0 : 1]);
            pipes[i][0]=pipes[i][1]=-1;
        }
    }
    {
        size_t received=0; ssize_t n;
        while (received<sizeof(*error)) {
            n=read(report[0], ((char*)error)+received, sizeof(*error)-received);
            if(n<0 && errno==EINTR) continue;
            if(n<0) { *error=errno; break; }
            if(n==0) break;
            received+=(size_t)n;
        }
        close(report[0]); report[0]=-1;
        if (*error || received) {
            if (!*error) *error=EIO;
            for(int i=0;i<3;i++) { airlift_close(result[i]); result[i]=-1; }
            kill(pid,SIGKILL); while(waitpid(pid,NULL,0)<0 && errno==EINTR) {}
            return -1;
        }
    }
    for(int i=0;i<3;i++) if(result[i]>=0) {
        int flags=fcntl(result[i],F_GETFL);
        if(flags<0 || fcntl(result[i],F_SETFL,flags|O_NONBLOCK)<0) {
            *error=errno; kill(pid,SIGKILL); while(waitpid(pid,NULL,0)<0 && errno==EINTR) {}
            for(int k=0;k<3;k++) { airlift_close(result[k]); result[k]=-1; }
            return -1;
        }
    }
    return pid;
failed:
    *error=errno;
    for(int i=0;i<3;i++) { airlift_close(pipes[i][0]); airlift_close(pipes[i][1]); airlift_close(files[i]); }
    airlift_close(report[0]); airlift_close(report[1]); return -1;
}
/* Block SIGPIPE only on the calling thread and consume our own newly generated signal. */
static inline ssize_t airlift_write(int fd, const void *data, size_t size) {
    sigset_t set, old, pending; sigemptyset(&set); sigaddset(&set,SIGPIPE);
    int rc=pthread_sigmask(SIG_BLOCK,&set,&old); if(rc) { errno=rc; return -1; }
    sigpending(&pending); int already=sigismember(&pending,SIGPIPE);
    ssize_t n=write(fd,data,size); int saved=errno;
    if(n<0 && saved==EPIPE && !already) { struct timespec zero={0,0}; while(sigtimedwait(&set,NULL,&zero)<0 && errno==EINTR) {} }
    pthread_sigmask(SIG_SETMASK,&old,NULL); errno=saved; return n;
}
static inline int airlift_remove_contents(int fd) {
    DIR *dir=fdopendir(fd); if(!dir) { int e=errno; close(fd); errno=e; return -1; }
    struct dirent *entry; int error=0;
    for(;;) {
        errno=0; entry=readdir(dir); if(!entry) { if(errno) error=errno; break; }
        if(!strcmp(entry->d_name,".") || !strcmp(entry->d_name,"..")) continue;
        struct stat st;
        if(fstatat(dirfd(dir),entry->d_name,&st,AT_SYMLINK_NOFOLLOW)<0) { error=errno; break; }
        if(S_ISDIR(st.st_mode)) {
            int child=openat(dirfd(dir),entry->d_name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            if(child<0 || airlift_remove_contents(child)<0 || unlinkat(dirfd(dir),entry->d_name,AT_REMOVEDIR)<0) { error=errno; break; }
        } else if(unlinkat(dirfd(dir),entry->d_name,0)<0) { error=errno; break; }
    }
    closedir(dir); if(error) { errno=error; return -1; } return 0;
}
static inline int airlift_remove_directory(const char *path) {
    int fd=open(path,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0) return errno==ENOENT ? 0 : -1;
    if(airlift_remove_contents(fd)<0) return -1;
    return rmdir(path);
}
static inline char **airlift_environ(void) { extern char **environ; return environ; }
#endif
