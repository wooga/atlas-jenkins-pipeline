package tools


import com.lesfurets.jenkins.unit.PipelineTestHelper
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import com.lesfurets.jenkins.unit.declarative.PostDeclaration
import com.lesfurets.jenkins.unit.declarative.WhenDeclaration
import org.apache.commons.lang3.ClassUtils
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.GenericWhitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist
import spock.lang.Shared
import spock.lang.Specification
import tools.sandbox.PackageWhitelist
import tools.sandbox.SandboxDeclarativePipelineTest
import tools.sandbox.SandboxPipelineTestHelper

import java.lang.reflect.Method
import java.util.stream.IntStream

/**
 * Abstract test specification for testing Jenkins Declarative Pipeline.
 * Has fake environments and credential storage for testing, as well as support for sandboxed execution.
 */
abstract class DeclarativeJenkinsSpec extends Specification {

    static Object lock = new Object()
    @Shared DeclarativePipelineTest jenkinsTest;
    @Shared Binding binding
    @Shared SandboxPipelineTestHelper helper
    @Shared FakeCredentialStorage credentials
    @Shared FakeMethodCalls calls
    @Shared FakeEnvironment environment
    @Shared Map<String, Map> jenkinsStash
    @Shared String currentDir

    def setupSpec() {
        jenkinsStash = new HashMap<>()
        jenkinsTest = new SandboxDeclarativePipelineTest(new ProxyWhitelist(
                new GenericWhitelist(),
                new PackageWhitelist("com.lesfurets.jenkins"),
                new PackageWhitelist("net.wooga.jenkins.pipeline")
        ))
        jenkinsTest.setUp()
        environment = new FakeEnvironment(jenkinsTest.binding)
        credentials = new FakeCredentialStorage(environment)
        calls = new FakeMethodCalls(jenkinsTest.helper)

        helper = jenkinsTest.helper as SandboxPipelineTestHelper
        binding = jenkinsTest.binding
        currentDir = null

        addLackingDSLTerms()
        populateJenkinsDefaultEnvironment(binding)
    }

    def setup() {
        registerPipelineFakeMethods(helper)
    }

    def cleanup() {
        helper?.callStack?.clear()
        environment.wipe()
        credentials.wipe()
    }

    def getUsedEnvironments() {
        return environment.usedEnvironments
    }

    private static void populateJenkinsDefaultEnvironment(Binding binding) {
        binding.with {
            setProperty("scm", "scm")
            setProperty("BUILD_NUMBER", 1)
            setProperty("BRANCH_NAME", "any")
            setProperty("docker", [:])

        }
    }

    private void registerPipelineFakeMethods(PipelineTestHelper helper) {
        helper.with {
            registerAllowedMethod("isUnix") { true }
            registerAllowedMethod("sendSlackNotification", [String, boolean]) {}
            registerAllowedMethod("junit", [LinkedHashMap]) {}
            registerAllowedMethod("nunit", [LinkedHashMap]) {}
            registerAllowedMethod("istanbulCoberturaAdapter", [String]) {}
            registerAllowedMethod("sourceFiles", [String]) {}
            registerAllowedMethod("publishCoverage", [Map]) {}
            registerAllowedMethod("unstash", [String]) {}
            registerAllowedMethod("unstable", [Map]) {}
            registerAllowedMethod("withEnv", [List, Closure]) { List<?> envStrs, Closure cls ->
                def env = envStrs.collect{it.toString()}.
                            collectEntries{String envStr -> [(envStr.split("=")[0]): envStr.split("=")[1]]}
                environment.runWithEnv(env, cls)
            }
            registerAllowedMethod("cleanWs") {}
            registerAllowedMethod("checkout", [String]) {}
            registerAllowedMethod("publishHTML", [HashMap]) {}
            registerAllowedMethod("fileExists", [String]) {String path -> new File(path).exists() }
            registerAllowedMethod("readFile", [String]) {String path -> new File(path).text }
            registerAllowedMethod("usernamePassword", [Map], credentials.&usernamePassword)
            registerAllowedMethod("usernameColonPassword", [Map], credentials.&usernameColonPassword)
            //TODO: make this generate KEY_USR and KEY_PWD environment
            registerAllowedMethod("credentials", [String], credentials.&getAt)
            registerAllowedMethod("string", [Map]) { Map params ->
                return params.containsKey("name")?
                        jenkinsTest.paramInterceptor : //string() from parameters clause
                        credentials.string(params) //string() from withCredentials() context
            }
            registerAllowedMethod("withCredentials", [List.class, Closure.class], credentials.&bindCredentials)
            //needed as utils scripts are dependent on jenkins sandbox
            registerAllowedMethod("utils", []) {[stringToSHA1 : { content -> "fakesha" }]}
            registerAllowedMethod("lock", [Closure]) { cls ->
                synchronized (lock) { cls() }
            }
            registerAllowedMethod("stash", [Map]) {Map<String,?> params -> jenkinsStash[params.name] = params}
            registerAllowedMethod("unstash", [String]) { String key ->
                Optional.ofNullable(jenkinsStash[key]).
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

    private static void addLackingDSLTerms() {
        GenericPipelineDeclaration.metaClass.any = "any"
        WhenDeclaration.metaClass.beforeAgent = { bool -> null }
        PostDeclaration.metaClass.cleanup = { clj -> clj() }
        PostDeclaration.metaClass.cleanWs = {}
    }

    /**
     * Loads a script and the javaLibCheck and publish side scripts inside the sandbox.
     * @param path - path to the script to be loaded.
     * @param varBindingOps - convenience closure to execute operations over the binding
     * object that will be passed to scripts
     * @param reloadSideScripts - if the side scripts should be loaded, defaults to true.
     * @return Script object representing the loaded script
     */
    Script loadSandboxedScript(String path, Closure varBindingOps={}, boolean reloadSideScripts = true) {
        varBindingOps.setDelegate(binding.variables)
        varBindingOps(binding.variables)
        if(reloadSideScripts) {
            registerSideScript("vars/javaLibCheck.groovy", binding)
            registerSideScript("vars/javaLibs.groovy", binding)
            registerSideScript("vars/publish.groovy", binding)
        }
        return helper.loadSandboxedScript(path, binding)
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
    protected void registerSideScript(String scriptPath, Binding binding) {
        def scriptName = new File(scriptPath).name.replace(".groovy", "")
        def script = helper.loadSandboxedScript(scriptPath, binding)
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
    protected Closure registerAllowedMethod(String methodName, Object base, Method method) {
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

    /**
     * Runs a closure in sandbox environment.
     *
     * IMPORTANT:
     * Avoid object exchange from in to outside the sandbox environment (ie. parameters pass or returns).
     * As the sandbox environment has its own ClassLoader, trying to use any 'non-basic'
     * (ie. not loaded by the bootstrap ClassLoader) JVM object will fail.
     * If you are having trouble with inane ClassNotFoundException(s), this is probably the case.
     *
     * Example of safe classes are Map, String, Object, and primitives, but there are many others besides these.
     *
     * If you absolutely have to pass a custom object, you can try serializing the it, passing the bytes,
     * and then deserialize it on the other side, for instance, using ObjectOutputStream/ObjectInputStream.
     *
     * @param cls function to run into the sandbox
     * @return the return of the cls closure
     */
    protected <T> T inSandbox(Closure<T> cls) {
        return helper.inSandbox(cls)
    }


}
