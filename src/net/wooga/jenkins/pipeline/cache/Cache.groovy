package net.wooga.jenkins.pipeline.cache

import com.cloudbees.groovy.cps.NonCPS

class Cache {

    String basePath = "/unity-cache/unity-assets-cache"
    Object jenkins
    String cacheProjectName
    boolean perBranch = true

    Lockfile renewLock
    LastModifiedFile lastModified


    Cache(Object jenkins, String basePath, String cacheProjectName, boolean perBranch) {
        this.jenkins = jenkins
        this.basePath = basePath
        this.cacheProjectName = cacheProjectName
        this.perBranch = perBranch
        //cant call getCacheLocation due to CPS-transformation limitations
        def cacheLocation = "$basePath/$cacheProjectName".toString()
        if (perBranch) {
             cacheLocation = "$basePath/$cacheProjectName/$jenkins.env.BRANCH_NAME"
        }
        this.renewLock = new Lockfile(jenkins, "$cacheLocation/.renew_lock")
        this.lastModified = new LastModifiedFile(jenkins, "$cacheLocation/.last_renew")
    }

    boolean renewProjectCache(String relativePathFolderToBeCached, long cacheMaxAgeMs, Closure generateAssets) {
        def success = false
        def timeout = renewLock.withLock(10) {
            def cacheAge = lastModified.ageMs
            if (lastModified.ageMs < cacheMaxAgeMs) {
                jenkins.echo "Unity Assets already cached ${cacheAge / 1000 / 60 / 60} hours ago skipping cache stage."
                return
            }
            generateAssets()
            success = updateCache(relativePathFolderToBeCached)
            if(success) {
                lastModified.update()
            }
        }
        if(timeout) {
            jenkins.echo "Timeout while waiting for cache lock (${renewLock.lockFile}), skipping cache update."
        }
        return success
    }

    def isFolder(String path) {
        jenkins.sh(script: "test -d '$path'", returnStatus: true) == 0
    }

    boolean updateCache(String relativePathFolderToBeCached) {
        if (!isFolder(relativePathFolderToBeCached)) {
            jenkins.echo "The path '$relativePathFolderToBeCached' is not a folder. Please provide a valid folder path to cache."
            return false
        }
        def pathInCache = "$cacheLocation/$relativePathFolderToBeCached"
        jenkins.sh "rm -rf $pathInCache || true"
        jenkins.sh "umask 002 && mkdir -p $pathInCache || true"
        return tarCopy(jenkins, ".", "$relativePathFolderToBeCached/", cacheLocation)
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
        if (!lastModified.fileExists()) {
            jenkins.echo "No cache location found: ${cacheLocation}(${lastModified.lastModifiedFile}), skiping cache load"
            return false
        }
        def pathInCache = "$cacheLocation/$relativePathFolderToBeLoaded"
        if (isFolder(pathInCache)) {
            return loadFolderFromCache(relativePathFolderToBeLoaded)
        } else {
            return loadFileFromCache(relativePathFolderToBeLoaded)
        }
    }

    boolean loadFolderFromCache(String relativePathFolder) {
        if (!jenkins.fileExists(relativePathFolder)) {
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

    static def tarCopy(Object j, String baseDir, String source, String destination) {
        //as unintuitive as it may sound, using gtar is __much__ faster than rsync to sync directories with a large amount of files.
        if (j.fileExists("$baseDir/$source")) {
            def status = j.sh script: "umask 002 && gtar --atime-preserve='replace' --mode=u+rwxs,g+rwxs --directory $baseDir -c $source | " +
                    "gtar --atime-preserve='replace' --mode=u+rwxs,g+rwxs --group=nfs_share -xf - -C $destination", returnStatus: true
            if (status != 0) {
                j.echo "Failed (exit code $status) to copy files from $source to $destination with gtar, removing copied files"
                j.sh "rm -rf $destination || true"
                return false
            }
            return true
        }
        return false
    }

    static def rsync(Object j, String source, String destination) {
        if (j.fileExists(source)) {
            if (destination.contains("/unity-cache/")) {
                j.sh "rsync --archive --delete --chmod u+rwxs,g+rwxs,o+r --groupmap *:nfs_share $source $destination"
            } else {
                j.sh "rsync --archive --delete --chmod u+rwx,g+rx,o+rx --chown jenkins_agent:staff $source $destination"
            }
            return true
        }
        return false
    }
}
