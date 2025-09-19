//import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
//import org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution
///**
// * Runs a timeout block, but catches the thrown FlowInterruptedException on timeout and transforms it into a generic Jenkins error (from the error step).
// * @param timeoutArgs Map with the same arguments you would provide the timeout step, plus an optional `onTimeout` closure, which runs in case of timeout. Defaults to a jenkins `error` step call.
// * @param action Same action you would provide the timeout step.
// * @return nothing
// */
//def call(Map timeoutArgs, Closure action) {
//    timeoutArgs.onTimeout = timeoutArgs.onTimeout ?: { error "Step timeout: ${timeoutArgs}" }
//    try {
//        timeout(timeoutArgs) {
//            action()
//        }
//    } catch(FlowInterruptedException e) {
//        //Transforms timeout error into a non-FlowInterruptedException, that will be catch by a catchError block
//        def isTimeout = e.causes.any { it instanceof TimeoutStepExecution.ExceededTimeout }
//        if(isTimeout) {
//            timeoutArgs.onTimeout(timeoutArgs)
//        } else {
//            throw e
//        }
//    }
//}
//
