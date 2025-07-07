import net.wooga.jenkins.pipeline.cache.Cache

def call(String projectName, String unityProjectFolder=null, boolean cachePerBranch = true) {
    unityProjectFolder = unityProjectFolder ?: projectName
    def basePath = "/unity-cache/unity-assets-cache"
    def cache = new Cache(this, basePath, projectName, cachePerBranch)
    def libraryFolder = "$unityProjectFolder/Library"
    def assetArtifacts = "$libraryFolder/Artifacts"
    def artifactDB = "$libraryFolder/ArtifactDB"
    def sourceAssetsDB = "$libraryFolder/SourceAssetDB"

    return [
            renewUnityLibraryCache   : { long cacheMaxAgeMs, Closure generateAssets ->
                cache.renewProjectCache(libraryFolder, cacheMaxAgeMs, generateAssets)
            },
            getCacheLocation         : { ->
                cache.getCacheLocation()
            },
            loadUnityAssetDBFromCache: { ->
                if(cache.hasFiles([assetArtifacts, artifactDB, sourceAssetsDB])) {
                    cache.loadFromCache(assetArtifacts)
                    cache.loadFromCache(artifactDB)
                    cache.loadFromCache(sourceAssetsDB)
                }
            }
    ]
}
