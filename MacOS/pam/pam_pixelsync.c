/*
 * pam_pixelsync.c — PixelSync phone-unlock PAM module (hardened).
 *
 * macOS lock screen authenticates via PAM (/etc/pam.d/screensaver). This module
 * sits as `auth sufficient` before the password module. If a valid grant written
 * by the PixelSync app (after a phone fingerprint signature) exists, it consumes
 * it and returns PAM_SUCCESS (passwordless). Otherwise PAM_IGNORE (password path
 * unchanged).
 *
 * Hardening:
 *  - binds the grant to a username (PAM_USER) and to that user's uid/ownership
 *  - strict, atomic single-use consumption (unlink)
 *  - bounded even when the file is malformed
 *  - never reads/stores a password
 */
#include <security/pam_appl.h>
#include <security/pam_modules.h>

#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <syslog.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define GRANT_PATH "/Users/Shared/PixelSync/unlock_grant"
#define MAX_GRANT_BYTES 4096

static void plog(const char *msg) {
    syslog(LOG_ERR, "pam_pixelsync: %s", msg);
}

__attribute__((constructor)) static void pam_pixelsync_load(void) {
    plog("module loaded");
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long long)ts.tv_sec * 1000LL + (long long)ts.tv_nsec / 1000000LL;
}

/* Returns 1 if a valid grant exists for `user` (name). Does not consume. */
static int grant_valid_for(const char *user) {
    if (!user || !*user) return 0;

    FILE *f = fopen(GRANT_PATH, "r");
    if (!f) return 0;

    char l1[1024] = {0}, l2[1024] = {0}, l3[64] = {0}, l4[256] = {0};
    int ok = 0;
    if (fgets(l1, sizeof(l1), f) && fgets(l2, sizeof(l2), f) &&
        fgets(l3, sizeof(l3), f) && fgets(l4, sizeof(l4), f)) {
        char *nl;
        if ((nl = strchr(l4, '\n'))) *nl = '\0';
        long long expiry = atoll(l3);
        if (expiry > 0 && now_ms() <= expiry && strcmp(l4, user) == 0) ok = 1;
    }
    fclose(f);
    if (!ok) return 0;

    /* The grant file must be owned by the target user (defence in depth). */
    struct stat st;
    struct passwd *pw = getpwnam(user);
    if (!pw) return 0;
    if (stat(GRANT_PATH, &st) != 0) return 0;
    if (st.st_uid != pw->pw_uid) return 0;
    if (st.st_size > MAX_GRANT_BYTES) return 0;
    return 1;
}

PAM_EXTERN int pam_sm_authenticate(pam_handle_t *pamh, int flags, int argc, const char **argv) {
    (void)flags; (void)argc; (void)argv;

    const char *user = NULL;
    if (pam_get_item(pamh, PAM_USER, (const void **)&user) != PAM_SUCCESS || !user) {
        plog("auth: no PAM_USER -> IGNORE");
        return PAM_IGNORE;
    }

    if (grant_valid_for(user)) {
        if (unlink(GRANT_PATH) == 0) {
            plog("auth: grant valid -> SUCCESS");
        } else {
            /* Could not consume: do NOT authenticate (avoid replay). */
            plog("auth: grant valid but unlink failed -> IGNORE");
            return PAM_IGNORE;
        }
        return PAM_SUCCESS;
    }
    plog("auth: no valid grant -> IGNORE");
    return PAM_IGNORE;
}

PAM_EXTERN int pam_sm_setcred(pam_handle_t *pamh, int flags, int argc, const char **argv) {
    (void)pamh; (void)flags; (void)argc; (void)argv;
    return PAM_SUCCESS;
}
