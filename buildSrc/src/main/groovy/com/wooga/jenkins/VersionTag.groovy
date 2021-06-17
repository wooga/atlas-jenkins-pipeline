package com.wooga.jenkins

import org.ajoberstar.grgit.Grgit

class VersionTag {

    private Grgit git
    private String remote
    private boolean dryRun;

    VersionTag(Grgit git, String remote, boolean dryRun=false) {
        this.git = git
        this.remote = remote
        this.dryRun = dryRun
    }

    def applyPatch(Version version) {
        String tagName = version.fullName()
        git.tag.add(name: tagName, annotate: true, message: tagName)
        git.push(remote: remote, refsOrSpecs: [tagName], dryRun: dryRun)
    }

}
