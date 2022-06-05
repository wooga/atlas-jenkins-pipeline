package net.wooga.jenkins.pipeline.test.specifications

import com.lesfurets.jenkins.unit.PipelineTestHelper
import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import com.lesfurets.jenkins.unit.declarative.WhenDeclaration
import net.wooga.jenkins.pipeline.test.specifications.fakes.FakeCredentialStorage
import net.wooga.jenkins.pipeline.test.specifications.fakes.FakeEnvironment
import net.wooga.jenkins.pipeline.test.specifications.fakes.FakeJenkinsObject
import net.wooga.jenkins.pipeline.test.specifications.fakes.FakeMethodCalls
import net.wooga.jenkins.pipeline.test.specifications.fakes.WithCredentials
import net.wooga.jenkins.pipeline.test.exceptions.JenkinsError
import org.apache.commons.lang3.ClassUtils
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.stream.IntStream

/**
 * Abstract test specification for testing Jenkins Declarative Pipeline.
 * Has fake environments and credential storage for testing.
 */
abstract class DeclarativeJenkinsSpec extends Specification {

    static Object lock = new Object()
    @Shared Map<String, Map> stash
    @Shared FakeCredentialStorage credentials
    @Shared FakeEnvironment environment
    @Shared FakeMethodCalls calls
    @Shared PipelineTestHelper helper
    @Shared Binding binding
    @Shared String currentDir
    @Shared GenericPipelineDeclaration pipeline

    abstract DeclarativePipelineTest getJenkinsTest()

    void setupSpec() {
        this.stash = new HashMap<>()
        this.credentials = new FakeCredentialStorage()
        this.environment = new FakeEnvironment(jenkinsTest.binding)
        this.calls = new FakeMethodCalls(jenkinsTest.helper)
        jenkinsTest.pipelineInterceptor = { Closure cls ->
            GenericPipelineDeclaration.binding = binding
            this.pipeline = GenericPipelineDeclaration.createComponent(DeclarativePipeline, cls)
            this.pipeline.execute(delegate)
        }
        this.helper = jenkinsTest.helper
        this.binding = jenkinsTest.binding
        this.currentDir = null
        jenkinsTest.setUp()
        addLackingDSLTerms()
        populateJenkinsDefaultEnvironment(binding)
    }

    private static void addLackingDSLTerms() {
        GenericPipelineDeclaration.metaClass.any = "any"
        WhenDeclaration.metaClass.beforeAgent = { bool -> null }
    }

    private static void populateJenkinsDefaultEnvironment(Binding binding) {
        binding.with {
            setProperty("scm", "scm")
            setProperty("BUILD_NUMBER", 1)
            setProperty("BRANCH_NAME", "branch")
        }
    }

    void setup() {
        registerPipelineFakeMethods(helper)
    }

    void cleanup() {
        helper?.callStack?.clear()
        environment.wipe()
        credentials.wipe()
    }

    /**
     * Loads a script to be used by another script. Registers its own call() method as an jenkins mock method,
     * in order for it to be used by another script.
     * @param path - path to the script to be loaded.
     * @param varBindingOps - convenience closure to execute operations over the binding
     * object that will be passed to scripts
     * @param reloadSideScripts - if the side scripts should be loaded, defaults to true.
     * @return Script object representing the loaded script
     */
    void registerSideScript(String scriptPath, Binding binding) {
        def scriptName = new File(scriptPath).name.replace(".groovy", "")
        def script = helper.loadScript(scriptPath, binding)
        script.class.getDeclaredMethods().findAll {it.name == "call" }.each { callMethod ->
            registerAllowedMethod(scriptName, script, callMethod)
        }
    }

    /**
     * Registers a given Method as a jenkins mock method..
     * @param methodName Name to register the mock as;
     * @param base Object from which method will be called
     * @param method the method to be called
     * @return Closure calling the registered method.
     */
    Closure registerAllowedMethod(String methodName, Object base, Method method) {
        List<Class> methodParams = method.parameterTypes.collect {
            return it.isPrimitive()? ClassUtils.primitiveToWrapper(it) : it
        }
        def methodCall = { Object... args ->
            args = args == null? [null] : args
            args = IntStream.range(0, args.size()).mapToObj { int index ->
                if(args[index] instanceof GString) {
                    return args[index].toString()
                }
                return methodParams[index]?.cast(args[index])
            }.toArray()
            method.invoke(base, args)
        }
        helper.registerAllowedMethod(methodName, methodParams, methodCall)
        return methodCall
    }


    private void registerPipelineFakeMethods(PipelineTestHelper helper) {
        helper.with {
            registerAllowedMethod("httpRequest", [LinkedHashMap]) {}
            registerAllowedMethod("publishHTML", [HashMap]) {}
            registerAllowedMethod("sourceFiles", [String]) {}
            registerAllowedMethod("publishCoverage", [Map]) {}
            registerAllowedMethod("unstable", [Map]) {}
            registerAllowedMethod("error", [String]) { String msg -> throw new JenkinsError(msg) }
            registerAllowedMethod("withEnv", [List, Closure], environment.&runWithEnv)
            registerAllowedMethod("checkout", [String]) {}
            registerAllowedMethod("fileExists", [String]) { String path -> new File(path).exists() }
            registerAllowedMethod("readFile", [String]) { String path -> new File(path).text }
            registerAllowedMethod("findFiles", [Map]) { Map args -> new FakeJenkinsObject().findFiles(args) }
            registerAllowedMethod("usernamePassword", [Map], WithCredentials.&usernamePassword)
            registerAllowedMethod("usernameColonPassword", [Map], WithCredentials.&usernameColonPassword)
            //this doesn't generate KEY_USR and KEY_PWD, as we don't know who KEY is. This may be possible with groovy AST wizardry though.
            registerAllowedMethod("credentials", [String], credentials.&getAt)
            registerAllowedMethod("string", [Map]) { Map params ->
                return params.containsKey("name")?
                        jenkinsTest.paramInterceptor : //string() from parameters clause
                        WithCredentials.string(params) //string() from withCredentials() context
            }
            registerAllowedMethod("withCredentials", [List.class, Closure.class],
                                            WithCredentials.&bindCredentials.curry(credentials, environment))
            //needed as utils scripts are dependent on jenkins sandbox
            registerAllowedMethod("utils", []) {
                [stringToSHA1 : { content ->
                    def shaBytes = MessageDigest.getInstance("SHA1").digest(content.toString().getBytes())
                    return new BigInteger(1, shaBytes).toString(16)
                }]
            }
            registerAllowedMethod("lock", [Closure]) { cls -> synchronized (lock) { cls() } }
            registerAllowedMethod("stash", [Map]) { params -> stash[params.name] = params }
            registerAllowedMethod("unstash", [String]) { String key ->
                Optional.ofNullable(stash[key]).
                        orElseThrow{new IllegalStateException("${key} does not exists on stash")}
            }
            registerAllowedMethod("dir", [String, Closure]) { String targetDir, cls ->
                def previousDir = this.currentDir
                this.currentDir = targetDir
                cls()
                this.currentDir = previousDir
            }
        }
    }

}
