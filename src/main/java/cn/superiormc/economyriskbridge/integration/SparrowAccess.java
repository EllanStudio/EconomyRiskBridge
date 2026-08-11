package cn.superiormc.economyriskbridge.integration;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.method.SparrowMethod;
import org.bukkit.event.Event;

import java.lang.invoke.MethodHandle;

import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mNamedNoRemap;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mTakeArguments;

/** Small typed facade around Sparrow Reflection's cross-ClassLoader MethodHandle path. */
final class SparrowAccess {

    private SparrowAccess() {
    }

    static Class<? extends Event> eventClass(ClassLoader loader, String className)
        throws ReflectiveOperationException {
        // Sparrow 0.27 does not yet expose its ClassLoader-aware lookup overload; load the
        // optional plugin class through its owner, then let Sparrow resolve every member.
        Class<?> found = loader.loadClass(className);
        try {
            return found.asSubclass(Event.class);
        } catch (ClassCastException exception) {
            throw new ReflectiveOperationException(className + " is not a Bukkit event", exception);
        }
    }

    static Accessor required(Class<?> owner, String name, Class<?>... argumentTypes)
        throws ReflectiveOperationException {
        Accessor accessor = optional(owner, name, argumentTypes);
        if (accessor == null) {
            throw new NoSuchMethodException(owner.getName() + "#" + name);
        }
        return accessor;
    }

    static Accessor optional(Class<?> owner, String name, Class<?>... argumentTypes)
        throws ReflectiveOperationException {
        try {
            SparrowMethod method = SparrowClass.of(owner).getSparrowMethod(
                mNamedNoRemap(name).and(mTakeArguments(argumentTypes)));
            if (method == null) {
                return null;
            }
            MethodHandle handle = method.unreflect();
            if (handle == null) {
                throw new ReflectiveOperationException("Sparrow could not unreflect " + owner.getName()
                    + "#" + name);
            }
            return new Accessor(handle, method.method.getReturnType(), owner.getName() + "#" + name);
        } catch (ReflectiveOperationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReflectiveOperationException("Sparrow could not bind " + owner.getName()
                + "#" + name, exception);
        }
    }

    static Object invoke(Accessor accessor, Object instance) throws ReflectiveOperationException {
        try {
            return accessor.handle.invoke(instance);
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw invocationFailure(accessor, throwable);
        }
    }

    static Object invoke(Accessor accessor, Object instance, Object argument)
        throws ReflectiveOperationException {
        try {
            return accessor.handle.invoke(instance, argument);
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw invocationFailure(accessor, throwable);
        }
    }

    static Object invoke(Accessor accessor, Object instance, Object first, Object second)
        throws ReflectiveOperationException {
        try {
            return accessor.handle.invoke(instance, first, second);
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw invocationFailure(accessor, throwable);
        }
    }

    private static ReflectiveOperationException invocationFailure(Accessor accessor, Throwable throwable) {
        return new ReflectiveOperationException("Sparrow invocation failed for " + accessor.description,
            throwable);
    }

    record Accessor(MethodHandle handle, Class<?> returnType, String description) {
    }
}
