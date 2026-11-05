package com.homelabpocket.api;
import com.homelabpocket.model.*;
import org.junit.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Controller-only acceptance probes; real loopback HTTPS from existing fixture. */
public class IndependentPdmAcceptanceTest {
    static PdmApiDriver driver;
    static AuthCredentials auth;
    static AtomicInteger posts, polls;
    static Object fixture(String name) throws Exception {
        Field f=PdmSafetyTest.class.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    @BeforeClass public static void setup() throws Exception {
        PdmSafetyTest.setUpClass();
        driver=new PdmApiDriver(new ServerConfig("review", "review", "127.0.0.1",
            (Integer)fixture("mockPort"), ServerConfig.ServerType.PDM, (String)fixture("mockFingerprint")));
        auth=AuthCredentials.ticket("root@pam", "synthetic-ticket", "synthetic-csrf", System.currentTimeMillis()+3600000);
        posts=(AtomicInteger)fixture("powerPostRequests"); polls=(AtomicInteger)fixture("taskPollRequests");
    }
    @AfterClass public static void teardown(){PdmSafetyTest.tearDownClass();}
    @Test public void absentNodeMustNotDispatchPower() throws Exception {
        int before=posts.get();
        boolean rejected=false;
        try {driver.executeGuestPowerAction("cluster-west", "", 101, GuestInfo.GuestType.QEMU, "start", auth, 3000);}
        catch(ApiException expected){rejected=true;}
        assertEquals("Missing node must not silently auto-target a possibly migrated guest", before, posts.get());
        assertTrue("Explicit full target identity must be required", rejected);
    }
    @Test public void mismatchedRemoteTaskMustNotBeAcceptedAsSuccess() throws Exception {
        int before=polls.get(); boolean rejected=false;
        try {driver.pollTask("cluster-west", "pve:cluster-east!UPID:pve-node-01:00001234:00005678:67B4A9D1:qmstart:101:root@pam:", auth, 3000);}
        catch(ApiException expected){rejected=true;}
        assertTrue("Task identity mismatch must be rejected before the permissive mock can report OK",rejected);
        assertEquals(before,polls.get());
    }
    @Test public void conflictingPowerForSameGuestMustBeBlocked() {
        ActionGuard guard=new ActionGuard();
        assertTrue(guard.tryAcquireGuestAction("pdm", "west/node", "qemu",101,"shutdown"));
        assertFalse("Force-stop must not acquire the same guest while shutdown is being monitored",
            guard.tryAcquireGuestAction("pdm", "west/node", "qemu",101,"stop"));
    }
}
