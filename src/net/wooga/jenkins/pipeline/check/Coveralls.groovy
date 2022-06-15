package net.wooga.jenkins.pipeline.check

class Coveralls {

    final Object jenkins
    final String token

    Coveralls(Object jenkins, String token) {
        this.jenkins = jenkins
        this.token = token
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        Coveralls coveralls = (Coveralls) o

        if (jenkins != coveralls.jenkins) return false
        if (token != coveralls.token) return false

        return true
    }

    int hashCode() {
        int result
        result = (jenkins != null ? jenkins.hashCode() : 0)
        result = 31 * result + (token != null ? token.hashCode() : 0)
        return result
    }
}
