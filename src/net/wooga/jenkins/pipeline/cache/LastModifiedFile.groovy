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
        jenkins.sh "umask 002 && echo '${System.currentTimeMillis()}' >> $lastModifiedFile || true"
        jenkins.sh "chmod g+rw $lastModifiedFile || true"
    }

    boolean fileExists() {
        return jenkins.fileExists(lastModifiedFile)
    }

    Long getLastModifiedMs() {
        if (!jenkins.fileExists(lastModifiedFile)) {
            return 0
        }
        def lastModifiedLines = jenkins.readFile(lastModifiedFile).readLines()
        def lastModified = lastModifiedLines.find { line -> line.isNumber() || line.split(" ").any { token -> token.isNumber() }} ?: "0"
        if(lastModified.isNumber()) {
            return lastModified.toLong()
        } else {
            def numberToken = lastModified.split(" ").find { token -> token.isNumber() }
            return numberToken ? numberToken.toLong() : 0
        }
    }

    long getAgeMs() {
        return System.currentTimeMillis() - getLastModifiedMs()
    }




}
