//package net.wooga.jenkins.pipeline.step
//
//import net.wooga.jenkins.pipeline.JenkinsCheck
//import net.wooga.jenkins.pipeline.Config
//import net.wooga.jenkins.pipeline.Platform
//
//class StepsBuilder {
//
//    Config config;
//    Platform platform;
//    List<Step> steps;
//
//    StepsBuilder(Config config, Platform platform) {
//        this.config = config
//        this.platform = platform
//        this.steps = new ArrayList<>()
//    }
//
//
//    Closure toJenkinsCheckStep(Closure mainClosure, Closure post) {
//        def nodeLabel = "atlas && ${platform.testLabels}"
//        def dockerArgs = config.dockerArgs
//        List<String> testEnvironment = platform.testEnvironment
//        testEnvironment.add("TRAVIS_JOB_NUMBER=${config.buildNumber}.${platform.name.toUpperCase()}")
//        return {
//            new JenkinsCheck(platform, dockerArgs).createClosure(nodeLabel, testEnvironment, mainClosure).call()
//            post()
//        }
//    }
//}