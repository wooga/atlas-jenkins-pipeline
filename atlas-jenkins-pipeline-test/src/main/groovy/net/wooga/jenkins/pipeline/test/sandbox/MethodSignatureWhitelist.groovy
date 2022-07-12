package net.wooga.jenkins.pipeline.test.sandbox

import com.lesfurets.jenkins.unit.MethodSignature
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.AbstractWhitelist

import javax.annotation.Nonnull
import java.lang.reflect.Method


/**
 * Whitelist class that allows given method signatures, even when these signatures are called using 'invokeMethod'.
 */
class MethodSignatureWhitelist extends AbstractWhitelist {

    Collection<MethodSignature> allowedMethodCallbacks;

    MethodSignatureWhitelist(Collection<MethodSignature> allowedMethodCallbacks) {
        this.allowedMethodCallbacks = allowedMethodCallbacks
    }

    boolean allows(Method method, Object[] args) {
        def candidateSignature = signatureForMethod(method, args)
        if(method.name == "getProperty") {
            return ["any", "none"].contains(args[0])
        }
        return allowedMethodCallbacks.any{
            it == candidateSignature ||
                    (it.name == candidateSignature.name &&
                            it.args.length == candidateSignature.args.length) }
    }

    @Override
    boolean permitsMethod(@Nonnull Method method, @Nonnull Object receiver, @Nonnull Object[] args) {
        return allows(method, args)
    }

    @Override
    boolean permitsStaticMethod(Method method, Object[] args) {
        return allows(method, args)

    }

    static MethodSignature signatureForMethod(Method method, Object[] args) {
        MethodSignature candidateSignature = new MethodSignature(method.name, *(args.collect {it.getClass()}))
        if(method.name == "invokeMethod") {
            def invokeArgsClasses = args.collect{it.getClass()}
            if(invokeArgsClasses == [String, Object[]]) {
                return signatureFromArgs(args[0], args[1])
            }
            if(invokeArgsClasses.size() > 2 && invokeArgsClasses[-2..-1] == [String, Object[]]) {
                return signatureFromArgs(args[-2], args[-1])
            }
        }
        return candidateSignature
    }


    private static MethodSignature signatureFromArgs(Object name, Object args) {
        def argsClasses = args.collect{obj -> obj.getClass()}
        return new MethodSignature(name, *argsClasses)
    }


}
