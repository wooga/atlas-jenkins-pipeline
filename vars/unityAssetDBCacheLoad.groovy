def call(String projectName, String targetFolder=null, boolean cachePerBranch = true) {
    def cache = unityAssetDBCache(projectName, targetFolder, cachePerBranch)
    cache.loadUnityAssetDBFromCache()
}
