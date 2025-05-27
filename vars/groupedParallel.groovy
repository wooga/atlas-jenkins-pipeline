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
    def maxParallel = config.maxParallel as int ?: 3
    def os = config.os ?: "macos"
    def prefix = config.prefix ?: ""
    def checkCreator = CheckCreator.basicCheckCreator(this)
    def platforms = config.platforms as List<Platform>?: config.names.collect {
        namespace -> Platform.simple(namespace, os, config.labels)
    }
    //========Execution========
    for(int i=0; i < platforms.size(); i+=maxParallel) {
        def last = Math.min(i+maxParallel - 1, platforms.size() - 1) as int
        def platformGroup = platforms[i..last]
        def parallelMap = checkCreator.simpleParallel(prefix, platformGroup,
                                                config.parallelAction,
                                                config.parallelCatch?: { throw it },
                                                config.parallelFinally?: { -> })
        echo "Running platforms in parallel: ${ platformGroup.collect {it.name + ": " + it.labels + "\n" } }"
        echo "Parallel map: ${parallelMap}"
        parallel parallelMap
    }
}
