#!/usr/bin/env groovy

def call(List<Map> varAndPasswordList, Closure closure) {
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: varAndPasswordList]) {
    withEnv(varAndPasswordList.collect { "${it.var}=${it.password}" }) {
      closure()
    }
  }
}
