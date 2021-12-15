package net.wooga.jenkins.pipeline.config

class UnityVersionFile {

    private final String filePath
    private final String contents
    final String editorVersion

    static UnityVersionFile searchFileInJenkins(Object jenkins, String baseDir, String fileName) {
        def files = jenkins.findFiles(glob: "${baseDir}/**/${fileName}")
        if(files && files.size() > 0) {
            def filePath = files[0].toString() as String
            return fromJenkins(jenkins, filePath)
        }
        return null
    }

    static UnityVersionFile fromJenkins(Object jenkins, String filePath) {
        def content = jenkins.readFile(filePath) as String
        return new UnityVersionFile(filePath, content)
    }

    UnityVersionFile(String filePath, String contents) {
        this.filePath = filePath
        this.contents = contents
        this.editorVersion = this.contents.split("\n").find {
            if(!it.trim().empty) {
                def parts = it.trim().split(":")
                def normalizedKey = parts[0].trim()
                return normalizedKey == "m_EditorVersion"
            }
            return false
        }.split(":")[1].trim()
    }
}
