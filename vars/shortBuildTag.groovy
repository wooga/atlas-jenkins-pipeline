#!/usr/bin/env groovy

def String call(Int numberOfDirectories = 2) {
  parts = env.JOB_URL.replaceAll("/", "").tokenize('job')
  
  if(parts.size() > 1) {
    parts = parts.takeRight(numberOfDirectories)
  }
  
  return parts.join('-')
}