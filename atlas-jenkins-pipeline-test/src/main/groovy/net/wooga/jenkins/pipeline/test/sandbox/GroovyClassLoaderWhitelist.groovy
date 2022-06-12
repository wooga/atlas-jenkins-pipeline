package net.wooga.jenkins.pipeline.test.sandbox

import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

//Based on ClassLoaderWhitelist from jenkins' security plugin
class GroovyClassLoaderWhitelist extends Whitelist {

    private final ClassLoader scriptLoader;

    public GroovyClassLoaderWhitelist(ClassLoader scriptLoader) {
        this.scriptLoader = scriptLoader;
    }

    private boolean permitClassLoader(ClassLoader classLoader) {
        //Groovy uses these inner loaders to load scripts tru GSE.
        // This isn't a problem in jenkins itself for some reason, but it is in our tests,
        // so we have to find the nearest non-innerloader parent.
        if(classLoader!= null && classLoader instanceof GroovyClassLoader.InnerLoader) {
            return permitClassLoader(classLoader.parent)
        }
        return classLoader == scriptLoader
    }

    private boolean permit(Class<?> declaringClass) {
        return permitClassLoader(declaringClass.classLoader)
    }

    @Override boolean permitsMethod(Method method, Object receiver, Object[] args) {
        return permit(method.getDeclaringClass());
    }

    @Override boolean permitsConstructor(Constructor<?> constructor, Object[] args) {
        return permit(constructor.getDeclaringClass());
    }

    @Override boolean permitsStaticMethod(Method method, Object[] args) {
        return permit(method.getDeclaringClass());
    }

    @Override boolean permitsFieldGet(Field field, Object receiver) {
        return permit(field.getDeclaringClass());
    }

    @Override boolean permitsFieldSet(Field field, Object receiver, Object value) {
        return permit(field.getDeclaringClass());
    }

    @Override boolean permitsStaticFieldGet(Field field) {
        return permit(field.getDeclaringClass());
    }

    @Override boolean permitsStaticFieldSet(Field field, Object value) {
        return permit(field.getDeclaringClass());
    }
}
