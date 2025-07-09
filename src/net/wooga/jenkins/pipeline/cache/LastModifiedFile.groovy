package net.wooga.jenkins.pipeline.cache

class LastModifiedFile {

    Object jenkins
    String lastModifiedFile

    LastModifiedFile(Object jenkins, String lastModifiedFile) {
        this.jenkins = jenkins
        this.lastModifiedFile = lastModifiedFile
    }

    def update() {
        jenkins.sh "rm $lastModifiedFile || true"
        jenkins.sh "umask 002 && echo -n '${System.currentTimeMillis()}' >> $lastModifiedFile || true"
        jenkins.sh "chmod g+rw $lastModifiedFile || true"
    }

    boolean fileExists() {
        return jenkins.fileExists(lastModifiedFile)
    }

    Long getLastModifiedMs() {
        if (!jenkins.fileExists(lastModifiedFile)) {
            return 0
        }
        String lastModified = jenkins.readFile(lastModifiedFile)
        return lastModified.isNumber() ? lastModified.toLong() : 0
    }

    long getAgeMs() {
        return System.currentTimeMillis() - getLastModifiedMs()
    }




}
