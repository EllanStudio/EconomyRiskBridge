package cn.superiormc.economyriskbridge.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SparrowAccessTest {

    @Test
    void bindsAndInvokesPublicMethodsThroughSparrow() throws Exception {
        Example target = new Example();
        SparrowAccess.Accessor value = SparrowAccess.required(Example.class, "value");
        SparrowAccess.Accessor plus = SparrowAccess.required(Example.class, "plus", int.class);
        SparrowAccess.Accessor join = SparrowAccess.required(Example.class, "join", String.class, String.class);

        assertEquals("ready", SparrowAccess.invoke(value, target));
        assertEquals(7, SparrowAccess.invoke(plus, target, 5));
        assertEquals("left:right", SparrowAccess.invoke(join, target, "left", "right"));
        assertNull(SparrowAccess.optional(Example.class, "missing"));
    }

    public static final class Example {

        public String value() {
            return "ready";
        }

        public int plus(int value) {
            return value + 2;
        }

        public String join(String left, String right) {
            return left + ":" + right;
        }
    }
}
