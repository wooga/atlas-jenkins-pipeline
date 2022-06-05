package net.wooga.jenkins.pipeline.test.specifications.fakes

class WithCredentials {

    final FakeCredentialStorage credStorage
    final FakeEnvironment environment

    static def bindCredentials(FakeCredentialStorage credStorage, FakeEnvironment environment, List creds, Closure operation) {
        return new WithCredentials(credStorage, environment).bindCredentials(creds, operation)
    }

    WithCredentials(FakeCredentialStorage credStorage, FakeEnvironment environment) {
        this.credStorage = credStorage
        this.environment =  environment

    }

    static StringCredentials string(Map contents) {
        return new StringCredentials().with {
            key = contents.credentialsId as String
            variable = contents.variable as String
            return it
        }
    }

    static UsernamePasswordCredentials usernamePassword(Map contents) {
        return new UsernamePasswordCredentials().with {
            key = contents.credentialsId as String
            usernameVariable = contents.usernameVariable as String
            passwordVariable = contents.passwordVariable as String
            return it
        }
    }

    static StringCredentials usernameColonPassword(Map contents) {
        return string(contents)
    }


    def bindCredentials(List creds, Closure operation) {
        Map clsDelegate = [:]
        creds.each {
            if(it instanceof StringCredentials) {
                def strCreds = it as StringCredentials
                clsDelegate[strCreds.variable] = credStorage.getSecretValueAsString(strCreds.key)
            }
            if(it instanceof UsernamePasswordCredentials) {
                def upCreds = it as UsernamePasswordCredentials
                def secrets = credStorage.getUsernamePassword(upCreds.key)
                clsDelegate[upCreds.usernameVariable] = secrets[0]
                clsDelegate[upCreds.passwordVariable] = secrets[1]
            }
        }
        operation.setDelegate(clsDelegate)
        return environment.runWithEnv(clsDelegate, operation)
    }

    static class StringCredentials {
        String key
        String variable
    }

    static class UsernamePasswordCredentials {
        String key
        String usernameVariable
        String passwordVariable
    }


}
