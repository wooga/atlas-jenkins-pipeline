package net.wooga.jenkins.pipeline.test.specifications

import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import net.wooga.jenkins.pipeline.test.sandbox.PackageWhitelist
import net.wooga.jenkins.pipeline.test.sandbox.SandboxDeclarativePipelineTest
import net.wooga.jenkins.pipeline.test.sandbox.SandboxPipelineTestHelper
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.GenericWhitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist
//TODO check this out: https://github.com/jenkinsci/script-security-plugin/blob/master/src/main/resources/org/jenkinsci/plugins/scriptsecurity/sandbox/whitelists/generic-whitelist
abstract class SandboxedDeclarativeJenkinsSpec extends DeclarativeJenkinsSpec {


    abstract String[] getWhitelistedPackages()
    DeclarativePipelineTest jenkinsTest;

    @Override
    DeclarativePipelineTest getJenkinsTest() {
        if(jenkinsTest == null) {
            def extraWhitelist = whitelistedPackages.collect { new PackageWhitelist(it) }
            def whitelist = [new GenericWhitelist(),
                             new PackageWhitelist("com.lesfurets.jenkins")]
            whitelist.addAll(extraWhitelist)
            jenkinsTest = new SandboxDeclarativePipelineTest(new ProxyWhitelist(whitelist))
        }
        return jenkinsTest
    }

    @Override
    SandboxPipelineTestHelper getHelper() {
        return super.helper as SandboxPipelineTestHelper
    }

    /**
     * Runs a closure in sandbox environment.
     *<br><br>
     * IMPORTANT:<br>
     * Object exchange from in to outside the sandbox environment (ie. parameters pass or returns), is non-trivial.
     * As the sandbox environment has its own ClassLoader, trying to use any 'non-basic'
     * (ie. not loaded by the bootstrap ClassLoader) JVM object will fail.
     * If you are having trouble with inane ClassNotFoundException(s), this is probably the case.
     *<br><br>
     * Example of safe classes are Map, String, Object, and primitives, but there are many others besides these.
     *<br><br>
     * If you have to pass in a custom object, `helper.cloneToSandbox` will generate a clone of that object in the sandbox classLoader,
     * which then can be used inside the sandbox.
     * For returns, you can use inSandboxClonedReturn or `helper.cloneTo`, to generate a clone of a sandboxed object in a non-sandbox environment.
     *<br>
     * @param cls function to run into the sandbox
     * @return the direct return of the cls closure
     */
    protected <T> T inSandbox(Closure<T> cls) {
        return helper.inSandbox(cls)
    }

    /**
     * Runs a closure in sandbox environment.
     * Limitations from inSandbox(Closure) still applies, except for the return object,
     * which is transfered to this class classLoader.
     *
     * @param cls function to run into the sandbox
     * @return cloned return of the cls closure, transplanted to this class' class loader.
     */
    protected <T> T inSandboxClonedReturn(Closure<T> cls) {
        def sandboxedReturn = inSandbox { cls() }
        return helper.cloneTo(sandboxedReturn, this.class.classLoader)
    }

    /**
     * Loads a script and the javaLibCheck and publish side scripts inside the sandbox.
     * @param path - path to the script to be loaded.
     * @param sideScripts - helper scripts to that will be loaded beforehand and in the same binding as your desired script
     * object that will be passed to scripts
     * @param varBindingOps - convenience closure to execute operations over the binding
     * object that will be passed to scripts
     * @return Script object representing the loaded script
     */
    Script loadSandboxedScript(String path, List<String> sideScripts=[], Closure varBindingOps={}) {
        varBindingOps.setDelegate(binding.variables)
        varBindingOps(binding.variables)
        sideScripts.each { registerSideScript(it, binding) }
        return helper.loadScript(path, binding)
    }
}
