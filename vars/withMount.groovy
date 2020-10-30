def call(Map config = [:], block) {
    def mountPoint = config.mountPoint ?: pwd
    createAndMountWorkspace(config)
    dir(mountPoint, block)
    unmountWorkspace(config)
}