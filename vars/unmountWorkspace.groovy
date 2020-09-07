def call(Map config = [:]) {
    def mountPoint = config.mountPoint ?: pwd
    sh "hdiutil detach -force ${mountPoint}"
}