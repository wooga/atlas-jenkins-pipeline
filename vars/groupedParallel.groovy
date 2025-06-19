import net.wooga.jenkins.pipeline.check.CheckCreator
import net.wooga.jenkins.pipeline.config.Platform

def call(Map config) {
    //========Validation========
    if(!config.parallelAction) {
        throw new Exception("groupedParallel requires parallelAction closure to be set")
    }
    if(!config.platforms && !config.names || !(config.names instanceof List)) {
        throw new Exception("groupedParallel requires names list to be set and a List")
    }
    if(!config.platforms && !config.labels) {
        throw new Exception("groupedParallel requires labels string to be set")
    }
    //========Parameters========
    def labels = config.labels as String
    def os = config.os as String ?: "macos"
    def prefix = config.prefix as String ?: ""
    def checkoutDir = config.checkoutDir as String ?: "."
    def maxParallel = config.maxParallel as int ?: 3
    def checkCreator = CheckCreator.basicCheckCreator(this, Integer.parseInt(env.BUILD_NUMBER))
    def platforms = config.platforms as List<Platform>?: config.names.collect {
        String namespace -> Platform.simple(namespace, os, labels, checkoutDir)
    }
    //========Execution========
    for(int i=0; i < platforms.size(); i+=maxParallel) {
        def last = Math.min(i+maxParallel - 1, platforms.size() - 1) as int
        def platformGroup = platforms[i..last]
        echo "Constructing parallels: ${ platformGroup.collect {it.name + ": " + it.labels + "\n" } }"
        def parallelMap = checkCreator.simpleParallel(prefix, platformGroup,
                                                config.parallelAction as Closure,
                                                config.parallelCatch as Closure?: { throw it },
                                                config.parallelFinally as Closure?: { -> })
        echo "Running parallels: ${ platformGroup.collect {it.name + "\n" } }"
        parallel parallelMap
    }
}
