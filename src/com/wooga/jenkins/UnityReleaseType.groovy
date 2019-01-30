package com.wooga.jenkins

enum UnityReleaseType {
    ALPHA("alpha"),
    BETA("beta"),
    PATCH("patch"),
    FINAL("final")

    final String value

    private UnityReleaseType(String value) {
        this.value = value
    }

    @Override
    String toString() {
        return value
    }
}