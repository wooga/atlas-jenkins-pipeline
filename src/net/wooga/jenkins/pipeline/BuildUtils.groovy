package net.wooga.jenkins.pipeline

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

class BuildVersion {
    final String version
    final Boolean optional

    BuildVersion(version, optional) {
        this.version = version
        this.optional = optional
    }

    @Override
    @NonCPS
    String toString() {
        return version
    }
}

/**
 * For each entry in a versions map, if they are not in the standard format
 * we process them into the newer format that supports optional arguments per version
 * (such as if it's optional, etc).
 * Example: '3001.x' -> [ version : '3001.x', optional : false ]
 * @param versions
 * @return
 */
def parseVersions(versions) {

    final versionKey = "version"
    final optionalKey = "optional"

    versions.collect { v ->

        if (v instanceof Closure) {
            v = v.call()
        }

        if (v instanceof Map) {
            if (!v.containsKey(versionKey)) {
                throw new Exception("Entry ${v} does not contain ${versionKey}")
            }
            if (!v.containsKey(optionalKey)) {
                throw new Exception("Entry ${v} does not contain ${optionalKey}")
            }
            return new BuildVersion(v[versionKey], v[optionalKey])
        }

        return new BuildVersion(v.toString(), false)
    }
}
