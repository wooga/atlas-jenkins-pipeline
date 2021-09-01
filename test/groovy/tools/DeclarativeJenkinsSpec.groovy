package tools

import com.lesfurets.jenkins.unit.MethodCall
import com.lesfurets.jenkins.unit.PipelineTestHelper
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import com.lesfurets.jenkins.unit.declarative.PostDeclaration
import com.lesfurets.jenkins.unit.declarative.WhenDeclaration
import net.wooga.jenkins.pipeline.TestHelper
import org.apache.commons.lang3.ClassUtils
import org.codehaus.groovy.runtime.DefaultGroovyMethods
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.IntStream

abstract class DeclarativeJenkinsSpec extends Specification {

    @Shared DeclarativePipelineTest jenkinsTest;
    @Shared Binding binding
    @Shared PipelineTestHelper helper

    def setupSpec() {
        jenkinsTest = new DeclarativePipelineTest() {}
        jenkinsTest.setUp()
        helper = jenkinsTest.helper
        binding = jenkinsTest.binding
        addLackingDSLTerms()

        populateJenkinsDefaultEnvironment(binding)
        registerPipelineFakeMethods(helper)
    }

    def cleanup() {
       helper?.callStack?.clear()
    }

    private static void populateJenkinsDefaultEnvironment(Binding binding) {
        binding.with {
            setProperty("scm", "scm")
            setProperty("BUILD_NUMBER", 1)
            setProperty("BRANCH_NAME", "any")
            setProperty("docker", [:])

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
            registerAllowedMethod("fileExists", [String]) {String path -> new File(path).exists() }
            registerAllowedMethod("readFile", [String]) {String path -> new File(path).text }
            registerAllowedMethod("utils", []) {[stringToSHA1 : { content -> "fakesha" }]}
        }
    }

    private static void addLackingDSLTerms() {
        GenericPipelineDeclaration.metaClass.any = "any"
        WhenDeclaration.metaClass.beforeAgent = { bool -> null }
        PostDeclaration.metaClass.cleanup = { clj -> clj() }
        PostDeclaration.metaClass.cleanWs = {}
    }

    protected boolean hasShCallWith(Closure assertion) {
        return hasMethodCallWith("sh") {
            MethodCall call -> assertion(call.argsToString())
        }
    }

    protected boolean hasMethodCallWith(String methodName, Closure assertion) {
        return getMethodCalls(methodName).any(assertion)
    }

    protected MethodCall[] getMethodCalls(String methodName) {
        return helper.callStack.findAll { call ->
            call.methodName == methodName
        }
    }

    protected Script loadScript(String name, Closure varBindingOps={}, boolean reloadSideScripts = true) {
        varBindingOps.setDelegate(binding.variables)
        varBindingOps(binding.variables)
        if(reloadSideScripts) {
            registerSideScript("vars/gradleWrapper.groovy", binding)
            registerSideScripts("src/net/wooga/jenkins/pipeline/scripts", "utils.groovy")
        }
        return helper.loadScript(name, binding)
    }

    protected void registerSideScripts(String folderPath, String... exceptions) {
        Files.walk(Paths.get(folderPath)).
                filter {path -> path.toString().endsWith(".groovy")}.
                filter {path -> exceptions.any {!path.toString().endsWith(it) }}.
                forEach{path -> registerSideScript(path.toString(), binding) }
    }

    protected void registerSideScript(String scriptPath, Binding binding) {
        def scriptName = new File(scriptPath).name.replace(".groovy", "")
        def script = helper.loadScript(scriptPath, binding)
        script.class.getDeclaredMethods().findAll {return it.name == "call" }.each {
            callMethod -> registerAllowedMethod(scriptName, script, callMethod)
        }
    }

    protected void registerAllowedMethod(String methodName, Object base, Method method) {
        List<Class> callParams = method.parameterTypes.collect {
            if(it.isPrimitive()) {
                return ClassUtils.primitiveToWrapper(it)
            }
            return it
        }
        helper.registerAllowedMethod(methodName, callParams) { Object... args ->
            if(args == null) {
                args = [null]
            }
            args = IntStream.range(0, args.size()).mapToObj { int index ->
                if(args[index] instanceof GString) {
                    return args[index].toString()
                }
                return callParams[index]?.cast(args[index])
            }.toArray()
            method.invoke(base, args)
        }
    }
}
