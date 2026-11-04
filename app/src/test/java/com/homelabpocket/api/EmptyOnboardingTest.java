// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.api;

import com.homelabpocket.model.ServerConfig;

import org.junit.Test;

import static org.junit.Assert.*;

public class EmptyOnboardingTest {

    @Test
    public void testServerConfigUrlFormatting() {
        // Reserved test net documentation address per RFC 5737
        ServerConfig pve = new ServerConfig("cfg-1", "Lab PVE", "192.0.2.10", 8006,
                ServerConfig.ServerType.PVE, "AA:BB:CC:DD", true);

        assertEquals("https://192.0.2.10:8006", pve.getBaseUrl());
        assertEquals("https://192.0.2.10:8006/api2/json", pve.getApiBaseUrl());
        assertEquals("Lab PVE", pve.name);
        assertEquals(ServerConfig.ServerType.PVE, pve.type);
        assertTrue(pve.enforceCertPin);

        ServerConfig pdm = new ServerConfig("cfg-2", "Lab PDM", "192.0.2.20", 8443,
                ServerConfig.ServerType.PDM, null, true);

        assertEquals("https://192.0.2.20:8443", pdm.getBaseUrl());
        assertEquals("https://192.0.2.20:8443/api2/json", pdm.getApiBaseUrl());
        assertEquals(ServerConfig.ServerType.PDM, pdm.type);
        assertNull(pdm.pinnedCertFingerprint);
    }

    @Test
    public void testFirstRunSecurityIsolation() {
        // Ensure no static production credentials exist in ServerConfig
        ServerConfig config = new ServerConfig("test-id", "Custom Server", "192.0.2.1", 8006,
                ServerConfig.ServerType.PVE, null);

        assertNull(config.pinnedCertFingerprint);
        assertFalse(config.toString().contains("password"));
        assertFalse(config.toString().contains("token"));
    }
}
