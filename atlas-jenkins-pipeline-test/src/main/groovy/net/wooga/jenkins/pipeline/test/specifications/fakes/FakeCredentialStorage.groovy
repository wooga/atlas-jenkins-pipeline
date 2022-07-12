package net.wooga.jenkins.pipeline.test.specifications.fakes

class FakeCredentialStorage {

    private final Map<String, Object> keychain

    FakeCredentialStorage() {
        keychain = new HashMap<>()
    }

    def addString(String key, String value) {
        keychain[key] = value
    }

    def addUsernamePassword(String key, String username, String password) {
        keychain[key] = [username, password]
    }

    String[] getUsernamePassword(String name) {
        if(keychain[name] instanceof List) {
            return keychain[name] as String[]
        }
        return getSecretValueAsString(name)
    }

    String getAt(String name) {
        return getSecretValueAsString(name)
    }

    String getSecretValueAsString(String name) {
        if(keychain[name] instanceof List) {
            return (keychain[name] as List).join(":")
        }
        return keychain[name].toString()
    }

    def wipe() {
        keychain.clear()
    }
}



