#!/usr/bin/env groovy

def call(String credentialsId, Closure closure) {
  withCredentials([string(credentialsId: credentialsId, variable: 'SECRET')]) {
  	withSecretEnv([
            	[var: 'UNITY_AUTHENTICATION_USERNAME', password: jsonKey(SECRET, 'username')],
            	[var: 'UNITY_PWD', password: jsonKey(SECRET, 'password')],
            	[var: 'UNITY_AUTHENTICATION_SERIAL', password: jsonKey(SECRET, 'serial')]
    ]) {
    	closure()
  	}
  }
}

String jsonKey(String json, String key) {
	def obj = readJSON text: json
	obj[key]
}
