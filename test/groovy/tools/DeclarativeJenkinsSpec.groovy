package tools

import com.lesfurets.jenkins.unit.MethodCall
import com.lesfurets.jenkins.unit.PipelineTestHelper
import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import com.lesfurets.jenkins.unit.declarative.PostDeclaration
import com.lesfurets.jenkins.unit.declarative.WhenDeclaration
import org.apache.commons.lang3.ClassUtils
import spock.lang.Shared
import spock.lang.Specification

import javax.swing.text.html.Option
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.Lock
import java.util.stream.IntStream

abstract class DeclarativeJenkinsSpec extends Specification {

    static Object lock = new Object()
    @Shared DeclarativePipelineTest jenkinsTest;
    @Shared Binding binding
    @Shared PipelineTestHelper helper
    @Shared FakeCredentialStorage credentials
    @Shared FakeMethodCalls calls
    @Shared List<Map> usedEnvironments
    @Shared Map<String, Map> jenkinsStash

    def setupSpec() {
        usedEnvironments = new ArrayList<>()
        jenkinsStash = new HashMap<>()
        jenkinsTest = new DeclarativePipelineTest() {}
        jenkinsTest.setUp()
        credentials = new FakeCredentialStorage()
        calls = new FakeMethodCalls(jenkinsTest.helper)

        helper = jenkinsTest.helper
        binding = jenkinsTest.binding
        addLackingDSLTerms()
        populateJenkinsDefaultEnvironment(binding)
    }

    def setup() {
        registerPipelineFakeMethods(helper)
    }

    def cleanup() {
        helper?.callStack?.clear()
        usedEnvironments.clear()
        credentials.wipe()
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
                binding.env.putAll(env)
                binding.variables.putAll(env)
                try {
                    cls()
                } finally {
                    usedEnvironments.add(deepCopy(binding.env)as Map)
                    env.each {
                        binding.env.remove(it.key)
                        binding.variables.remove(it.key)
                    }
                }
            }
            registerAllowedMethod("cleanWs") {}
            registerAllowedMethod("checkout", [String]) {}
            registerAllowedMethod("publishHTML", [HashMap]) {}
            registerAllowedMethod("fileExists", [String]) {String path -> new File(path).exists() }
            registerAllowedMethod("readFile", [String]) {String path -> new File(path).text }
            registerAllowedMethod("usernamePassword", [Map], credentials.&usernamePassword)
            //TODO: make this generate KEY_USR and KEY_PWD environment
            registerAllowedMethod("credentials", [String]) {String key -> credentials[key]}
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
        }
    }

    private static void addLackingDSLTerms() {
        GenericPipelineDeclaration.metaClass.any = "any"
        WhenDeclaration.metaClass.beforeAgent = { bool -> null }
        PostDeclaration.metaClass.cleanup = { clj -> clj() }
        PostDeclaration.metaClass.cleanWs = {}
    }

    protected Script loadScript(String name, Closure varBindingOps={}, boolean reloadSideScripts = true) {
        varBindingOps.setDelegate(binding.variables)
        varBindingOps(binding.variables)
        if(reloadSideScripts) {
            registerSideScript("vars/javaLibCheck.groovy", binding)
            registerSideScript("vars/publish.groovy", binding)
        }

        return helper.loadScript(name, binding)
    }

    protected void registerSideScript(String scriptPath, Binding binding) {
        def scriptName = new File(scriptPath).name.replace(".groovy", "")
        def script = helper.loadScript(scriptPath, binding)
        script.class.getDeclaredMethods().findAll {return it.name == "call" }.each { callMethod ->
            registerAllowedMethod(scriptName, script, callMethod)
        }
    }

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

    protected static def deepCopy(orig) {
        def bos = new ByteArrayOutputStream()
        def oos = new ObjectOutputStream(bos)
        oos.writeObject(orig); oos.flush()
        def bin = new ByteArrayInputStream(bos.toByteArray())
        def ois = new ObjectInputStream(bin)
        return ois.readObject()
    }
}
