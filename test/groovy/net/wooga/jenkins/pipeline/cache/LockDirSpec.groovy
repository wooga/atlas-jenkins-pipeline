package net.wooga.jenkins.pipeline.cache

import spock.lang.Specification

class LockDirSpec extends Specification {

    def "acquireSh builds an atomic mkdir loop that breaks a stale lock"() {
        when:
        def script = LockDir.acquireSh("/tmp/demo.lock", "demo", 300)

        then:
        script.contains('umask 002')
        script.contains('_LOCK_DIR="/tmp/demo.lock"')
        script.contains('_LOCK_TIMEOUT=300')
        script.contains('while ! mkdir "$_LOCK_DIR" 2>/dev/null; do')
        script.contains('breaking stale lock')
        script.contains('rm -rf "$_LOCK_DIR" 2>/dev/null || true')
        script.contains('Acquired demo lock')
    }

    def "withLock acquires, runs the action, then releases"() {
        given:
        def calls = []
        def jenkins = new Expando()
        jenkins.sh = { Map arg -> calls << arg; null }
        def lock = new LockDir(jenkins, "/tmp/demo.lock", "demo")
        def ran = false

        when:
        lock.withLock { ran = true }

        then:
        ran
        def acquireIdx = calls.findIndexOf { it.script.contains('mkdir "$_LOCK_DIR"') && it.script.contains("/tmp/demo.lock") }
        def releaseIdx = calls.findIndexOf { it.script.contains('rm -rf "/tmp/demo.lock"') && it.script.contains("Released demo lock") }
        acquireIdx >= 0
        releaseIdx >= 0
        acquireIdx < releaseIdx // released after acquired
    }

    def "withLock still releases the lock when the action throws"() {
        given:
        def calls = []
        def jenkins = new Expando()
        jenkins.sh = { Map arg -> calls << arg; null }
        def lock = new LockDir(jenkins, "/tmp/demo.lock", "demo")

        when:
        lock.withLock { throw new RuntimeException("boom") }

        then:
        thrown(RuntimeException)
        calls.any { it.script.contains("Released demo lock") }
    }

    def "withLock returns the action result"() {
        given:
        def jenkins = new Expando()
        jenkins.sh = { Map arg -> null }
        def lock = new LockDir(jenkins, "/tmp/demo.lock", "demo")

        expect:
        lock.withLock { 42 } == 42
    }
}
