package com.homelabpocket.api;
import com.homelabpocket.model.*; import org.junit.*; import java.util.*; import java.util.concurrent.atomic.AtomicInteger; import static org.junit.Assert.*;
public class RenewedAcceptanceTest {
 @BeforeClass public static void setup() throws Exception {IndependentPdmAcceptanceTest.setup();}
 @AfterClass public static void close(){IndependentPdmAcceptanceTest.teardown();}
 @Test public void distinctServerAndNodeCannotCollide(){ActionGuard g=new ActionGuard(); assertTrue(g.tryAcquireGuestAction("a:b","c","qemu",101,"start"));assertTrue("Distinct server/node tuple collided",g.tryAcquireGuestAction("a","b:c","qemu",101,"start"));}
 @Test public void expiredEnrichmentBudgetMustNotQuery() throws Exception {PdmApiDriver.clearStorageCache(); AtomicInteger requests=(AtomicInteger)IndependentPdmAcceptanceTest.fixture("storageStatusRequests");int before=requests.get();IndependentPdmAcceptanceTest.driver.enrichStoragePools("cluster-west/pve-node-01",Arrays.asList(new StoragePool("local-zfs","unknown",100,20,80,true,true,false,"")),IndependentPdmAcceptanceTest.auth,0);assertEquals("Expired aggregate budget still made HTTPS request",before,requests.get());}
}
