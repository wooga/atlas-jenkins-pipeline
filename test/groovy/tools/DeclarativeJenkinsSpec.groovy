package tools

import com.lesfurets.jenkins.unit.PipelineTestHelper
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import com.lesfurets.jenkins.unit.declarative.PostDeclaration
import com.lesfurets.jenkins.unit.declarative.WhenDeclaration
import net.wooga.jenkins.pipeline.TestHelper
import spock.lang.Shared
import spock.lang.Specification

abstract class DeclarativeJenkinsSpec extends Specification {

    @Shared DeclarativePipelineTest jenkinsTest;
    @Shared Binding binding
    @Shared PipelineTestHelper helper

    public void setupSpec() {
        jenkinsTest = new DeclarativePipelineTest() {}
        jenkinsTest.setUp()
        helper = jenkinsTest.helper
        binding = jenkinsTest.binding

        addLackingDSLTerms()

        populateJenkinsDefaultEnvironment(binding)
        registerPipelineFakeMethods(helper)
        forceMockedScriptConstructor(binding, helper, TestHelper.metaClass, "src/net/wooga/jenkins/pipeline/TestHelper.groovy")
    }

    private static void populateJenkinsDefaultEnvironment(Binding binding) {
        binding.with {
            setProperty("scm", "scm")
            setProperty("BUILD_NUMBER", 1)
        }
    }

    private static void forceMockedScriptConstructor(Binding binding, PipelineTestHelper helper,
                                              MetaClass scriptClass, String scriptPath) {
        scriptClass.constructor = {->
            return helper.loadScript(scriptPath, binding)
        }
    }

    private static void registerPipelineFakeMethods(PipelineTestHelper helper) {
        helper.with {
            registerAllowedMethod("sendSlackNotification", [String, boolean]) {}
            registerAllowedMethod("junit", [LinkedHashMap]) {}
            registerAllowedMethod("cleanWs") {}
            registerAllowedMethod("checkout", [String]) {}
            registerAllowedMethod("publishHTML", [HashMap]) {}
        }
    }

    private static void addLackingDSLTerms() {
        GenericPipelineDeclaration.metaClass.any = "any"
        WhenDeclaration.metaClass.beforeAgent = { bool -> null }
        PostDeclaration.metaClass.cleanup = { clj -> clj() }
        PostDeclaration.metaClass.cleanWs = {}
    }

    protected boolean hasShCallWith(Closure assertion) {
        helper.callStack.findAll { call ->
            call.methodName == "sh"
        }.any { call ->
            assertion(call.argsToString())
        }
    }
}
