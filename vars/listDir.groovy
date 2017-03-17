#!/usr/bin/env groovy

def call(path, includePattern = null ) {
  out = []
  new File(path).eachFile() { file->
    file_name = file.getName()
    out << file_name
  }
  return out
}