package net.wooga.jenkins.pipeline.cache

class Cache {

    final String basePath = "/unity-cache/unity-assets-cache"
    String cacheProjectName
    Object jenkins
    boolean perBranch = true

    Cache(Object jenkins, String cacheProjectName, boolean perBranch) {
        this.cacheProjectName = cacheProjectName
        this.jenkins = jenkins
        this.perBranch = perBranch
    }

    def renewProjectCache(String relativePathFolderToBeCached, long cacheMaxAgeMs, String ageTestFile=null, Closure generateAssets) {
        ageTestFile = ageTestFile ?: cacheLocation
        def cacheAge = testCacheAgeMs(ageTestFile)
        if(cacheAge < cacheMaxAgeMs) {
            jenkins.echo "Unity Assets already cached ${cacheAge/1000/60/60} hours ago skipping cache stage."
            return
        }
        generateAssets()
        updateCache(relativePathFolderToBeCached)
    }

    def isFolder(String path) {
        jenkins.sh(script: "test -d '$path'", returnStatus: true) == 0
    }

    boolean updateCache(String relativePathFolderToBeCached) {
        if(!isFolder(relativePathFolderToBeCached)) {
            jenkins.echo "The path '$relativePathFolderToBeCached' is not a folder. Please provide a valid folder path to cache."
            return false
        }
        def pathInCache = "$cacheLocation/$relativePathFolderToBeCached"
        if(!jenkins.fileExists(pathInCache)) {
            jenkins.sh "umask 002 && mkdir -p $pathInCache || true"
            return tarCopy(jenkins, ".", "$relativePathFolderToBeCached/", cacheLocation)
        } else {
            return rsync(jenkins, "$relativePathFolderToBeCached/", "$cacheLocation/$relativePathFolderToBeCached")
        }
    }

    def hasFiles(List<String> files) {
        for (String file : files) {
            if (!jenkins.fileExists("$cacheLocation/$file")) {
                return false
            }
        }
        return true
    }

    boolean loadFromCache(String relativePathFolderToBeLoaded) {
        if(!jenkins.fileExists(cacheLocation)) {
            jenkins.echo "No cache location found: ${cacheLocation}, skiping cache load"
            return false
        }
        def pathInCache = "$cacheLocation/$relativePathFolderToBeLoaded"
        if(isFolder(pathInCache)) {
            return loadFolderFromCache(relativePathFolderToBeLoaded)
        } else {
            return loadFileFromCache(relativePathFolderToBeLoaded)
        }
    }

    boolean loadFolderFromCache(String relativePathFolder) {
        if(!jenkins.fileExists(relativePathFolder)) {
            return tarCopy(jenkins, cacheLocation, "$relativePathFolder/", ".")
        } else {
            return rsync(jenkins, "$cacheLocation/$relativePathFolder/", "$relativePathFolder")
        }
    }

    boolean loadFileFromCache(String relativePathFile) {
        return rsync(jenkins, "$cacheLocation/$relativePathFile", "$relativePathFile")
    }

    String getCachePath() {
        if (perBranch) {
            return "$cacheProjectName/$jenkins.env.BRANCH_NAME".toString()
        } else {
            return cacheProjectName
        }
    }

    String getCacheLocation() {
        return "$basePath/$cachePath".toString()
    }

    long testCacheAgeMs(String ageTestFile) {
        def ageFile = "$cacheLocation/$ageTestFile".toString()
        if(jenkins.fileExists(ageFile)) {
            def lastModified = (jenkins.sh(
                    script: """
        if [[ \$(uname) == "Darwin" ]]; then
            stat -f %m '${ageFile}'
        else
            stat -c %Y '${ageFile}'
        fi
    """, returnStdout: true
            ).trim() as Long) * 1000
            return System.currentTimeMillis() - lastModified
        }
        return 0x7fffffffffffffffL //Long.MAX_VALUE
    }

    static def tarCopy(Object j, String baseDir, String source, String destination) {
        //as unintuitive as it may sound, using gtar is __much__ faster than rsync to sync directories with a large amount of files.
        if (j.fileExists("$baseDir/$source")) {
            j.sh "umask 002 && gtar --atime-preserve='replace' --mode=u+rwxs,g+rwxs --directory $baseDir -c $source | " +
                    "gtar --atime-preserve='replace' --mode=u+rwxs,g+rwxs --group=nfs_share -xf - -C $destination"
            return true
        }
        return false
    }

    static def rsync(Object j, String source, String destination) {
        if (j.fileExists(source)) {
            if(destination.contains("/unity-cache/")) {
                j.sh "rsync --archive --delete --chmod u+rwxs,g+rwxs,o+r --groupmap *:nfs_share $source $destination"
            } else {
                j.sh "rsync --archive --delete --chmod u+rwx,g+rx,o+rx --chown jenkins_agent:staff $source $destination"
            }
            return true
        }
        return false
    }

}
