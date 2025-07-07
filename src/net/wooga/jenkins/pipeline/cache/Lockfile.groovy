package net.wooga.jenkins.pipeline.cache

class Lockfile {

    private final Object jenkins
    String lockFile

    Lockfile(Object jenkins, String lockFile) {
        this.jenkins = jenkins
        this.lockFile = lockFile
    }

    boolean withLock(int timeoutMinutes = 10, int pollingIntervalSeconds = 10, Closure action) {
        if (!waitFor(timeoutMinutes, pollingIntervalSeconds)) {
            return true
        }
        try {
            create()
            action.call()
            return false
        } finally {
            deleteLock()
        }
    }

    void create() {
        jenkins.sh "umask 002 && mkdir -p \$(dirname \"$lockFile\")"
        jenkins.sh "umask 002 && touch \"$lockFile\" && chmod g+rw \"$lockFile\" || true"
    }

    void deleteLock() {
        jenkins.sh "rm -f $lockFile || true"
    }

    boolean waitFor(int timeoutMinutes = 10, int pollingIntervalSeconds = 10) {
        def startTime = System.currentTimeMillis()
        def timeoutMs = timeoutMinutes * 60 * 1000

        while (jenkins.fileExists(lockFile)) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false
            }
            jenkins.echo "Lock file $lockFile exists, waiting for it to be released..."
            jenkins.sleep pollingIntervalSeconds
        }
        return true
    }
}
