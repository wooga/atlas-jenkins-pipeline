package net.wooga.jenkins.pipeline.test.sandbox

import com.lesfurets.jenkins.unit.PipelineTestHelper
import org.codehaus.groovy.control.CompilerConfiguration
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.ClassLoaderWhitelist
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist

/**
 * Pipeline version test that integrates its own Groovy compilations shenanigans with the groovy sandbox ones.
 */
class SandboxPipelineTestHelper extends PipelineTestHelper {

    private final Whitelist whitelist;
    GroovySandbox sandbox

    SandboxPipelineTestHelper(Whitelist whitelist) {
        this.whitelist = whitelist
    }

    @Override
    PipelineTestHelper init() {
        super.init()
        this.scriptRoots = this.scriptRoots + [".", "src"]
        this.gse = createSandboxGSE(gse)
        this.sandbox = new GroovySandbox().withWhitelist(createWhitelist())
        return this
    }

    private Whitelist createWhitelist() {
        return new ProxyWhitelist(whitelist,
                new ClassLoaderWhitelist(gse.groovyClassLoader),
                new MethodSignatureWhitelist(allowedMethodCallbacks.keySet()))
    }

    /**
     * Creates a new GroovyScriptEngine that runs inside a jenkins sandbox.
     * @param baseGse - GroovyScriptEngine to be used as base.
     * Its parentClassLoader and CompilerConfiguration will be added to the sandbox ones.
     * @return a Sandbox-enabled GroovyScriptEngine.
     * Please mind that it will refuse any non-sandboxed or sandboxed and non-whitelisted code.
     */
    private GroovyScriptEngine createSandboxGSE(GroovyScriptEngine baseGse) {
        CompilerConfiguration sandboxCc = GroovySandbox.createSecureCompilerConfiguration();
        def sandboxCl = GroovySandbox.createSecureClassLoader(baseGse.parentClassLoader)
        sandboxCc.compilationCustomizers.each {
            baseGse.config.addCompilationCustomizers(it)
        }
        baseGse.config.disabledGlobalASTTransformations = sandboxCc.disabledGlobalASTTransformations

        def sandboxGse = new GroovyScriptEngine(this.scriptRoots, sandboxCl)
        sandboxGse.setConfig(baseGse.config)
        return sandboxGse
    }

    @Override
    Script loadScript(String scriptName, Binding binding) {
        Script script = inSandbox {
            super.loadScript(scriptName, binding)
        }
        return script
    }

    public <T> T inSandbox(Closure<T> cls) {
        GroovySandbox.Scope scope = sandbox.enter()
        try {
            return cls()
        } finally {
            scope.close()
        }
    }


}