package tools.sandbox

import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist

import javax.annotation.CheckForNull
import javax.annotation.Nonnull
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Whitelist that allows all methods belonging to a given package and its subpackages.
 */
class PackageWhitelist extends Whitelist {

    final String packagePrefix

    PackageWhitelist(String packagePrefix) {
        this.packagePrefix = packagePrefix
    }

    boolean allowed(Class<?> cls) {
        return cls.package != null && cls.package.name.startsWith(packagePrefix)
    }

    @Override
    boolean permitsMethod(@Nonnull Method method, @Nonnull Object receiver, @Nonnull Object[] args) {
        return allowed(method.getDeclaringClass()) || allowed(receiver.getClass())
    }

    @Override
    boolean permitsConstructor(@Nonnull Constructor<?> constructor, @Nonnull Object[] args) {
        return allowed(constructor.getDeclaringClass())
    }

    @Override
    boolean permitsStaticMethod(@Nonnull Method method, @Nonnull Object[] args) {
        return allowed(method.getDeclaringClass())
    }

    @Override
    boolean permitsFieldGet(@Nonnull Field field, @Nonnull Object receiver) {
        return allowed(field.getDeclaringClass())
    }

    @Override
    boolean permitsFieldSet(@Nonnull Field field, @Nonnull Object receiver, @CheckForNull Object value) {
        return allowed(field.getDeclaringClass()) || allowed(receiver.getClass())
    }

    @Override
    boolean permitsStaticFieldGet(@Nonnull Field field) {
        return allowed(field.getDeclaringClass())
    }

    @Override
    boolean permitsStaticFieldSet(@Nonnull Field field, @CheckForNull Object value) {
        return allowed(field.getDeclaringClass())
    }
}
