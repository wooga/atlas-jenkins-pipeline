//package net.wooga.jenkins.pipeline
//
//import net.wooga.jenkins.pipeline.config.DockerArgs
//import net.wooga.jenkins.pipeline.config.Platform
//
//
//class JenkinsCheck {
//
//    final Platform platform
//    final DockerArgs dockerArgs
//
//    JenkinsCheck(Platform platform, DockerArgs dockerArgs) {
//        this.platform = platform
//        this.dockerArgs = dockerArgs
//    }
//
//    Closure createClosure(String nodeLabel, List<String> testEnvironment,
//                          Closure mainClosure, Closure catchClosure={}, Closure finallyClosure={}) {
//        return {
//            create(nodeLabel, testEnvironment, mainClosure, catchClosure, finallyClosure)
//        }
//    }
//
//    def create(String nodeLabel, List<String> testEnvironment,
//               Closure mainClosure, Closure catchClosure={}, Closure finallyClosure={}) {
//        node(nodeLabel) {
//            try {
//                withEnv(testEnvironment) { //TODO test testEnvironment
//                    if (platform.name == "linux") {
//                        def image = dockerArgs.createImage(docker)
//                        if(image != null) {
//                            def imageArgs = dockerArgs.dockerArgs.join(" ")
//                            image.inside(imageArgs) { mainClosure.call() }
//                        } else {
//                            mainClosure.call()
//                        }
//                    } else {
//                        mainClosure.call()
//                    }
//                }
//            }
//            catch (Exception e) {
//                if (catchClosure) {
//                    catchClosure.call(e)
//                } else {
//                    throw e
//                }
//            }
//            finally {
//                finallyClosure.call()
//            }
//        }
//    }
//}
