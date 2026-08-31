package com.leslie.colorosnote.core.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CacheTrimRegistryTest {

    private final List<Float> calls = new ArrayList<>();
    private final CacheTrimRegistry.TrimTarget target = calls::add;

    @After
    public void tearDown() {
        CacheTrimRegistry.unregister(target);
    }

    @Test
    public void trimAll_invokesRegisteredTargetsWithFraction() {
        CacheTrimRegistry.register(target);
        CacheTrimRegistry.trimAll(0.25f);
        assertEquals(1, calls.size());
        assertEquals(0.25f, calls.get(0), 1e-6f);
    }

    @Test
    public void unregister_stopsFurtherTrims() {
        CacheTrimRegistry.register(target);
        CacheTrimRegistry.unregister(target);
        CacheTrimRegistry.trimAll(0.5f);
        assertTrue(calls.isEmpty());
    }

    @Test
    public void trimAll_clampsFractionToUnitRange() {
        CacheTrimRegistry.register(target);
        CacheTrimRegistry.trimAll(1.5f);
        CacheTrimRegistry.trimAll(-0.2f);
        assertEquals(1f, calls.get(0), 1e-6f);
        assertEquals(0f, calls.get(1), 1e-6f);
    }

    @Test
    public void register_isIdempotent() {
        CacheTrimRegistry.register(target);
        CacheTrimRegistry.register(target);
        CacheTrimRegistry.trimAll(0.5f);
        assertEquals(1, calls.size());
    }

    @Test
    public void trimAll_survivesThrowingTarget() {
        CacheTrimRegistry.TrimTarget bad = f -> {
            throw new RuntimeException("boom");
        };
        CacheTrimRegistry.register(bad);
        try {
            CacheTrimRegistry.register(target);
            CacheTrimRegistry.trimAll(0.5f);
            assertEquals(1, calls.size());
        } finally {
            CacheTrimRegistry.unregister(bad);
        }
    }
}
