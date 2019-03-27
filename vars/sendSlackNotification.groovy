#!/usr/bin/env groovy

/**
 * Send notifications based on build status string
 */
def call(String buildStatus = 'STARTED', blueOceanURL = false) {
  // build status of null means successful
  buildStatus =  buildStatus ?: 'SUCCESSFUL'

  // Default values
  def buildUrl = env.BUILD_URL

  if( blueOceanURL && env.RUN_DISPLAY_URL.trim()) {
    buildUrl = env.RUN_DISPLAY_URL
  }

  def channel
  if(env.SLACK_CHANNEL) {
    channel = env.SLACK_CHANNEL.trim()
  }

  def colorCode = '#FF0000'

  // Override default values based on build status
  if (buildStatus == 'STARTED') {
    colorCode = '#FFFF00'
    buildStatus = buildStatus.toLowerCase().capitalize()
  } else if (buildStatus == 'SUCCESSFUL') {
    colorCode = '#00FF00'
    buildStatus = buildStatus.toLowerCase().capitalize()
  else if (buildStatus == 'SUCCESS') {
     colorCode = '#00FF00'
     buildStatus = buildStatus.toLowerCase().capitalize()
  } else {
    colorCode = '#FF0000'
    buildStatus = buildStatus.toLowerCase().capitalize()
  }

  def subject = "*${buildStatus}*: _${env.JOB_NAME}_ *[Build: ${env.BUILD_NUMBER}]*"
  def summary = "${subject}\n${buildUrl}"

  // Send notifications
  slackSend (color: colorCode, message: summary, channel: channel)
}
