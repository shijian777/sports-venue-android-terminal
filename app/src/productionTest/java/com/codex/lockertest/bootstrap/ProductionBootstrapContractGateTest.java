package com.codex.lockertest.bootstrap;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ProductionBootstrapContractGateTest {
    @Test
    public void documentedThreeEndpointContractIsLiveApproved() {
        assertTrue(ProductionBootstrapContractGate.isLiveApproved());
        assertTrue(ProductionBootstrapContractGate.isLiveApproved());
    }

    @Test
    public void gateHasNoStateOrApprovalInjectionSeam() {
        assertEquals(0, ProductionBootstrapContractGate.class.getDeclaredFields().length);

        Constructor<?>[] constructors =
                ProductionBootstrapContractGate.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(0, constructors[0].getParameterTypes().length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));

        Method[] methods = ProductionBootstrapContractGate.class.getDeclaredMethods();
        assertEquals(1, methods.length);
        assertEquals("isLiveApproved", methods[0].getName());
        assertEquals(boolean.class, methods[0].getReturnType());
        assertEquals(0, methods[0].getParameterTypes().length);
        assertTrue(Modifier.isPublic(methods[0].getModifiers()));
        assertTrue(Modifier.isStatic(methods[0].getModifiers()));
    }
}
