package net.wooga.jenkins.pipeline.stages

import net.wooga.jenkins.pipeline.config.PipelineConfig

class Stage {
     Closure when
     Closure action

     static Stage fromClosure(Map jenkinsParams, PipelineConfig config, Closure cls) {
          def clsClone = cls.clone() as Closure
          def stage = new Stage(null, null)
          clsClone.delegate = stage
          clsClone(stage, jenkinsParams, config)
          return stage
     }

     Stage(Closure when, Closure action) {
          this.when = when
          this.action = action
     }

     boolean runWhenOrElse(Closure alternative) {
          if(when) {
               return when()
          }
          return alternative? alternative(): false
     }

     boolean runActionOrElse(Closure alternative) {
          if(action) {
               return action()
          }
          return alternative? alternative(): false
     }
}