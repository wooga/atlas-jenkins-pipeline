#!groovy

import com.cloudbees.groovy.cps.NonCPS
import org.jenkinsci.plugins.workflow.graph.AtomNode
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

// Pipeline-neutral inspection of downstream builds.

// Returns serializable build metadata. Pipeline graph failures are reported separately so callers can still use the
// build's identity and result.
Map inspectBuild(RunWrapper run) {
    if (run == null) {
        throw new IllegalArgumentException('run must not be null')
    }

    Map info = [
        projectName: run.fullProjectName,
        number: run.number,
        id: run.id,
        externalizableId: run.externalizableId,
        result: run.result,
        url: run.absoluteUrl,
        displayName: run.displayName,
        fullDisplayName: run.fullDisplayName,
        startTimeMillis: run.startTimeInMillis,
        durationMillis: run.duration,
        durationString: run.durationString,
        causes: copyCauses(run.buildCauses),
        stages: [],
        stageInspectionError: null,
    ]

    try {
        info.stages = inspectStages(run)
    } catch (Exception e) {
        info.stageInspectionError = "${e.class.name}: ${e.message}"
    }
    return info
}

// Returns every stage and parallel branch in the workflow graph. It deliberately does not remove setup,
// declarative, skipped, wrapper or unfinished stages; those are presentation decisions for the caller.
@NonCPS
List<Map> inspectStages(RunWrapper run) {
    if (run == null) {
        throw new IllegalArgumentException('run must not be null')
    }
    def rawBuild = run.rawBuild
    if (!(rawBuild instanceof WorkflowRun)) {
        throw new IllegalArgumentException("build ${run.externalizableId} is not a WorkflowRun")
    }
    if (rawBuild.execution == null) {
        throw new IllegalStateException("build ${run.externalizableId} has no workflow execution")
    }

    def visitor = new io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor(rawBuild)
    List wrappers = []
    for (def wrapper : visitor.pipelineNodes) {
        String type = String.valueOf(wrapper.type)
        if (type == 'STAGE' || type == 'PARALLEL') {
            wrappers.add(wrapper)
        }
    }

    Map wrappersById = [:]
    Map stagesById = [:]
    List<Map> stages = []
    for (def wrapper : wrappers) {
        def timing = wrapper.timing
        def status = wrapper.status
        Map stage = [
            id: wrapper.id,
            name: wrapper.displayName,
            type: String.valueOf(wrapper.type),
            result: status?.result?.name(),
            state: status?.state?.name(),
            causeOfFailure: wrapper.causeOfFailure,
            startTimeMillis: timing?.startTimeMillis ?: 0L,
            durationMillis: timing?.totalDurationMillis ?: 0L,
            pauseDurationMillis: timing?.pauseDurationMillis ?: 0L,
            parentId: null,
            childIds: [],
            directStepCount: 0,
            hasChildren: false,
            hasDirectSteps: false,
        ]
        wrappersById[stage.id] = wrapper
        stagesById[stage.id] = stage
        stages.add(stage)
    }

    for (Map stage : stages) {
        def flowNode = wrappersById[stage.id]?.node
        stage.parentId = nearestEnclosingStageId(flowNode, stagesById, stage.id)
    }

    def scanner = new DepthFirstScanner()
    for (def flowNode : scanner.allNodes(rawBuild.execution)) {
        if (flowNode instanceof AtomNode) {
            String stageId = nearestEnclosingStageId(flowNode, stagesById)
            if (stageId != null) {
                stagesById[stageId].directStepCount = ((Number) stagesById[stageId].directStepCount).intValue() + 1
            }
        }
    }

    sortStages(stages)
    for (Map stage : stages) {
        if (stage.parentId != null && stagesById[stage.parentId] != null) {
            stagesById[stage.parentId].childIds.add(stage.id)
        }
    }
    for (Map stage : stages) {
        stage.hasChildren = stage.childIds.isEmpty() == false
        stage.hasDirectSteps = ((Number) stage.directStepCount).intValue() > 0
    }
    return stages
}

@NonCPS
private List<Map> copyCauses(def causes) {
    List<Map> copied = []
    for (def cause : causes ?: []) {
        Map item = [:]
        for (def entry : cause.entrySet()) {
            item[String.valueOf(entry.key)] = copyValue(entry.value)
        }
        copied.add(item)
    }
    return copied
}

@NonCPS
private Object copyValue(Object value) {
    if (value instanceof Map) {
        Map copied = [:]
        for (def entry : value.entrySet()) {
            copied[String.valueOf(entry.key)] = copyValue(entry.value)
        }
        return copied
    }
    if (value instanceof Collection) {
        List copied = []
        for (Object nested : value) {
            copied.add(copyValue(nested))
        }
        return copied
    }
    if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
        return value
    }
    return String.valueOf(value)
}

@NonCPS
private String nearestEnclosingStageId(def flowNode, Map stagesById, String excludedId = null) {
    if (flowNode == null) {
        return null
    }
    for (def block : flowNode.iterateEnclosingBlocks()) {
        if (block.id != excludedId && stagesById.containsKey(block.id)) {
            return block.id
        }
    }
    return null
}

@NonCPS
private void sortStages(List<Map> stages) {
    for (int i = 1; i < stages.size(); i++) {
        Map item = stages.get(i)
        int j = i - 1
        while (j >= 0 && stageComesAfter(stages.get(j), item)) {
            stages.set(j + 1, stages.get(j))
            j--
        }
        stages.set(j + 1, item)
    }
}

@NonCPS
private boolean stageComesAfter(Map left, Map right) {
    long leftStarted = ((Number) left.startTimeMillis).longValue()
    long rightStarted = ((Number) right.startTimeMillis).longValue()
    if (leftStarted != rightStarted) {
        return leftStarted > rightStarted
    }
    return numericId(left.id) > numericId(right.id)
}

@NonCPS
private long numericId(Object id) {
    try {
        return Long.parseLong(String.valueOf(id))
    } catch (NumberFormatException ignored) {
        return 0L
    }
}
