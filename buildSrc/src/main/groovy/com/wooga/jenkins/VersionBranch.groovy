package com.wooga.jenkins

import org.ajoberstar.grgit.Branch
import org.ajoberstar.grgit.Grgit

class VersionBranch {

    private Grgit git
    private String remote
    private boolean dryRun

    VersionBranch(Grgit git, String remote="origin", boolean dryRun=false) {
        this.git = git
        this.remote = remote
        this.dryRun = dryRun
    }

    def applyMajor(Version version) {
        Branch base = git.branch.current()
        updateVersionBranch(version.genericMajorName(), base)
    }

    def applyMinor(Version version) {
        Branch base = git.branch.current()
        updateVersionBranch(version.genericMinorName(), base)
    }

    private void updateVersionBranch(String branchName, Branch base) {
        def versionBranch = git.branch.list().find{it.name == branchName}
        git.checkout(branch: branchName, createBranch: versionBranch == null)
        try {
            Branch newBranch = git.branch.current()
            git.merge(head: base.fullName, message: "Merge branch ${base.name}")
            git.push(remote: remote, refsOrSpecs: [newBranch.fullName], dryRun: dryRun)
        } finally {
            git.checkout(branch: base.fullName)
        }

    }

}
