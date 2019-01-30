package com.wooga.jenkins

import groovy.json.JsonException
import groovy.json.JsonSlurper

class UnityTestVersionSpecResolver {

    private final List<UnityTestVersionSpec> versionSpecs = new ArrayList<UnityTestVersionSpec>()

    UnityTestVersionSpecResolver(List<UnityTestVersionSpec> versionReqs) {
        this.versionSpecs.addAll(versionReqs)
    }

    void requestVersion(UnityTestVersionSpec versionReq) {
        versionSpecs.add(versionReq)
    }

    void requestVersions(List<UnityTestVersionSpec> versionReqs) {
        this.versionSpecs.addAll(versionReqs)
    }

    static List<UnityTestVersionSpec> resolve(List<UnityTestVersionSpec> versions) {
        new UnityTestVersionSpecResolver(versions).resolveVersions()
    }

    List<UnityTestVersionSpec> resolveVersions() {
        List<UnityTestVersionSpec> resolvedVersions = new ArrayList<UnityTestVersionSpec>()
        def json = new JsonSlurper()
        for (UnityTestVersionSpec spec in versionSpecs) {
            if(!spec.getStrictVersion()) {
                def url =  new URL("https://unity-versions-service-pr-3.herokuapp.com/version/${spec.versionReq}/${spec.releaseType.toString()}")
                try {
                    String version = json.parse(url).toString()
                    UnityTestVersionSpec newSpec = new UnityTestVersionSpec(version)
                    newSpec.setStrictVersion(true)
                    if(!resolvedVersions.contains(newSpec)) {
                        resolvedVersions.add(newSpec)
                    }

                } catch(JsonException _e) {
                    println("version doesn't exist")
                }
            } else {
                if(!resolvedVersions.contains(spec)) {
                    resolvedVersions.add(spec)
                }
            }
        }

        return resolvedVersions
    }
}
