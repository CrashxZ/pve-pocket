// SPDX-License-Identifier: GPL-3.0-or-later
package com.homelabpocket.api;

import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.util.JsonUtil;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ProxmoxApiParsingTest {

    @Test
    public void testParseNodeListJson() throws Exception {
        String mockResponse = "{\"data\":[" +
                "{\"node\":\"pve-1\",\"status\":\"online\",\"cpu\":0.142,\"maxcpu\":8," +
                "\"mem\":17179869184,\"maxmem\":34359738368,\"disk\":107374182400,\"maxdisk\":1000000000000," +
                "\"uptime\":123456,\"pveversion\":\"pve-manager/8.1.4/1234\"}," +
                "{\"node\":\"pve-2\",\"status\":\"offline\",\"cpu\":null,\"maxcpu\":0," +
                "\"mem\":0,\"maxmem\":0,\"disk\":0,\"maxdisk\":0,\"uptime\":0}" +
                "]}";

        JSONArray items = JsonUtil.getDataArray(mockResponse);
        assertEquals(2, items.length());

        JSONObject n1 = items.getJSONObject(0);
        assertEquals("pve-1", n1.getString("node"));
        assertEquals("online", n1.getString("status"));
        assertEquals(0.142, n1.getDouble("cpu"), 0.001);
        assertEquals(8, n1.getInt("maxcpu"));
        assertEquals(17179869184L, n1.getLong("mem"));
        assertEquals(34359738368L, n1.getLong("maxmem"));

        JSONObject n2 = items.getJSONObject(1);
        assertEquals("pve-2", n2.getString("node"));
        assertEquals("offline", n2.getString("status"));
    }

    @Test
    public void testParseGuestListJson() throws Exception {
        String mockResponse = "{\"data\":[" +
                "{\"vmid\":100,\"name\":\"web-server\",\"status\":\"running\",\"cpu\":0.05,\"cpus\":2," +
                "\"mem\":2147483648,\"maxmem\":4294967296,\"disk\":10737418240,\"maxdisk\":32212254720," +
                "\"uptime\":86400,\"tags\":\"prod,web\"}," +
                "{\"vmid\":101,\"name\":\"test-vm\",\"status\":\"stopped\",\"cpu\":0.0,\"cpus\":1," +
                "\"mem\":0,\"maxmem\":2147483648,\"uptime\":0}" +
                "]}";

        JSONArray items = JsonUtil.getDataArray(mockResponse);
        assertEquals(2, items.length());

        JSONObject g1 = items.getJSONObject(0);
        assertEquals(100, g1.getInt("vmid"));
        assertEquals("web-server", g1.getString("name"));
        assertEquals("running", g1.getString("status"));
        assertEquals(2, g1.getInt("cpus"));
        assertEquals(2147483648L, g1.getLong("mem"));
        assertEquals(4294967296L, g1.getLong("maxmem"));
        assertEquals(10737418240L, g1.getLong("disk"));
        assertEquals(32212254720L, g1.getLong("maxdisk"));
        assertEquals("prod,web", g1.getString("tags"));
    }

    @Test
    public void testParseTermproxyResponse() throws Exception {
        String mockResponse = "{\"data\":{" +
                "\"port\":5900," +
                "\"ticket\":\"PVE:root@pam:01234567:randomtermproxyticket\"," +
                "\"user\":\"root@pam\"" +
                "}}";

        JSONObject data = JsonUtil.getDataObject(mockResponse);
        assertEquals(5900, data.getInt("port"));
        assertEquals("PVE:root@pam:01234567:randomtermproxyticket", data.getString("ticket"));
        assertEquals("root@pam", data.getString("user"));
    }
}
