package net.wooga.jenkins.pipeline

import hudson.model.Run
import org.jenkinsci.plugins.pipeline.modeldefinition.actions.ExecutionModelAction
import org.jenkinsci.plugins.pipeline.modeldefinition.ast.ModelASTStage
import org.jenkinsci.plugins.pipeline.modeldefinition.ast.ModelASTStages
import org.jenkinsci.plugins.workflow.actions.LabelAction
import org.jenkinsci.plugins.workflow.actions.StageAction
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode
import org.jenkinsci.plugins.workflow.flow.FlowExecution
import org.jenkinsci.plugins.workflow.flow.GraphListener
import org.jenkinsci.plugins.workflow.flow.StepListener
import org.jenkinsci.plugins.workflow.graph.FlowNode
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepContext

import com.cloudbees.groovy.cps.NonCPS
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.logging.Logger

class Listeners {
    static final Logger LOGGER = Logger.getLogger(Listeners.class.getName());
    static String accumulator = ""
    static StepListener stepListener = new StepListener() {
        @NonCPS
        @Override
        void notifyOfNewStep(Step step, StepContext context) {
            FlowNode node = context.get(FlowNode.class);
            if(node != null) {
                accumulator += "nodeName: $node.displayName" + "\n"
                accumulator += "stepName: ${step.descriptor.displayName}" + "\n\n"
            }
        }
    }

    static GraphListener graphListener = new GraphListener() {
        @NonCPS
        @Override
        void onNewHead(FlowNode node) {
            println("onNewHead")
            LOGGER.info("GraphListener logger")
            LOGGER.info("logger Current node " + node.getDisplayName())
            throw new IOException("My exception!")
            if(isStage(node)) {
                LOGGER.info("logger node is a stage")
            }
        }
    }

    @NonCPS
    static void addStepListener(Object jenkinsInstance) {
        jenkinsInstance.getExtensionList(StepListener).add(stepListener)
    }

    @NonCPS
    static void removeStepListener(Object jenkinsInstance) {
        jenkinsInstance.getExtensionList(StepListener).remove(stepListener)
    }

    //type WorkflowScript
    @NonCPS
    static void addGraphListener(Object script) {
        addGraphListener(script.currentBuild, Listeners.graphListener)
    }

    @NonCPS
    static void removeGraphListener(RunWrapper build) {
        build.rawBuild.execution.removeListener(Listeners.graphListener)
//        withBuildFlowExecution(build) { FlowExecution execution ->
//            execution.removeListener(Listeners.graphListener)
//        }
//        jenkinsInstance.getExtensionList(GraphListener).remove(graphListener)
    }

    def runFor(FlowExecution exec) {
        def executable;
        try {
            executable = exec.getOwner().getExecutable();
        } catch (IOException x) {
            getLogger().log(Level.WARNING, null, x);
            return null;
        }
        if (executable instanceof Run) {
            return (Run<?, ?>) executable;
        } else {
            return null;
        }
    }

    def getDeclarativeStages(Run<?, ?> run) {
        ExecutionModelAction executionModelAction = run.getAction(ExecutionModelAction.class);
        if (null == executionModelAction) {
            return null;
        }
        ModelASTStages stages = executionModelAction.getStages();
        if (null == stages) {
            return null;
        }
        List<ModelASTStage> stageList = stages.getStages();
        if (null == stageList) {
            return null;
        }
        return stageList.collect{it.name};
    }

    @NonCPS
    static String printNodes(Object script) {

        def exec = ((CpsFlowExecution)script.currentBuild.rawBuild.execution);
        def heads = exec.getCurrentHeads()
//        return heads.collect{it.displayName}.join(", ")
//        return exec.getListenersToRun().collect { it.class.canonicalName + it.hashCode() }.join(',')
        return getDeclarativeStages(runFor(exec)).join(", ")
    }

    @NonCPS
    static void addGraphListener(RunWrapper build, GraphListener listener) {
        def exec = ((FlowExecution)build.rawBuild.execution);
        LOGGER.info(build.rawBuild.execution.toString())
        build.rawBuild.execution.addListener(listener)
//        withBuildFlowExecution(build) { execution ->
//            if (execution != null) {
//                throw new Exception("aaaaaa")
//                execution.addListener(listener)
//            } else {
//                LOGGER.log(SEVERE, "could not get flow-execution for build " + build.fullDisplayName)
//            }
//        }
    }

    @NonCPS
    static void withBuildFlowExecution(RunWrapper build, Closure flowExecutionOp) {
        def rawBuild = (WorkflowRun) build.rawBuild
        rawBuild.executionPromise.addListener({ ->
            FlowExecution execution = rawBuild.execution
            flowExecutionOp(execution)
        }, Executors.newSingleThreadExecutor())
    }


    @NonCPS
    static boolean isStage(FlowNode node) {
        if (node instanceof StepAtomNode) {
            // This filters out labelled steps, such as `sh(script: "echo 'hello'", label: 'echo')`
            return false;
        }
        return node != null && ((node.getAction(StageAction) != null)
                || (node.getAction(LabelAction) != null && node.getAction(ThreadNameAction) == null));
    }



}

