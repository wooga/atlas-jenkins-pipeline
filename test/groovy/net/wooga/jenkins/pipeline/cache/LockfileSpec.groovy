package net.wooga.jenkins.pipeline.cache

import spock.lang.Specification

class LockfileSpec extends Specification {

    static Expando fakeJenkins(int acquireStatus) {
        def jenkins = new Expando()
        jenkins.calls = [sh: []]
        // returnStatus calls (Lockfile.acquire) return the configured status;
        // release (no returnStatus) returns null.
        jenkins.sh = { Map arg -> jenkins.calls.sh << arg; arg.returnStatus ? acquireStatus : null }
        return jenkins
    }

    def "acquireSh builds an atomic mkdir loop that breaks a stale lock in BREAK_STALE mode"() {
        when:
        def script = Lockfile.acquireSh("/tmp/demo.lock", "demo", Lockfile.BREAK_STALE, 300)

        then:
        script.contains('umask 002')
        script.contains('_LOCK_DIR="/tmp/demo.lock"')
        script.contains('_LOCK_TIMEOUT=300')
        script.contains('while ! mkdir "$_LOCK_DIR" 2>/dev/null; do')
        script.contains('breaking stale lock')
        script.contains('rm -rf "$_LOCK_DIR" 2>/dev/null || true')
        script.contains('Acquired demo lock')
        !script.contains('Timed out')
    }

    def "acquireSh gives up instead of breaking the lock in SKIP_ON_TIMEOUT mode"() {
        when:
        def script = Lockfile.acquireSh("/tmp/demo.lock", "demo", Lockfile.SKIP_ON_TIMEOUT, 60)

        then:
        script.contains('while ! mkdir "$_LOCK_DIR" 2>/dev/null; do')
        script.contains('Timed out after ${_LOCK_TIMEOUT}s')
        script.contains('exit 1')
        !script.contains('breaking stale lock')
    }

    def "withLock runs the action and releases the lock when acquired"() {
        given:
        def jenkins = fakeJenkins(0) // acquire succeeds
        def lock = new Lockfile(jenkins, "/tmp/demo.lock", "demo")
        def ran = false

        when:
        def skipped = lock.withLock(mode: Lockfile.BREAK_STALE, timeoutSeconds: 5) { ran = true }

        then:
        !skipped
        ran
        jenkins.calls.sh.any { it.returnStatus && it.script.contains('mkdir "$_LOCK_DIR"') } // acquire
        jenkins.calls.sh.any { it.script.contains('rm -rf "/tmp/demo.lock"') && it.script.contains("Released demo lock") } // release
    }

    def "withLock skips the action and does not release when acquisition times out"() {
        given:
        def jenkins = fakeJenkins(1) // acquire times out / fails
        def lock = new Lockfile(jenkins, "/tmp/demo.lock", "demo")
        def ran = false

        when:
        def skipped = lock.withLock(mode: Lockfile.SKIP_ON_TIMEOUT, timeoutSeconds: 1) { ran = true }

        then:
        skipped
        !ran
        // we never owned the lock, so we must not delete it (another holder owns it)
        !jenkins.calls.sh.any { it.script.contains('rm -rf') }
    }

    def "withLock defaults to SKIP_ON_TIMEOUT with a 600s timeout"() {
        given:
        def jenkins = fakeJenkins(0)
        def lock = new Lockfile(jenkins, "/tmp/demo.lock", "demo")

        when:
        lock.withLock { }

        then:
        def acquire = jenkins.calls.sh.find { it.returnStatus }
        acquire.script.contains('_LOCK_TIMEOUT=600')
        acquire.script.contains('Timed out after') // SKIP_ON_TIMEOUT loop
        !acquire.script.contains('breaking stale lock')
    }
}
