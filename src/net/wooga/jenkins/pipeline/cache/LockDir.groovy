package net.wooga.jenkins.pipeline.cache

import com.cloudbees.groovy.cps.NonCPS

/**
 * A cross-process advisory lock backed by an atomic `mkdir` (the lock is a
 * directory, and `mkdir` either creates it or fails - no check-then-act race,
 * unlike a `fileExists`-then-`touch` approach). The whole acquire/wait/retry
 * loop runs inside a single `sh`, so there is no Groovy-side polling; the lock
 * is released in a `finally`. A lock held past `staleTimeoutSeconds` (assumed
 * left behind by a crashed run) is broken and acquisition proceeds, so a dead
 * run can't wedge the lock indefinitely.
 *
 * Unix/POSIX only. Deliberately separate from {@link Lockfile} (the
 * pre-existing, non-atomic touch-file lock used for low-contention cache
 * renewal) so this atomic variant can be adopted where true concurrency
 * matters without changing that lock's behavior.
 */
class LockDir {

    private final Object jenkins
    String lockDir
    String label
    int staleTimeoutSeconds

    LockDir(Object jenkins, String lockDir, String label = 'lock', int staleTimeoutSeconds = 300) {
        this.jenkins = jenkins
        this.lockDir = lockDir
        this.label = label
        this.staleTimeoutSeconds = staleTimeoutSeconds
    }

    /**
     * Acquires the lock (breaking a stale one if needed), runs `action`, then
     * releases the lock even if `action` throws. Returns whatever `action`
     * returns.
     */
    def withLock(Closure action) {
        acquire()
        try {
            return action.call()
        } finally {
            release()
        }
    }

    private void acquire() {
        jenkins.sh(label: "acquire ${label} lock", script: acquireSh(lockDir, label, staleTimeoutSeconds))
    }

    private void release() {
        jenkins.sh(label: "release ${label} lock",
                script: "rm -rf \"${lockDir}\" 2>/dev/null; echo \"[lock] Released ${label} lock: ${lockDir}\" >&2 || true")
    }

    /**
     * The atomic mkdir-as-mutex acquire loop as a POSIX-sh snippet. `umask 002`
     * keeps the lock dir group-writable (so a shared, group-owned cache can be
     * locked by any agent user, not only its creator). `mkdir` is atomic on
     * POSIX filesystems, so it is the mutex - no separate existence check.
     */
    @NonCPS
    static String acquireSh(String lockDir, String label, int staleTimeoutSeconds) {
        return [
                'umask 002',
                "_LOCK_DIR=\"${lockDir}\"",
                "_LOCK_TIMEOUT=${staleTimeoutSeconds}",
                'mkdir -p "$(dirname "$_LOCK_DIR")"',
                'while ! mkdir "$_LOCK_DIR" 2>/dev/null; do',
                '  _now="$(date +%s)"',
                '  _mtime="$(stat -c %Y "$_LOCK_DIR" 2>/dev/null || stat -f %m "$_LOCK_DIR" 2>/dev/null || echo "$_now")"',
                '  if [ "$(( _now - _mtime ))" -ge "$_LOCK_TIMEOUT" ]; then',
                "    echo \"[lock] ${label} lock '\$_LOCK_DIR' held \$(( _now - _mtime ))s (>= \${_LOCK_TIMEOUT}s); breaking stale lock\" >&2",
                '    rm -rf "$_LOCK_DIR" 2>/dev/null || true',
                '    continue',
                '  fi',
                "  echo \"[lock] Waiting for ${label} lock '\$_LOCK_DIR'...\" >&2",
                '  sleep 2',
                'done',
                "echo \"[lock] Acquired ${label} lock: \$_LOCK_DIR\" >&2",
        ].join("\n")
    }
}
