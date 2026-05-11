/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeCallableTest {

    @Test
    void testInferArchFromServerType_arm() {
        assertEquals("arm64", NodeCallable.inferArchFromServerType("cax11"));
        assertEquals("arm64", NodeCallable.inferArchFromServerType("cax21"));
        assertEquals("arm64", NodeCallable.inferArchFromServerType("cax31"));
        assertEquals("arm64", NodeCallable.inferArchFromServerType("cax41"));
        assertEquals("arm64", NodeCallable.inferArchFromServerType("CAX41"));
    }

    @Test
    void testInferArchFromServerType_x86() {
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cpx11"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cpx21"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cpx31"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cpx42"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cpx62"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cx22"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cx32"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cx42"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cx52"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("ccx13"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("ccx23"));
    }

    @Test
    void testInferArchFromServerType_null() {
        assertEquals("x86_64", NodeCallable.inferArchFromServerType(null));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType(""));
        // Unknown prefix: falls through to x86_64 default with a WARN logged
        // (so an operator can spot a new Hetzner family in the logs).
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("unknown"));
    }

    /**
     * Regression: prior implementation classified any "cax" prefix as ARM and
     * everything else as x86_64. After tightening, unknown prefixes (e.g. a
     * future Hetzner family like "aax" or "cxx") still default to x86_64 so
     * existing setups don't break, but the hardware uname check remains the
     * safety net for confirming the arch. This test pins that contract.
     */
    @Test
    void testInferArchFromServerType_unknownPrefixDefaultsToX86() {
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("aax11"));   // hypothetical ARM
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cxx11"));   // hypothetical x86
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("graviton"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("xyz"));
    }

    /**
     * Regression: known x86 prefixes (cx, cpx, ccx) and known ARM (cax)
     * are positively matched. If a future change accidentally moves one
     * out of the known-set, this test will fail before production.
     */
    @Test
    void testInferArchFromServerType_knownPrefixesArePositive() {
        // ARM: cax family
        assertEquals("arm64", NodeCallable.inferArchFromServerType("cax11"));
        assertEquals("arm64", NodeCallable.inferArchFromServerType("cax41"));
        // x86: cx, cpx, ccx families
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cx22"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("cpx62"));
        assertEquals("x86_64", NodeCallable.inferArchFromServerType("ccx33"));
    }
}
