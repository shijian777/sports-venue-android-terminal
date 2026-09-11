package com.codex.lockertest.bootstrap;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ProductionBootstrapAssemblySourceTest {
    @Test
    public void productionAssemblyCallsOnlyTheNoArgReadOnlyFactory() throws Exception {
        String assembly = read("app/src/production/java/com/codex/lockertest/bootstrap/"
                + "BootstrapAssembly.java");
        assertTrue(assembly.contains("ProductionBootstrapRuntimeFactory.create()"));
        assertFalse(assembly.contains("ProductionBootstrapService"));
        assertFalse(assembly.contains("HttpsUrlConnectionTransport"));
        assertFalse(assembly.contains("SystemProtocolClock"));
        assertFalse(assembly.contains("Executor"));
        assertFalse(assembly.contains("BootstrapScheduler"));
    }

    @Test
    public void actualFactoryChecksCredentialAndGateThenOwnsFixedLiveDependencies()
            throws Exception {
        String factory = read("app/src/production/java/com/codex/lockertest/bootstrap/"
                + "ProductionBootstrapRuntimeFactory.java");
        assertTrue(factory.contains("ProductionSecretProvider.copyOrEmpty()"));
        assertTrue(factory.contains("ProductionBootstrapContractGate.isLiveApproved()"));
        assertTrue(factory.indexOf("sysCode.length == 0")
                < factory.indexOf("ProductionBootstrapContractGate.isLiveApproved()"));
        assertTrue(factory.contains("finally"));
        assertTrue(factory.contains("Arrays.fill(sysCode, '\\0')"));
        assertTrue(factory.contains("ProductionBootstrapService.createLive(sysCode)"));
        assertTrue(factory.contains("new ProductionBootstrapScheduler()"));
        assertTrue(factory.contains("new Rk3288DeviceSerialProvider()"));
        assertTrue(factory.contains("new ProductionBootstrapRuntime("));
        assertTrue(factory.contains("ownershipTransferred = true"));
        assertFalse(factory.contains("new HttpsUrlConnectionTransport"));
        assertFalse(factory.contains("new SystemProtocolClock"));
        assertFalse(factory.contains("System.getenv"));
        assertFalse(factory.contains("System.getProperty"));
        assertFalse(factory.contains("SharedPreferences"));
        assertFalse(factory.contains("BuildConfig"));
    }

    private static String read(String relative) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }
}
