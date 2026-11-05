/*
 * pam_pixelsync.c — PixelSync phone-unlock PAM module (hardened, v2 grant).
 *
 * macOS lock screen authenticates via PAM (/etc/pam.d/screensaver). This module
 * sits as `auth sufficient` before the password module. If a valid grant written
 * by the PixelSync app (after a phone fingerprint signature) exists, it consumes
 * it and returns PAM_SUCCESS (passwordless). Otherwise PAM_IGNORE (password path
 * unchanged).
 *
 * Hardening:
 *  - v2 grant carries the phone's ECDSA signature over the unlock transcript.
 *    The signature is verified here against a **root-owned** public key, so a
 *    process running as the user cannot forge a grant by writing the file.
 *  - binds the grant to a username (PAM_USER) and to that user's uid/ownership
 *  - the grant file must be owner-only (no group/other bits)
 *  - strict, atomic single-use consumption (unlink)
 *  - bounded even when the file is malformed
 *  - never reads/stores a password
 *
 * Trust provisioning: `/usr/local/etc/pixelsync/trusted_phones/<phoneId>.pub`
 * (one base64 X.509 SubjectPublicKeyInfo per paired phone, root:wheel 0644).
 * Install/update with `sudo ./install.sh` from MacOS/pam.
 */
#include <security/pam_appl.h>
#include <security/pam_modules.h>

#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>

#include <errno.h>
#include <limits.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <syslog.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define GRANT_PATH "/Users/Shared/PixelSync/unlock_grant"
#define TRUST_DIR "/usr/local/etc/pixelsync/trusted_phones"
#define MAX_GRANT_BYTES 16384
#define MAX_TRUST_BYTES 8192

static void plog(const char *msg) {
    syslog(LOG_ERR, "pam_pixelsync: %s", msg);
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long long)ts.tv_sec * 1000LL + (long long)ts.tv_nsec / 1000000LL;
}

static int b64val(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

/* Lenient base64 decoder (whitespace ignored, '=' terminates). */
static unsigned char *b64decode(const char *in, size_t *out_len) {
    if (!in || !out_len) return NULL;
    size_t cap = strlen(in) / 4 * 3 + 4;
    unsigned char *out = malloc(cap);
    if (!out) return NULL;
    int val = 0, bits = -8;
    size_t o = 0;
    for (const char *p = in; *p; p++) {
        if (*p == '=') break;
        int v = b64val(*p);
        if (v < 0) continue; /* whitespace / invalid: skip */
        val = (val << 6) | v;
        bits += 6;
        if (bits >= 0) {
            if (o >= cap) { free(out); return NULL; }
            out[o++] = (unsigned char)((val >> bits) & 0xFF);
            bits -= 8;
        }
    }
    if (o == 0) { free(out); return NULL; }
    *out_len = o;
    return out;
}

static int valid_phone_id(const char *s) {
    if (!s || !*s) return 0;
    size_t n = strlen(s);
    if (n > 128) return 0;
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
              (c >= '0' && c <= '9') || c == '-' || c == '_')) return 0;
    }
    return 1;
}

/* Reads a root-owned phone public key and verifies the ECDSA signature over
 * the transcript. Returns 1 only on a complete, valid verification. */
static int verify_phone_signature(const char *phoneId,
                                  const unsigned char *transcript, size_t tlen,
                                  const unsigned char *sig, size_t slen) {
    if (!valid_phone_id(phoneId) || !transcript || tlen == 0 || !sig || slen == 0) return 0;

    char path[PATH_MAX];
    int n = snprintf(path, sizeof(path), TRUST_DIR "/%s.pub", phoneId);
    if (n <= 0 || (size_t)n >= sizeof(path)) return 0;

    struct stat st;
    if (stat(path, &st) != 0) return 0;
    if (st.st_uid != 0) return 0;                 /* root-owned */
    if (st.st_mode & 022) return 0;               /* not group/other writable */
    if (st.st_size <= 0 || st.st_size > MAX_TRUST_BYTES) return 0;

    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char *b64 = malloc((size_t)st.st_size + 1);
    if (!b64) { fclose(f); return 0; }
    size_t got = fread(b64, 1, (size_t)st.st_size, f);
    fclose(f);
    b64[got] = '\0';

    size_t der_len = 0;
    unsigned char *der = b64decode(b64, &der_len);
    free(b64);
    if (!der) return 0;

    /* Accept a P-256 SubjectPublicKeyInfo DER: 26-byte header + 0x04 + 64. */
    const unsigned char *raw = NULL;
    if (der_len >= 65 && der[der_len - 65] == 0x04) {
        raw = der + der_len - 65;
    } else {
        free(der);
        return 0;
    }

    CFMutableDictionaryRef attrs = CFDictionaryCreateMutable(
        kCFAllocatorDefault, 0,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    if (!attrs) { free(der); return 0; }
    CFDictionarySetValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom);
    CFDictionarySetValue(attrs, kSecAttrKeyClass, kSecAttrKeyClassPublic);
    int bits = 256;
    CFNumberRef bitsNum = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &bits);
    if (bitsNum) {
        CFDictionarySetValue(attrs, kSecAttrKeySizeInBits, bitsNum);
        CFRelease(bitsNum);
    }

    CFDataRef keyData = CFDataCreate(kCFAllocatorDefault, raw, 65);
    free(der);
    if (!keyData) { CFRelease(attrs); return 0; }

    CFErrorRef err = NULL;
    SecKeyRef key = SecKeyCreateWithData(keyData, attrs, &err);
    CFRelease(keyData);
    CFRelease(attrs);
    if (!key) {
        if (err) CFRelease(err);
        return 0;
    }
    if (err) { CFRelease(err); err = NULL; }

    CFDataRef t = CFDataCreate(kCFAllocatorDefault, transcript, (CFIndex)tlen);
    CFDataRef s = CFDataCreate(kCFAllocatorDefault, sig, (CFIndex)slen);
    int ok = 0;
    if (t && s) {
        ok = SecKeyVerifySignature(key,
                                   kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                                   t, s, &err) ? 1 : 0;
    }
    if (err) CFRelease(err);
    if (t) CFRelease(t);
    if (s) CFRelease(s);
    CFRelease(key);
    return ok;
}

/* Returns 1 if a valid v2 grant exists for `user` (name). Does not consume. */
static int grant_valid_for(const char *user) {
    if (!user || !*user) return 0;

    struct stat st;
    if (stat(GRANT_PATH, &st) != 0) return 0;
    if (st.st_size <= 0 || st.st_size > MAX_GRANT_BYTES) return 0;

    /* Defence in depth: grant must belong to the target user and be 0600. */
    struct passwd *pw = getpwnam(user);
    if (!pw) return 0;
    if (st.st_uid != pw->pw_uid) return 0;
    if (st.st_mode & 077) return 0;

    FILE *f = fopen(GRANT_PATH, "r");
    if (!f) return 0;

    char l1[16] = {0}, l2[2048] = {0}, l3[256] = {0}, l4[64] = {0};
    char l5[256] = {0}, l6[256] = {0}, l7[8192] = {0}, l8[8192] = {0};
    int ok = 0;

    if (fgets(l1, sizeof(l1), f) && fgets(l2, sizeof(l2), f) &&
        fgets(l3, sizeof(l3), f) && fgets(l4, sizeof(l4), f) &&
        fgets(l5, sizeof(l5), f) && fgets(l6, sizeof(l6), f) &&
        fgets(l7, sizeof(l7), f) && fgets(l8, sizeof(l8), f)) {
        char *nl;
        if ((nl = strchr(l1, '\n'))) *nl = '\0';
        if ((nl = strchr(l4, '\n'))) *nl = '\0';
        if ((nl = strchr(l5, '\n'))) *nl = '\0';
        if ((nl = strchr(l6, '\n'))) *nl = '\0';
        if ((nl = strchr(l7, '\n'))) *nl = '\0';
        if ((nl = strchr(l8, '\n'))) *nl = '\0';

        if (strcmp(l1, "v2") == 0) {
            long long expiry = atoll(l4);
            if (expiry > 0 && now_ms() <= expiry && strcmp(l5, user) == 0) {
                size_t tlen = 0, slen = 0;
                unsigned char *transcript = b64decode(l7, &tlen);
                unsigned char *sig = b64decode(l8, &slen);
                if (transcript && sig) {
                    ok = verify_phone_signature(l6, transcript, tlen, sig, slen);
                }
                free(transcript);
                free(sig);
            }
        }
    }
    fclose(f);
    return ok;
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
            plog("auth: signed grant valid -> SUCCESS");
        } else {
            /* Could not consume: do NOT authenticate (avoid replay). */
            plog("auth: signed grant valid but unlink failed -> IGNORE");
            return PAM_IGNORE;
        }
        return PAM_SUCCESS;
    }
    plog("auth: no valid signed grant -> IGNORE");
    return PAM_IGNORE;
}

PAM_EXTERN int pam_sm_setcred(pam_handle_t *pamh, int flags, int argc, const char **argv) {
    (void)pamh; (void)flags; (void)argc; (void)argv;
    return PAM_SUCCESS;
}
