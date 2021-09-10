package net.wooga.jenkins.pipeline

/**
 * For each entry in a versions map, if they are not in the standard format
 * we process them into the newer format that supports optional arguments per version
 * (such as if it's optional, etc).
 * Example: '3001.x' -> [ version : '3001.x', optional : false ]
 * @param versions
 * @return
 */
def parseVersions(versions) {
    return versions.collect { v -> BuildVersion.parse(v) }
}
