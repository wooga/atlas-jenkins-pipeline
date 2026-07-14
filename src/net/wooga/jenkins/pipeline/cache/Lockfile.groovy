package net.wooga.jenkins.pipeline.cache

import com.cloudbees.groovy.cps.NonCPS

/**
 * A cross-process advisory lock backed by an atomic `mkdir` (the lock is a
 * directory, and `mkdir` either creates it or fails - no check-then-act race,
 * unlike a `fileExists`-then-`touch` approach). Acquisition (including the
 * wait/retry loop) runs inside a single `sh`, so there is no Groovy-side
 * polling; the lock is released in a `finally`.
 *
 * Two contention strategies, chosen per call via `mode`:
 *  - SKIP_ON_TIMEOUT: wait up to `timeoutSeconds`; if the lock is still held,
 *    give up and skip the action entirely (for non-essential work that is fine
 *    to skip - e.g. a periodic cache refresh).
 *  - BREAK_STALE: wait, but treat a lock older than `timeoutSeconds` as stale
 *    (assumed left behind by a crashed run) and break it, then proceed; the
 *    action always runs (for essential work that must not be skipped).
 *
 * Unix/POSIX only - the acquire loop is a POSIX-sh snippet.
 */
class Lockfile {

    static final String SKIP_ON_TIMEOUT = 'skipOnTimeout'
    static final String BREAK_STALE = 'breakStale'

    private final Object jenkins
    String lockDir
    String label

    Lockfile(Object jenkins, String lockDir, String label = 'lock') {
        this.jenkins = jenkins
        this.lockDir = lockDir
        this.label = label
    }

    /**
     * Runs `action` while holding the lock. Options:
     *  - mode: SKIP_ON_TIMEOUT (default) or BREAK_STALE
     *  - timeoutSeconds: wait-or-stale threshold (default 600)
     * Returns true iff the lock could not be acquired (only possible in
     * SKIP_ON_TIMEOUT mode) and the action was therefore skipped; false when
     * the action ran.
     */
    boolean withLock(Map opts = [:], Closure action) {
        String mode = opts.mode ?: SKIP_ON_TIMEOUT
        int timeoutSeconds = (opts.timeoutSeconds ?: 600) as int
        if (!acquire(mode, timeoutSeconds)) {
            return true
        }
        try {
            action.call()
            return false
        } finally {
            release()
        }
    }

    private boolean acquire(String mode, int timeoutSeconds) {
        return jenkins.sh(label: "acquire ${label} lock", returnStatus: true,
                script: acquireSh(lockDir, label, mode, timeoutSeconds)) == 0
    }

    private void release() {
        jenkins.sh(label: "release ${label} lock",
                script: "rm -rf \"${lockDir}\" 2>/dev/null; echo \"[lock] Released ${label} lock: ${lockDir}\" >&2 || true")
    }

    /**
     * The atomic mkdir-as-mutex acquire loop as a POSIX-sh snippet. Exit 0 =
     * this process now owns the lock dir; exit 1 = SKIP_ON_TIMEOUT gave up.
     * `umask 002` keeps the lock dir group-writable so a shared (group-owned)
     * cache directory can be locked/unlocked by any agent user, not only its
     * creator. `mkdir` is atomic on POSIX filesystems, so it is the mutex - no
     * separate existence check is needed.
     */
    @NonCPS
    static String acquireSh(String lockDir, String label, String mode, int timeoutSeconds) {
        List<String> lines = [
                'umask 002',
                "_LOCK_DIR=\"${lockDir}\"",
                "_LOCK_TIMEOUT=${timeoutSeconds}",
                'mkdir -p "$(dirname "$_LOCK_DIR")"',
                '_start="$(date +%s)"',
                'while ! mkdir "$_LOCK_DIR" 2>/dev/null; do',
                '  _now="$(date +%s)"',
        ]
        if (mode == BREAK_STALE) {
            lines += [
                    '  _mtime="$(stat -c %Y "$_LOCK_DIR" 2>/dev/null || stat -f %m "$_LOCK_DIR" 2>/dev/null || echo "$_now")"',
                    '  if [ "$(( _now - _mtime ))" -ge "$_LOCK_TIMEOUT" ]; then',
                    "    echo \"[lock] ${label} lock '\$_LOCK_DIR' held \$(( _now - _mtime ))s (>= \${_LOCK_TIMEOUT}s); breaking stale lock\" >&2",
                    '    rm -rf "$_LOCK_DIR" 2>/dev/null || true',
                    '    continue',
                    '  fi',
            ]
        } else {
            lines += [
                    '  if [ "$(( _now - _start ))" -ge "$_LOCK_TIMEOUT" ]; then',
                    "    echo \"[lock] Timed out after \${_LOCK_TIMEOUT}s waiting for ${label} lock '\$_LOCK_DIR'\" >&2",
                    '    exit 1',
                    '  fi',
            ]
        }
        lines += [
                "  echo \"[lock] Waiting for ${label} lock '\$_LOCK_DIR'...\" >&2",
                '  sleep 2',
                'done',
                "echo \"[lock] Acquired ${label} lock: \$_LOCK_DIR\" >&2",
        ]
        return lines.join("\n")
    }
}
