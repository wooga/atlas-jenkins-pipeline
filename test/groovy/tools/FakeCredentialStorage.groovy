package tools

class FakeCredentialStorage {

    private final Map<String, Object> keychain
    private FakeEnvironment environment

    FakeCredentialStorage(FakeEnvironment environment) {
        this.environment = environment
        keychain = new HashMap<>()
    }

    def addString(String key, String value) {
        keychain[key] = value
    }
    def addUsernamePassword(String key, String username, String password) {
        keychain[key] = [username, password]
    }

    StringCredentials string(Map contents) {
        return new StringCredentials().with {
            key = contents.credentialsId as String
            variable = contents.variable as String
            return it
        }
    }

    UsernamePasswordCredentials usernamePassword(Map contents) {
        return new UsernamePasswordCredentials().with {
            key = contents.credentialsId as String
            usernameVariable = contents.usernameVariable as String
            passwordVariable = contents.passwordVariable as String
            return it
        }
    }

    def getAt(String name) {
        if(keychain[name] instanceof List) {
            return "${keychain[name][0]}:${keychain[name][1]}"
        }
        return keychain[name].toString()
    }


    //TODO add generated credentials to environment in a similar fashion to withEnv
    def bindCredentials(List creds, Closure operation) {
        Map clsDelegate = [:]
        creds.each {
            if(it instanceof StringCredentials) {
                def strCreds = it as StringCredentials
                clsDelegate[strCreds.variable] = keychain[strCreds.key]
            }
            if(it instanceof UsernamePasswordCredentials) {
                def upCreds = it as UsernamePasswordCredentials
                def secrets = keychain[upCreds.key] as List
                clsDelegate[upCreds.usernameVariable] = secrets?.get(0)
                clsDelegate[upCreds.passwordVariable] = secrets?.get(1)
            }
        }
        operation.setDelegate(clsDelegate)
        environment.runWithEnv(clsDelegate, operation)
    }

    def wipe() {
        keychain.clear()
    }
}

interface Credentials {
    String getKey()
}

class StringCredentials {
    String key
    String variable
}

class UsernamePasswordCredentials {
    String key
    String usernameVariable
    String passwordVariable
}
