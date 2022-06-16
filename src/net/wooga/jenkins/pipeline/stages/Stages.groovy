package net.wooga.jenkins.pipeline.stages

import net.wooga.jenkins.pipeline.config.PipelineConfig;

class Stages {
     Stage check
     Stage publish
     private Closure<Stage> stageFactory

     static Stages standard(Map jenkinsParams, PipelineConfig config) {
          return new Stages(
                  new Stage(null, null, null),
                  new Stage(null, null, null),
                  { Closure cls -> Stage.fromClosure(jenkinsParams , config, cls) })
     }

     static Stages fromClosure(Map jenkinsParams, PipelineConfig config, Closure cls) {
          def clsClone = cls.clone() as Closure
          def stages = standard(jenkinsParams, config)
          clsClone.delegate = stages
          clsClone(stages)
          return stages
     }

     Stages(Stage check, Stage publish, Closure<Stage> stageFactory) {
          this.check = check
          this.publish = publish
          this.stageFactory = stageFactory
     }

     void setCheck(Closure checkCls) {
          this.check = stageFactory(checkCls)
     }

     void setPublish(Closure publishCls) {
          this.publish = stageFactory(publishCls)
     }
}



