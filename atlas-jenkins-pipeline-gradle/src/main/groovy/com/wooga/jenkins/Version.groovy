package com.wooga.jenkins

    import org.ajoberstar.grgit.Tag

enum UpdateType { MAJOR, MINOR, PATCH }

class Version implements Comparable<Version>{

    private int major
    private int minor
    private int patch

    static Optional<Version> currentFromTags(List<Tag> tags) {
        try {
            return Optional.ofNullable(tags.findAll {tag ->
                tag.name.split("\\.").length == 3 &&
                tag.name.split("\\.").each {it.isInteger() }
            }.collect {tag -> new Version(tag.name)}.sort().last())
        } catch(NoSuchElementException _) {
            return Optional.empty()
        }
    }

    static Version newFromType(UpdateType updateType) {
        return new Version(0,0,0).update(updateType)
    }

    Version(int major, int minor, int patch) {
        this.major = major
        this.minor = minor
        this.patch = patch
    }

    private Version(String versionStr) {
        String[] verParts = versionStr.replace("v", "").split("\\.")
        this.major = verParts[0] as int
        this.minor = verParts[1] as int
        this.patch = verParts[2] as int
    }

    String genericMajorName() {
        return "${major}.x"
    }

    String genericMinorName() {
        return "${major}.${minor}.x"
    }

    String fullName() {
        return "${major}.${minor}.${patch}"
    }

    Version update(UpdateType updateType) {
        switch (updateType) {
            case UpdateType.MAJOR:
                return this.majorUpdate()
            case UpdateType.MINOR:
                return this.minorUpdate()
            case UpdateType.PATCH:
                return this.patchUpdate()
        }
        throw new IllegalArgumentException("invalid UpdateType: ${updateType}")
    }

    Version majorUpdate() {
        return new Version(major+1,0, 0)
    }

    Version minorUpdate() {
        return new Version(major, minor+1, 0)
    }

    Version patchUpdate() {
        return new Version(major, minor, patch+1)
    }

    int getMajor() {
        return major
    }

    int getMinor() {
        return minor
    }

    int getPatch() {
        return patch
    }

    @Override
    int compareTo(Version version) {
        if(this.major != version.major) {
            return this.major <=> version.major
        }
        if(this.minor != version.minor) {
            return this.minor <=> version.minor
        }
        if(this.patch != version.patch) {
            return this.patch <=> version.patch
        }
        return 0
    }
}
