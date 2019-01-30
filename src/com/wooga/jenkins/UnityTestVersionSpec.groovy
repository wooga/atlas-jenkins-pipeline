package com.wooga.jenkins

class UnityTestVersionSpec {
    String versionReq
    Boolean strictVersion
    Boolean optional
    UnityReleaseType releaseType

    UnityTestVersionSpec(String versionReq) {
        this.versionReq = versionReq
        this.strictVersion = false
        this.optional = false
        this.releaseType = UnityReleaseType.FINAL
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (!(o instanceof UnityTestVersionSpec)) return false

        UnityTestVersionSpec that = (UnityTestVersionSpec) o

        if (optional != that.optional) return false
        if (releaseType != that.releaseType) return false
        if (strictVersion != that.strictVersion) return false
        if (versionReq != that.versionReq) return false

        return true
    }

    int hashCode() {
        int result
        result = (versionReq != null ? versionReq.hashCode() : 0)
        result = 31 * result + (strictVersion != null ? strictVersion.hashCode() : 0)
        result = 31 * result + (optional != null ? optional.hashCode() : 0)
        result = 31 * result + (releaseType != null ? releaseType.hashCode() : 0)
        return result
    }
}
