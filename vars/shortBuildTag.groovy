#!/usr/bin/env groovy

def String call(int numberOfDirectories = 2) {
  parts = env.JOB_URL.replaceAll("/", "").tokenize('job')
  
  if(parts.size() > 1) {
    numberOfDirectories = Math.min(numberOfDirectories, parts.size())
    parts = parts.takeRight(numberOfDirectories)
  }
  
  return parts.join('-')
}