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

    private Optional<Branch> getLocalBranchWithRemote(String branchName) {
        return Optional.ofNullable(
                git.branch.list(mode: "LOCAL").find{Branch it ->
                    it.name == branchName &&
                    it.trackingBranch != null &&
                    it.trackingBranch.name.endsWith(branchName)
                }
        )
    }

    private void updateVersionBranch(String branchName, Branch base) {
        def targetBranch = getLocalBranchWithRemote(branchName).orElseGet{
            createLocalBranchFromRemote(branchName).orElseGet {
                getOrCreateLocalBranch(branchName, base)
            }
        }
        git.checkout(branch: targetBranch.name)
        try {
            git.merge(head: base.fullName, message: "Merge branch ${base.name}")
            git.push(remote: remote,
                    refsOrSpecs: ["${targetBranch.name}"],
                    dryRun: dryRun)
        } finally {
            git.checkout(branch: base.fullName)
        }

    }

    private Optional<Branch> createLocalBranchFromRemote(String branchName) {
        def remoteBranch = Optional.ofNullable(
                git.branch.list(mode: "REMOTE").find { branch -> branch.name.endsWith("${remote}/${branchName}") }
        )
        return remoteBranch.map{ Branch remote ->
                git.branch.add(name: branchName, startPoint: remote.fullName, mode: "TRACK")
        }
    }

    private Branch getOrCreateLocalBranch(String branchName, Branch base=git.branch.current()) {
        return Optional.ofNullable(
                git.branch.list(mode:"LOCAL").find{it.name == branchName} as Branch)
                .orElseGet {
                    git.branch.add(name: branchName, startPoint: base.fullName)
                }
    }
}
