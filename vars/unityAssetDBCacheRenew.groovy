def call(String projectName,
         String targetFolder=null,
         long cacheMaxAgeMs = 24 * 60 * 60 * 1000,
         boolean cachePerBranch = true,
         Closure generateAssets) {
    def cache = unityAssetDBCache(projectName, targetFolder, cachePerBranch)
    cache.renewUnityLibraryCache(cacheMaxAgeMs, generateAssets)
}