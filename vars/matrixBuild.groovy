#!/usr/bin/env groovy

def call(Map config, Closure body) {
  if(!config.label) {
    error "Please provide a label to run the body"
  }

  def labelQuery = config.label
  def jobLabel = config.name ?: "matrixBuild"

  def nodes = nodesByLabel(label:labelQuery)
  
  if(nodes.isEmpty()) {
    error "No execution node available with label ${labelQuery}."
  }

  def stepsToExecuteParallel = nodes.collectEntries { String nodeName ->
    ["${jobLabel}-${nodeName}" : executeOnNode(nodeName, body)]
  }
  parallel stepsToExecuteParallel
}

def executeOnNode(String nodeName, Closure body) {
  return {
    node(nodeName) {
      if (body) {
        body.call(nodeName)
      }
    }
  }
}
