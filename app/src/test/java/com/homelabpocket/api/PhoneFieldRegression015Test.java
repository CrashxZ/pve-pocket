// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Field Regression 0.1.5 Behavioral Test Matrix
package com.homelabpocket.api;

import com.homelabpocket.HomelabApp;
import com.homelabpocket.scheduler.AutoRefreshScheduler;
import com.homelabpocket.scheduler.AutoRefreshSchedulerTest;
import com.homelabpocket.scheduler.RefreshPreferenceStore;
import com.homelabpocket.security.CredentialStore;
import android.content.Context;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.widget.Toast;
import android.os.AsyncTask;
import java.lang.reflect.Proxy;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.GuestInfo;
import com.homelabpocket.model.NodeStatus;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.model.StoragePool;
import com.homelabpocket.model.TaskResult;
import com.homelabpocket.security.PinnedTrustManager;
import com.homelabpocket.ui.UiPresentationPolicy;
import com.homelabpocket.ui.nodes.NodeDetailActivity;
import com.homelabpocket.ui.vms.VmDetailActivity;
import com.homelabpocket.ui.guests.GuestsView;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * RED behavioral regression suite for real phone failures:
 * 1. Proxmox task WARNINGS terminal classification (PVE + PDM).
 * 2. NodeDetailActivity owner load seam with multi-node same-remote storage provenance isolation.
 * 3. NodeDetailActivity owner missing remote/node unavailable handling.
 * 4. NodeDetailActivity lifecycle owner completion rejection/acceptance seam.
 * 5. Task result presentation seam genuinely wired to GuestsView and VmDetailActivity.
 * 6. Initial UI explicit unavailable state without fake zeros.
 * 7. Selected unreachable remote controls disabled / zero POST preflight rejection.
 * 8. Exact shutdown fixture with POST, path, CSRF, node body, qmshutdown UPID, and status poll identity.
 * 9. Truthful ack isolation from subsequent telemetry failure.
 */
public class PhoneFieldRegression015Test {

    private static HttpsServer mockServer;
    private static int mockPort;
    private static String mockFingerprint;

    private static final AtomicReference<String> resourcesPayload = new AtomicReference<>();
    private static final AtomicInteger resourcesStatusCode = new AtomicInteger(200);
    private static final AtomicLong resourcesDelayMs = new AtomicLong(0);
    private static final AtomicReference<java.util.concurrent.CountDownLatch> resourcesLatch = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicBoolean resourcesEntered = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final AtomicReference<String> taskStatusPayload = new AtomicReference<>();
    private static final AtomicInteger resourcesCallCount = new AtomicInteger(0);
    private static final AtomicInteger activeResourcesRequests = new AtomicInteger(0);
    private static final AtomicInteger maxConcurrentResourcesRequests = new AtomicInteger(0);
    private static final AtomicInteger postActionCount = new AtomicInteger(0);
    private static final AtomicReference<String> lastPostPath = new AtomicReference<>();
    private static final AtomicReference<String> lastPostMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastPostCsrf = new AtomicReference<>();
    private static final AtomicReference<String> lastPostBody = new AtomicReference<>();
    private static final AtomicReference<String> lastPollUpid = new AtomicReference<>();

    private AuthCredentials creds;
    private ServerConfig pdmConfig;
    private ServerConfig pveConfig;
    private Context context;

    @SuppressWarnings("unchecked")
    private static <T> T allocateInstance(Class<T> clazz) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object u = f.get(null);
            java.lang.reflect.Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);
            return (T) alloc.invoke(u, clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class NodeDetailFixture {
        final NodeDetailActivity activity;
        final android.widget.LinearLayout layoutStorage;
        final android.widget.LinearLayout layoutGuests;
        final android.widget.TextView tvCpu;
        final android.widget.ProgressBar progCpu;
        final android.widget.TextView tvRam;
        final android.widget.ProgressBar progRam;
        final android.widget.TextView tvDisk;
        final android.widget.ProgressBar progDisk;
        final android.widget.TextView tvUptime;
        final android.widget.TextView tvKernel;
        final android.widget.Button btnShell;

        NodeDetailFixture(NodeDetailActivity activity,
                          android.widget.LinearLayout layoutStorage,
                          android.widget.LinearLayout layoutGuests,
                          android.widget.TextView tvCpu,
                          android.widget.ProgressBar progCpu,
                          android.widget.TextView tvRam,
                          android.widget.ProgressBar progRam,
                          android.widget.TextView tvDisk,
                          android.widget.ProgressBar progDisk,
                          android.widget.TextView tvUptime,
                          android.widget.TextView tvKernel,
                          android.widget.Button btnShell) {
            this.activity = activity;
            this.layoutStorage = layoutStorage;
            this.layoutGuests = layoutGuests;
            this.tvCpu = tvCpu;
            this.progCpu = progCpu;
            this.tvRam = tvRam;
            this.progRam = progRam;
            this.tvDisk = tvDisk;
            this.progDisk = progDisk;
            this.tvUptime = tvUptime;
            this.tvKernel = tvKernel;
            this.btnShell = btnShell;
        }
    }

    private NodeDetailFixture createNodeDetailFixture() {
        NodeDetailActivity activity = allocateInstance(NodeDetailActivity.class);
        android.widget.LinearLayout layoutStorage = new android.widget.LinearLayout(context);
        android.widget.LinearLayout layoutGuests = new android.widget.LinearLayout(context);
        android.widget.TextView tvCpu = new android.widget.TextView(context);
        android.widget.ProgressBar progCpu = new android.widget.ProgressBar(context);
        android.widget.TextView tvRam = new android.widget.TextView(context);
        android.widget.ProgressBar progRam = new android.widget.ProgressBar(context);
        android.widget.TextView tvDisk = new android.widget.TextView(context);
        android.widget.ProgressBar progDisk = new android.widget.ProgressBar(context);
        android.widget.TextView tvUptime = new android.widget.TextView(context);
        android.widget.TextView tvKernel = new android.widget.TextView(context);
        android.widget.Button btnShell = new android.widget.Button(context);
        android.widget.TextView tvName = new android.widget.TextView(context);
        android.widget.Button btnBack = new android.widget.Button(context);

        setField(activity, "layoutDetailStorage", layoutStorage);
        setField(activity, "layoutDetailGuests", layoutGuests);
        setField(activity, "tvCpu", tvCpu);
        setField(activity, "progCpu", progCpu);
        setField(activity, "tvRam", tvRam);
        setField(activity, "progRam", progRam);
        setField(activity, "tvDisk", tvDisk);
        setField(activity, "progDisk", progDisk);
        setField(activity, "tvUptime", tvUptime);
        setField(activity, "tvKernel", tvKernel);
        setField(activity, "btnShell", btnShell);
        setField(activity, "tvName", tvName);
        setField(activity, "btnBack", btnBack);
        setField(activity, "cachedStoragePools", new ArrayList<StoragePool>());
        setField(activity, "cachedGuests", new ArrayList<GuestInfo>());

        return new NodeDetailFixture(activity, layoutStorage, layoutGuests, tvCpu, progCpu, tvRam, progRam, tvDisk, progDisk, tvUptime, tvKernel, btnShell);
    }

    private VmDetailActivity createVmDetailActivity() {
        VmDetailActivity activity = allocateInstance(VmDetailActivity.class);
        setField(activity, "tvTitle", new android.widget.TextView(context));
        setField(activity, "tvStatus", new android.widget.TextView(context));
        setField(activity, "tvIdNode", new android.widget.TextView(context));
        setField(activity, "tvCpu", new android.widget.TextView(context));
        setField(activity, "progCpu", new android.widget.ProgressBar(context));
        setField(activity, "tvRam", new android.widget.TextView(context));
        setField(activity, "progRam", new android.widget.ProgressBar(context));
        setField(activity, "tvDisk", new android.widget.TextView(context));
        setField(activity, "tvUptime", new android.widget.TextView(context));
        setField(activity, "btnStart", new android.widget.Button(context));
        setField(activity, "btnShutdown", new android.widget.Button(context));
        setField(activity, "btnReboot", new android.widget.Button(context));
        setField(activity, "btnForceStop", new android.widget.Button(context));
        setField(activity, "btnTerminal", new android.widget.Button(context));
        setField(activity, "btnBack", new android.widget.Button(context));
        return activity;
    }

    private GuestsView createGuestsView() {
        GuestsView view = allocateInstance(GuestsView.class);
        setField(view, "tvError", new android.widget.TextView(context));
        setField(view, "layoutGuests", new android.widget.LinearLayout(context));
        android.widget.LinearLayout emptyLayout = new android.widget.LinearLayout(context);
        emptyLayout.registerChild(com.homelabpocket.R.id.tv_empty_guests_title, new android.widget.TextView(context));
        emptyLayout.registerChild(com.homelabpocket.R.id.tv_empty_guests_desc, new android.widget.TextView(context));
        setField(view, "layoutEmptyGuests", emptyLayout);
        setField(view, "scrollGuests", new android.widget.ScrollView(context));
        setField(view, "btnRefresh", new android.widget.Button(context));
        setField(view, "btnFilterAll", new android.widget.Button(context));
        setField(view, "btnFilterRunning", new android.widget.Button(context));
        setField(view, "btnFilterStopped", new android.widget.Button(context));
        setField(view, "btnFilterFavs", new android.widget.Button(context));
        setField(view, "lastGuestList", new ArrayList<GuestInfo>());
        setField(view, "currentSearchQuery", "");
        com.homelabpocket.ui.MainActivity mainActivity = allocateInstance(com.homelabpocket.ui.MainActivity.class);
        setField(mainActivity, "cachedNodes", new ArrayList<NodeStatus>());
        setField(mainActivity, "cachedGuests", new ArrayList<GuestInfo>());
        setField(mainActivity, "cachedServerProvenances", new java.util.LinkedHashMap<String, ServerConfig.ServerType>());
        setField(mainActivity, "isLoading", true);
        setField(view, "activity", mainActivity);
        return view;
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        File ksFile = new File("app/src/test/resources/test_fixtures.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(ksFile)) {
            ks.load(is, "testpass".toCharArray());
        }

        X509Certificate cert = (X509Certificate) ks.getCertificate("local-ip");
        mockFingerprint = PinnedTrustManager.computeFingerprint(cert);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "testpass".toCharArray());
        sslCtx.init(kmf.getKeyManagers(), null, null);

        mockServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.setHttpsConfigurator(new HttpsConfigurator(sslCtx) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    SSLEngine engine = sslCtx.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // 1. Auth ticket endpoint
        mockServer.createContext("/api2/json/access/ticket", exchange -> {
            String json = "{\"data\":{\"ticket\":\"TEST_TICKET\",\"CSRFPreventionToken\":\"TEST_CSRF\"}}";
            byte[] bytes = json.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 2. Resources endpoint: /api2/json/resources/list
        mockServer.createContext("/api2/json/resources/list", exchange -> {
            int inFlight = activeResourcesRequests.incrementAndGet();
            maxConcurrentResourcesRequests.accumulateAndGet(inFlight, Math::max);
            try {
                resourcesCallCount.incrementAndGet();
                resourcesEntered.set(true);
                java.util.concurrent.CountDownLatch latch = resourcesLatch.get();
                if (latch != null) {
                    try {
                        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {}
                }
                long delay = resourcesDelayMs.get();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {}
                }
                int statusCode = resourcesStatusCode.get();
                String body = resourcesPayload.get();
                if (body == null) {
                    body = "{\"data\":[]}";
                }
                byte[] bytes = body.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } finally {
                activeResourcesRequests.decrementAndGet();
            }
        });

        // 3. Task status endpoint: /api2/json/pve/remotes/home-lab/tasks/.../status
        mockServer.createContext("/api2/json/pve/remotes/home-lab/tasks/", exchange -> {
            String rawPath = exchange.getRequestURI().getRawPath();
            lastPollUpid.set(rawPath);
            String body = taskStatusPayload.get();
            if (body == null) {
                body = "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\",\"node\":\"home-lab\",\"starttime\":1700000000,\"endtime\":1700000010}}";
            }
            byte[] bytes = body.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 4. Exact start endpoint
        mockServer.createContext("/api2/json/pve/remotes/home-lab/qemu/100/start", exchange -> {
            postActionCount.incrementAndGet();
            lastPostPath.set(exchange.getRequestURI().getPath());
            lastPostMethod.set(exchange.getRequestMethod());
            lastPostCsrf.set(exchange.getRequestHeaders().getFirst("CSRFPreventionToken"));

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int r;
            while ((r = exchange.getRequestBody().read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            lastPostBody.set(new String(baos.toByteArray(), "UTF-8"));

            String resp = "{\"data\":\"pve:home-lab!UPID:home-lab:00000001:00000001:66000000:qmstart:100:root@pam:\"}";
            byte[] bytes = resp.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 5. Exact shutdown endpoint
        mockServer.createContext("/api2/json/pve/remotes/home-lab/qemu/100/shutdown", exchange -> {
            postActionCount.incrementAndGet();
            lastPostPath.set(exchange.getRequestURI().getPath());
            lastPostMethod.set(exchange.getRequestMethod());
            lastPostCsrf.set(exchange.getRequestHeaders().getFirst("CSRFPreventionToken"));

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int r;
            while ((r = exchange.getRequestBody().read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            lastPostBody.set(new String(baos.toByteArray(), "UTF-8"));

            String resp = "{\"data\":\"pve:home-lab!UPID:home-lab:00000001:00000001:66000000:qmshutdown:100:root@pam:\"}";
            byte[] bytes = resp.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        mockServer.start();
        mockPort = mockServer.getAddress().getPort();
    }

    @AfterClass
    public static void tearDownClass() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Before
    public void setUp() {
        resourcesCallCount.set(0);
        activeResourcesRequests.set(0);
        maxConcurrentResourcesRequests.set(0);
        resourcesStatusCode.set(200);
        resourcesDelayMs.set(0);
        resourcesLatch.set(null);
        resourcesEntered.set(false);
        postActionCount.set(0);
        lastPostPath.set(null);
        lastPostMethod.set(null);
        lastPostCsrf.set(null);
        lastPostBody.set(null);
        lastPollUpid.set(null);
        resourcesPayload.set(null);
        taskStatusPayload.set(null);

        creds = AuthCredentials.ticket("user@pve", "TEST_TICKET", "TEST_CSRF", System.currentTimeMillis() + 3600_000L);
        pdmConfig = new ServerConfig(
                "test-pdm-id", "PDM Server", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PDM, mockFingerprint, true, false
        );
        pveConfig = new ServerConfig(
                "test-pve-id", "PVE Server", "127.0.0.1", mockPort,
                ServerConfig.ServerType.PVE, mockFingerprint, true, false
        );
        ActionGuard.getInstance().clearAll();
        PdmApiDriver.clearStorageCache();

        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object u = unsafeField.get(null);
            java.lang.reflect.Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);

            HomelabApp app = (HomelabApp) alloc.invoke(u, HomelabApp.class);

            Map<String, Object> serverPrefsMap = new HashMap<>();
            Map<String, Object> credPrefsMap = new HashMap<>();
            Map<String, Object> refreshPrefsMap = new HashMap<>();
            Map<String, Object> favPrefsMap = new HashMap<>();

            Context testCtx = new Context() {
                @Override
                public SharedPreferences getSharedPreferences(String name, int mode) {
                    Map<String, Object> targetMap;
                    if ("homelab_servers".equals(name)) {
                        targetMap = serverPrefsMap;
                    } else if ("homelab_creds_encrypted".equals(name)) {
                        targetMap = credPrefsMap;
                    } else if ("homelab_favorites".equals(name)) {
                        targetMap = favPrefsMap;
                    } else {
                        targetMap = refreshPrefsMap;
                    }
                    return (SharedPreferences) Proxy.newProxyInstance(
                            PhoneFieldRegression015Test.class.getClassLoader(),
                            new Class[]{SharedPreferences.class},
                            (p, m, x) -> {
                                String mName = m.getName();
                                if (mName.equals("edit")) {
                                    Map<String, Object> puts = new HashMap<>();
                                    Set<String> removes = new HashSet<>();
                                    boolean[] clear = new boolean[]{false};
                                    return (SharedPreferences.Editor) Proxy.newProxyInstance(
                                            PhoneFieldRegression015Test.class.getClassLoader(),
                                            new Class[]{SharedPreferences.Editor.class},
                                            (eProxy, eMethod, eArgs) -> {
                                                String eName = eMethod.getName();
                                                if (eName.equals("putString")) {
                                                    puts.put((String) eArgs[0], eArgs[1]);
                                                    return eProxy;
                                                } else if (eName.equals("putStringSet")) {
                                                    puts.put((String) eArgs[0], eArgs[1]);
                                                    return eProxy;
                                                } else if (eName.equals("putInt")) {
                                                    puts.put((String) eArgs[0], eArgs[1]);
                                                    return eProxy;
                                                } else if (eName.equals("putBoolean")) {
                                                    puts.put((String) eArgs[0], eArgs[1]);
                                                    return eProxy;
                                                } else if (eName.equals("remove")) {
                                                    removes.add((String) eArgs[0]);
                                                    return eProxy;
                                                } else if (eName.equals("clear")) {
                                                    clear[0] = true;
                                                    return eProxy;
                                                } else if (eName.equals("commit") || eName.equals("apply")) {
                                                    if (clear[0]) targetMap.clear();
                                                    for (String r : removes) targetMap.remove(r);
                                                    targetMap.putAll(puts);
                                                    return true;
                                                }
                                                return null;
                                            });
                                } else if (mName.equals("getString")) {
                                    return targetMap.getOrDefault((String) x[0], (String) x[1]);
                                } else if (mName.equals("getStringSet")) {
                                    Object val = targetMap.get((String) x[0]);
                                    return val instanceof Set ? val : (Set) x[1];
                                } else if (mName.equals("getInt")) {
                                    Object val = targetMap.get((String) x[0]);
                                    return val instanceof Integer ? val : (Integer) x[1];
                                } else if (mName.equals("getBoolean")) {
                                    Object val = targetMap.get((String) x[0]);
                                    return val instanceof Boolean ? val : (Boolean) x[1];
                                } else if (mName.equals("getAll")) {
                                    return new HashMap<>(targetMap);
                                }
                                return null;
                            });
                }
            };
            this.context = testCtx;

            ServerConfigStore configStore = new ServerConfigStore(testCtx);
            configStore.addOrUpdate(pdmConfig);
            configStore.addOrUpdate(pveConfig);

            CredentialStore credentialStore = new CredentialStore(testCtx);
            credentialStore.setCryptoProvider(new CredentialStore.CryptoProvider() {
                @Override
                public String encrypt(String plaintext) {
                    return "TEST_ENC:" + java.util.Base64.getEncoder().encodeToString(plaintext.getBytes());
                }
                @Override
                public String decrypt(String encoded) throws GeneralSecurityException {
                    byte[] raw = java.util.Base64.getDecoder().decode(encoded.substring("TEST_ENC:".length()));
                    return new String(raw);
                }
            });

            SessionManager sessionManager = new SessionManager(credentialStore);
            sessionManager.putActiveSession(pdmConfig.id, creds);
            sessionManager.putActiveSession(pveConfig.id, creds);

            RefreshPreferenceStore refreshStore = new RefreshPreferenceStore(testCtx);
            FavoritesStore favoritesStore = new FavoritesStore(testCtx);

            java.lang.reflect.Field fInstance = HomelabApp.class.getDeclaredField("instance");
            fInstance.setAccessible(true);
            fInstance.set(null, app);

            java.lang.reflect.Field fCfg = HomelabApp.class.getDeclaredField("serverConfigStore");
            fCfg.setAccessible(true);
            fCfg.set(app, configStore);

            java.lang.reflect.Field fCred = HomelabApp.class.getDeclaredField("credentialStore");
            fCred.setAccessible(true);
            fCred.set(app, credentialStore);

            java.lang.reflect.Field fSess = HomelabApp.class.getDeclaredField("sessionManager");
            fSess.setAccessible(true);
            fSess.set(app, sessionManager);

            java.lang.reflect.Field fRef = HomelabApp.class.getDeclaredField("refreshPreferenceStore");
            fRef.setAccessible(true);
            fRef.set(app, refreshStore);

            java.lang.reflect.Field fFav = HomelabApp.class.getDeclaredField("favoritesStore");
            fFav.setAccessible(true);
            fFav.set(app, favoritesStore);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize HomelabApp container double", e);
        }
    }

    // =========================================================================
    // 1. Upstream Proxmox VE Task Status Terminal Classification Matrix
    // =========================================================================

    @Test
    public void testTerminalStatusMatrixClassificationHonestOutcomes() throws Exception {
        PdmApiDriver pdmDriver = new PdmApiDriver(pdmConfig);
        PveApiDriver pveDriver = new PveApiDriver(pveConfig);

        java.lang.reflect.Method pdmMethod = PdmApiDriver.class.getDeclaredMethod("parseTaskStatus", String.class, JSONObject.class, String.class);
        pdmMethod.setAccessible(true);
        java.lang.reflect.Method pveMethod = PveApiDriver.class.getDeclaredMethod("parseTaskStatus", String.class, JSONObject.class, String.class);
        pveMethod.setAccessible(true);

        // stopped + missing exitstatus -> UNKNOWN
        TaskResult rPdmMissing = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\"}"), "node1");
        assertEquals("Missing exitstatus must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPdmMissing.state);
        TaskResult rPveMissing = (TaskResult) pveMethod.invoke(pveDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\"}"), "node1");
        assertEquals("Missing exitstatus must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPveMissing.state);

        // stopped + null exitstatus -> UNKNOWN
        TaskResult rPdmNull = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":null}"), "node1");
        assertEquals("Null exitstatus must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPdmNull.state);
        TaskResult rPveNull = (TaskResult) pveMethod.invoke(pveDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":null}"), "node1");
        assertEquals("Null exitstatus must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPveNull.state);

        // stopped + empty exitstatus -> UNKNOWN
        TaskResult rPdmEmpty = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"\"}"), "node1");
        assertEquals("Empty exitstatus must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPdmEmpty.state);
        TaskResult rPveEmpty = (TaskResult) pveMethod.invoke(pveDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"\"}"), "node1");
        assertEquals("Empty exitstatus must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPveEmpty.state);

        // stopped + malformed "WARNINGS:" or "WARNINGS: nonsense" -> UNKNOWN
        TaskResult rPdmMal1 = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS:\"}"), "node1");
        assertEquals("Malformed WARNINGS: without count must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPdmMal1.state);
        TaskResult rPveMal1 = (TaskResult) pveMethod.invoke(pveDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS:\"}"), "node1");
        assertEquals("Malformed WARNINGS: without count must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPveMal1.state);

        TaskResult rPdmMal2 = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS: nonsense\"}"), "node1");
        assertEquals("Malformed WARNINGS: nonsense must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPdmMal2.state);
        TaskResult rPveMal2 = (TaskResult) pveMethod.invoke(pveDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS: nonsense\"}"), "node1");
        assertEquals("Malformed WARNINGS: nonsense must map to UNKNOWN", TaskResult.TaskState.UNKNOWN, rPveMal2.state);

        // stopped + legitimate "WARNINGS: 1" -> WARNINGS (isSuccess = true)
        TaskResult rPdmWarn = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS: 1\"}"), "node1");
        assertEquals("Valid WARNINGS: 1 must map to WARNINGS", TaskResult.TaskState.WARNINGS, rPdmWarn.state);
        assertTrue("WARNINGS: 1 must be finished", rPdmWarn.isFinished());
        assertTrue("WARNINGS: 1 must be success", rPdmWarn.isSuccess());

        TaskResult rPveWarn = (TaskResult) pveMethod.invoke(pveDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS: 2\"}"), "node1");
        assertEquals("Valid WARNINGS: 2 must map to WARNINGS", TaskResult.TaskState.WARNINGS, rPveWarn.state);
        assertTrue("WARNINGS: 2 must be finished", rPveWarn.isFinished());
        assertTrue("WARNINGS: 2 must be success", rPveWarn.isSuccess());

        // stopped + "OK" -> OK (isSuccess = true)
        TaskResult rPdmOk = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"OK\"}"), "node1");
        assertEquals("OK must map to OK", TaskResult.TaskState.OK, rPdmOk.state);
        assertTrue(rPdmOk.isSuccess());

        TaskResult rPveOk = (TaskResult) pveMethod.invoke(pveDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"OK\"}"), "node1");
        assertEquals("OK must map to OK", TaskResult.TaskState.OK, rPveOk.state);
        assertTrue(rPveOk.isSuccess());

        // stopped + official error string -> ERROR (isSuccess = false)
        TaskResult rPdmErr = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"TASK ERROR: VM 100 already running\"}"), "node1");
        assertEquals("Task error string must map to ERROR", TaskResult.TaskState.ERROR, rPdmErr.state);
        assertFalse(rPdmErr.isSuccess());

        TaskResult rPveErr = (TaskResult) pveMethod.invoke(pveDriver, "UPID:1", new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"TASK ERROR: command failed: exit code 1\"}"), "node1");
        assertEquals("Task error string must map to ERROR", TaskResult.TaskState.ERROR, rPveErr.state);
        assertFalse(rPveErr.isSuccess());

        // running -> RUNNING
        TaskResult rPdmRun = (TaskResult) pdmMethod.invoke(pdmDriver, "UPID:1", new JSONObject("{\"status\":\"running\"}"), "node1");
        assertEquals("running status must map to RUNNING", TaskResult.TaskState.RUNNING, rPdmRun.state);
        assertFalse(rPdmRun.isFinished());
    }

    // =========================================================================
    // 2. NodeDetailActivity Owner Load Seam: Storage Provenance Isolation
    // =========================================================================

    @Test
    public void testNodeDetailOwnerFiltersSiblingStorageProvenance() throws Exception {
        // Multi-node single remote: remote "home-lab" contains two nodes:
        // Node 1: "home-lab" with storage "local-lvm"
        // Node 2: "home-lab-sibling" with storage "sibling-pool"
        String multiNodePayload = "{"
                + "\"data\":[{"
                + "  \"remote\":\"home-lab\","
                + "  \"resources\":["
                + "    {\"type\":\"node\",\"node\":\"home-lab\",\"status\":\"online\",\"cpu\":0.15,\"maxcpu\":4,\"mem\":4294967296,\"maxmem\":17179869184,\"disk\":10737418240,\"maxdisk\":107374182400,\"uptime\":86400},"
                + "    {\"type\":\"node\",\"node\":\"home-lab-sibling\",\"status\":\"online\",\"cpu\":0.10,\"maxcpu\":4,\"mem\":2147483648,\"maxmem\":8589934592,\"disk\":5368709120,\"maxdisk\":53687091200,\"uptime\":43200},"
                + "    {\"type\":\"pve-qemu\",\"vmid\":100,\"name\":\"Windows-11\",\"node\":\"home-lab\",\"status\":\"running\",\"cpu\":0.05,\"maxcpu\":2,\"mem\":2147483648,\"maxmem\":8589934592,\"disk\":42949672960,\"maxdisk\":68719476736,\"uptime\":3600},"
                + "    {\"type\":\"pve-storage\",\"storage\":\"local-lvm\",\"node\":\"home-lab\",\"disk\":10737418240,\"maxdisk\":107374182400,\"status\":\"available\"},"
                + "    {\"type\":\"pve-storage\",\"storage\":\"sibling-pool\",\"node\":\"home-lab-sibling\",\"disk\":5368709120,\"maxdisk\":53687091200,\"status\":\"available\"}"
                + "  ]"
                + "}]"
                + "}";

        resourcesPayload.set(multiNodePayload);

        // Invoke NodeDetailActivity production load seam directly
        NodeDetailActivity.NodeDataResult res = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab/home-lab", "test-pdm-id");

        assertNotNull(res);
        assertFalse("Healthy node must not be marked offline", res.isOffline);
        assertNotNull("Node status must be populated", res.node);
        assertEquals("home-lab/home-lab", res.node.node);

        // Critical provenance check: sibling-pool must be filtered out!
        assertNotNull(res.storagePools);
        assertEquals("Selected node must have exactly 1 storage pool; sibling node pool must NOT leak", 1, res.storagePools.size());
        assertEquals("local-lvm", res.storagePools.get(0).name);
        assertNull("Storage error must be null on successful load", res.storageError);

        // Guest check: only guests on home-lab
        assertEquals(1, res.guests.size());
        assertEquals("Windows-11", res.guests.get(0).name);
        assertNull("Guest error must be null on successful load", res.guestError);
    }

    @Test
    public void testNodeDetailOwnerMissingRemoteOrNodeFailsExplicitUnavailable() throws Exception {
        String payload = "{"
                + "\"data\":[{"
                + "  \"remote\":\"home-lab\","
                + "  \"resources\":["
                + "    {\"type\":\"node\",\"node\":\"home-lab\",\"status\":\"online\"}"
                + "  ]"
                + "}]"
                + "}";
        resourcesPayload.set(payload);

        // Case 1: Missing remote
        NodeDetailActivity.NodeDataResult resMissingRemote = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "missing-remote/node1", "test-pdm-id");
        assertNotNull(resMissingRemote);
        assertTrue("Missing remote must be marked offline", resMissingRemote.isOffline);
        assertNotNull("Missing remote must have non-null storageError", resMissingRemote.storageError);
        assertNotNull("Missing remote must have non-null guestError", resMissingRemote.guestError);
        assertTrue("Storage error must indicate unavailable", resMissingRemote.storageError.contains("unavailable"));

        // Case 2: Remote exists, but selected node is missing
        NodeDetailActivity.NodeDataResult resMissingNode = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab/non-existent-node", "test-pdm-id");
        assertNotNull(resMissingNode);
        assertTrue("Missing node must be marked offline", resMissingNode.isOffline);
        assertNotNull("Missing node must have non-null storageError", resMissingNode.storageError);
        assertNotNull("Missing node must have non-null guestError", resMissingNode.guestError);
        assertTrue("Storage error must indicate unavailable", resMissingNode.storageError.contains("unavailable"));
    }

    // =========================================================================
    // 3. NodeDetailActivity Lifecycle Owner Acceptance & Completion Seam
    // =========================================================================

    private static class TestRefreshCallback implements com.homelabpocket.scheduler.AutoRefreshScheduler.RefreshCallback {
        private boolean success = false;
        private Throwable error = null;
        private String errorReason = null;
        private boolean canAccept = true;

        @Override
        public void onSuccess() {
            this.success = true;
        }

        @Override
        public void onError(Throwable t, String reason) {
            this.error = t;
            this.errorReason = reason;
        }

        @Override
        public boolean canAcceptResult() {
            return canAccept;
        }
    }

    @Test
    public void testNodeDetailLifecycleOwnerCompletionRejectionAndAcceptance() {
        NodeDetailActivity.NodeDataResult healthyResult = new NodeDetailActivity.NodeDataResult(
                new NodeStatus("home-lab/home-lab", "pdm", NodeStatus.Status.ONLINE, 0.15, 4, 1000, 2000, 1000, 2000, 0, 0, "Proxmox VE", 1000, System.currentTimeMillis()),
                Collections.singletonList(new StoragePool("local-lvm", "lvmthin", 1000, 500, 500, true, true, false, "", "home-lab/home-lab")),
                null,
                Collections.singletonList(new GuestInfo(100, "Windows-11", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING, "home-lab/home-lab", "pdm", 0.05, 2, 1000, 2000, 1000, 2000, 0, 0, 0, 0, 100, "", false, 1000)),
                null, false
        );

        // 1. Detached activity
        TestRefreshCallback cbDetached = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState presDetached = NodeDetailActivity.completeAndBind(
                false, false, false, false, true, healthyResult, pdmConfig, null, null, cbDetached, null);
        assertFalse("Detached activity must not accept result", presDetached.decision.accepted);
        assertEquals("Detached", cbDetached.errorReason);
        assertNull("UI binding state must not be populated on detached rejection", presDetached.metricsState);

        // 2. Session locked
        TestRefreshCallback cbLocked = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState presLocked = NodeDetailActivity.completeAndBind(
                true, true, false, false, true, healthyResult, pdmConfig, null, null, cbLocked, null);
        assertFalse("Session locked must not accept result", presLocked.decision.accepted);
        assertEquals("Session locked", cbLocked.errorReason);
        assertNull(presLocked.metricsState);

        // 3. Action in progress
        TestRefreshCallback cbAction = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState presAction = NodeDetailActivity.completeAndBind(
                true, false, true, false, true, healthyResult, pdmConfig, null, null, cbAction, null);
        assertFalse("Action running must not accept result", presAction.decision.accepted);
        assertEquals("Action in progress", cbAction.errorReason);
        assertNull(presAction.metricsState);

        // 4. Configuration changed
        TestRefreshCallback cbConfig = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState presConfig = NodeDetailActivity.completeAndBind(
                true, false, false, true, true, healthyResult, pdmConfig, null, null, cbConfig, null);
        assertFalse("Config change must not accept result", presConfig.decision.accepted);
        assertEquals("Configuration changed", cbConfig.errorReason);
        assertNull(presConfig.metricsState);

        // 5. Stale callback
        TestRefreshCallback cbStale = new TestRefreshCallback();
        cbStale.canAccept = false;
        NodeDetailActivity.NodeDetailPresentationState presStale = NodeDetailActivity.completeAndBind(
                true, false, false, false, false, healthyResult, pdmConfig, null, null, cbStale, null);
        assertFalse("Stale callback must not accept result", presStale.decision.accepted);
        assertEquals("Stale", cbStale.errorReason);
        assertNull(presStale.metricsState);

        // 6. Healthy resumed foreground: accepted, clears loading, populates presentation
        TestRefreshCallback cbHealthy = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState presHealthy = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, healthyResult, pdmConfig, null, null, cbHealthy, null);
        assertTrue("Healthy resumed foreground must accept result", presHealthy.decision.accepted);
        assertTrue("Healthy result must be marked success", presHealthy.decision.isSuccess);
        assertTrue("Callback onSuccess must be called", cbHealthy.success);
        assertNotNull(presHealthy.metricsState);
        assertTrue(presHealthy.metricsState.hasData);
        assertNotNull(presHealthy.storageState);
        assertFalse("Storage loading must be cleared on completion", presHealthy.storageState.isLoading);
        assertEquals(1, presHealthy.storageState.displayedPools.size());
        assertNotNull(presHealthy.guestsState);
        assertFalse("Guests loading must be cleared on completion", presHealthy.guestsState.isLoading);
        assertEquals(1, presHealthy.guestsState.displayedGuests.size());
    }

    @Test
    public void testNodeDetailColdAndCachedStartThroughCompleteAndBindSeam() {
        // Cold start: empty previous cache, healthy fresh result
        StoragePool pool = new StoragePool("local-lvm", "lvmthin", 1000, 500, 500, true, true, false, "", "home-lab/home-lab");
        GuestInfo guest = new GuestInfo(100, "Windows-11", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING, "home-lab/home-lab", "pdm", 0.05, 2, 1000, 2000, 1000, 2000, 0, 0, 0, 0, 100, "", false, 1000);
        NodeDetailActivity.NodeDataResult coldResult = new NodeDetailActivity.NodeDataResult(
                new NodeStatus("home-lab/home-lab", "pdm", NodeStatus.Status.ONLINE, 0.15, 4, 1000, 2000, 1000, 2000, 0, 0, "Proxmox VE", 1000, System.currentTimeMillis()),
                Collections.singletonList(pool), null, Collections.singletonList(guest), null, false
        );

        TestRefreshCallback cbCold = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState presCold = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, coldResult, pdmConfig, null, null, cbCold, null);

        assertTrue(presCold.decision.accepted);
        assertTrue(presCold.decision.isSuccess);
        assertTrue(cbCold.success);
        assertFalse(presCold.storageState.isLoading);
        assertEquals(1, presCold.storageState.cachedPools.size());
        assertEquals("local-lvm", presCold.storageState.cachedPools.get(0).name);
        assertFalse(presCold.guestsState.isLoading);
        assertEquals(1, presCold.guestsState.cachedGuests.size());
        assertEquals("Windows-11", presCold.guestsState.cachedGuests.get(0).name);

        // Cached start on subsequent refresh error: preserves cache with "(showing cached data)" and clears loading
        NodeDetailActivity.NodeDataResult errResult = new NodeDetailActivity.NodeDataResult(
                null, Collections.emptyList(), "Network connection timeout", Collections.emptyList(), "Network connection timeout", true
        );

        TestRefreshCallback cbErr = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState presErr = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, errResult, pdmConfig,
                presCold.storageState.cachedPools, presCold.guestsState.cachedGuests, cbErr, null);

        assertTrue(presErr.decision.accepted);
        assertFalse("Error result is not marked success", presErr.decision.isSuccess);
        assertEquals("Node offline or telemetry error", cbErr.errorReason);
        assertFalse("Loading must be cleared even on error", presErr.storageState.isLoading);
        assertTrue("Storage error must indicate showing cached data", presErr.storageState.errorMessage.contains("(showing cached data)"));
        assertEquals("Cached storage pool must be preserved on error", 1, presErr.storageState.displayedPools.size());
        assertEquals("local-lvm", presErr.storageState.displayedPools.get(0).name);

        assertFalse("Guests loading must be cleared on error", presErr.guestsState.isLoading);
        assertTrue("Guests offline message must indicate host offline", presErr.guestsState.offlineMessage.contains("offline or unreachable"));
    }

    @Test
    public void testWholeEndpointFailureProducesExplicitUnavailableAndClearsLoading() throws Exception {
        // Resources endpoint fails completely with actual HTTP 500 status code
        resourcesStatusCode.set(500);
        resourcesPayload.set("{\"error\":\"Internal Server Error 500: Database unavailable\"}");

        NodeDetailActivity.NodeDataResult res = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab/home-lab", "test-pdm-id");

        assertNotNull(res);
        assertTrue("Whole endpoint HTTP 500 failure must mark result offline", res.isOffline);
        assertNotNull("Storage error must not be null", res.storageError);
        assertNotNull("Guest error must not be null", res.guestError);
        assertTrue("Error message must reflect HTTP 500 or failure reason",
                res.storageError.contains("500") || res.storageError.contains("Internal Server Error") || res.storageError.contains("HTTP"));

        TestRefreshCallback cb = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState pres = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, res, pdmConfig, null, null, cb, null);

        assertTrue(pres.decision.accepted);
        assertFalse(pres.decision.isSuccess);
        assertEquals("Node offline or telemetry error", cb.errorReason);
        assertFalse("Storage loading must be cleared on whole endpoint failure", pres.storageState.isLoading);
        assertNotNull(pres.storageState.errorMessage);
        assertFalse("Guests loading must be cleared on whole endpoint failure", pres.guestsState.isLoading);
        assertNotNull(pres.guestsState.offlineMessage);
        assertFalse("Initial telemetry must not display valid data on endpoint failure", pres.metricsState.hasData);
        assertTrue("Metrics must show explicit unavailable without fake zeros", pres.metricsState.cpuText.contains("-"));
    }

    @Test
    public void testWholeEndpointHttp200JsonErrorProducesExplicitUnavailableAndClearsLoading() throws Exception {
        // Resources endpoint returns HTTP 200 with JSON error payload (e.g. auth ticket / route error)
        resourcesStatusCode.set(200);
        resourcesPayload.set("{\"error\":\"Permission denied: cluster inaccessible\"}");

        NodeDetailActivity.NodeDataResult res = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab/home-lab", "test-pdm-id");

        assertNotNull(res);
        assertTrue("JSON error payload must mark result offline", res.isOffline);
        assertNotNull(res.storageError);
        assertNotNull(res.guestError);

        TestRefreshCallback cb = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState pres = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, res, pdmConfig, null, null, cb, null);

        assertTrue(pres.decision.accepted);
        assertFalse(pres.decision.isSuccess);
        assertFalse("Storage loading must be cleared", pres.storageState.isLoading);
        assertFalse("Guests loading must be cleared", pres.guestsState.isLoading);
        assertFalse(pres.metricsState.hasData);
        assertTrue(pres.metricsState.cpuText.contains("-"));
    }

    // =========================================================================
    // 4. Shared Task Result Presentation Seam Wired to Both Activities
    // =========================================================================

    @Test
    public void testTaskResultPresentationSeamWiredForGuestsAndVmDetail() {
        String targetInfo = "QEMU 100 (Windows-11) on home-lab [PDM]";

        // Case 1: WARNINGS: 1 -> Dialog "Task Completed with Warnings", isSuccess = true
        TaskResult warningsResult = new TaskResult("UPID:home-lab:0001", TaskResult.TaskState.WARNINGS, "WARNINGS: 1", null, 1000, 1010, "home-lab");
        UiPresentationPolicy.TaskResultPresentation presWarn = UiPresentationPolicy.evaluateTaskResultPresentation(
                warningsResult, "start", targetInfo, null);
        assertNotNull(presWarn);
        assertEquals(UiPresentationPolicy.TaskResultPresentation.DialogType.ALERT_WARNINGS, presWarn.type);
        assertEquals("Task Completed with Warnings", presWarn.title);
        assertTrue(presWarn.isSuccess);
        assertTrue(presWarn.message.contains("WARNINGS: 1"));

        // Case 2: OK -> Toast Success, isSuccess = true
        TaskResult okResult = new TaskResult("UPID:home-lab:0002", TaskResult.TaskState.OK, "OK", null, 1000, 1010, "home-lab");
        UiPresentationPolicy.TaskResultPresentation presOk = UiPresentationPolicy.evaluateTaskResultPresentation(
                okResult, "start", targetInfo, null);
        assertNotNull(presOk);
        assertEquals(UiPresentationPolicy.TaskResultPresentation.DialogType.TOAST_SUCCESS, presOk.type);
        assertTrue(presOk.isSuccess);

        // Case 3: ERROR -> Dialog "Task Failed", isSuccess = false
        TaskResult errResult = new TaskResult("UPID:home-lab:0003", TaskResult.TaskState.ERROR, "exit code 1: qemu error", null, 1000, 1010, "home-lab");
        UiPresentationPolicy.TaskResultPresentation presErr = UiPresentationPolicy.evaluateTaskResultPresentation(
                errResult, "start", targetInfo, null);
        assertNotNull(presErr);
        assertEquals(UiPresentationPolicy.TaskResultPresentation.DialogType.ALERT_ERROR, presErr.type);
        assertEquals("Task Failed", presErr.title);
        assertFalse(presErr.isSuccess);

        // Case 4: TIMEOUT -> Dialog "Task Timed Out", isSuccess = false
        TaskResult timeoutResult = new TaskResult("UPID:home-lab:0004", TaskResult.TaskState.TIMEOUT, null, null, 1000, 0, "home-lab");
        UiPresentationPolicy.TaskResultPresentation presTimeout = UiPresentationPolicy.evaluateTaskResultPresentation(
                timeoutResult, "start", targetInfo, null);
        assertNotNull(presTimeout);
        assertEquals(UiPresentationPolicy.TaskResultPresentation.DialogType.ALERT_TIMEOUT, presTimeout.type);
        assertEquals("Task Timed Out", presTimeout.title);
        assertFalse(presTimeout.isSuccess);

        // Case 5: UNKNOWN -> Dialog "Task Status Unknown", isSuccess = false
        TaskResult unkResult = new TaskResult("UPID:home-lab:0005", TaskResult.TaskState.UNKNOWN, null, null, 1000, 0, "home-lab");
        UiPresentationPolicy.TaskResultPresentation presUnk = UiPresentationPolicy.evaluateTaskResultPresentation(
                unkResult, "start", targetInfo, null);
        assertNotNull(presUnk);
        assertEquals(UiPresentationPolicy.TaskResultPresentation.DialogType.ALERT_UNKNOWN, presUnk.type);
        assertEquals("Task Status Unknown", presUnk.title);
        assertFalse(presUnk.isSuccess);
    }

    @Test
    public void testTruthfulAckNotCorruptedBySubsequentTelemetryFailure() throws Exception {
        String targetInfo = "QEMU 100 (Windows-11) on home-lab [PDM]";

        // Step 1: Upstream task terminates with WARNINGS: 1
        taskStatusPayload.set("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS: 1\",\"node\":\"home-lab\",\"starttime\":1700000000,\"endtime\":1700000010}}");
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);
        TaskResult acknowledgedResult = driver.pollTask("home-lab", "pve:home-lab!UPID:home-lab:00000001:00000001:66000000:qmstart:100:root@pam:", creds);

        assertNotNull(acknowledgedResult);
        assertEquals(TaskResult.TaskState.WARNINGS, acknowledgedResult.state);
        assertTrue(acknowledgedResult.isFinished());
        assertTrue(acknowledgedResult.isSuccess());

        // Step 2: Actual production caller transition executed by GuestsView
        AtomicInteger refreshCallCount = new AtomicInteger(0);
        UiPresentationPolicy.ActionCallerTransitionState transitionFromGuests =
                com.homelabpocket.ui.guests.GuestsView.transitionCallerAction(
                        acknowledgedResult, "start", targetInfo, null,
                        refreshCallCount::incrementAndGet);

        assertNotNull(transitionFromGuests);
        assertTrue("Acknowledged action was success with warnings", transitionFromGuests.isSuccess);
        assertEquals("Task Completed with Warnings", transitionFromGuests.dialogTitle);
        assertTrue(transitionFromGuests.dialogMessage.contains("WARNINGS: 1"));
        assertEquals("Post-operation refresh must be triggered on caller transition", 1, refreshCallCount.get());
        assertFalse("Action running flag must be cleared on transition", transitionFromGuests.isActionRunning);

        // Step 2b: Also invoke VmDetailActivity caller transition
        UiPresentationPolicy.ActionCallerTransitionState transitionFromVmDetail =
                com.homelabpocket.ui.vms.VmDetailActivity.transitionCallerAction(
                        acknowledgedResult, "start", targetInfo, null,
                        refreshCallCount::incrementAndGet);

        assertNotNull(transitionFromVmDetail);
        assertTrue("VmDetail caller transition preserves acknowledged success", transitionFromVmDetail.isSuccess);
        assertEquals("Task Completed with Warnings", transitionFromVmDetail.dialogTitle);
        assertEquals(2, refreshCallCount.get());

        // Step 3: Trigger subsequent post-operation telemetry refresh: telemetry fails (connection error)
        String subsequentTelemetryError = "Connection reset by peer during post-action refresh";
        GuestInfo guest = new GuestInfo(100, "Windows-11", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING,
                "home-lab/home-lab", "test-pdm-id", 0.05, 2, 1000, 2000, 1000, 2000, 0, 0, 0, 0, 100, "", false, 1000);
        UiPresentationPolicy.GuestMetricsBindingState staleState =
                UiPresentationPolicy.evaluateGuestMetricsBinding(guest, true, pdmConfig, subsequentTelemetryError);

        assertNotNull(staleState);
        assertTrue("Telemetry state must be marked stale on refresh failure", staleState.isStale);
        assertFalse("Power controls must be disabled on stale telemetry", staleState.isPowerControlsEnabled);

        // Step 4: Actual owner instances execute action completion callbacks and render acknowledgement,
        // followed by subsequent telemetry refresh error:

        // 4A. VmDetailActivity instance verification:
        VmDetailActivity vmDetailActivity = createVmDetailActivity();
        setField(vmDetailActivity, "serverId", "test-pdm-id");
        setField(vmDetailActivity, "node", "home-lab/home-lab");
        setField(vmDetailActivity, "vmid", 100);
        setField(vmDetailActivity, "type", GuestInfo.GuestType.QEMU);
        setField(vmDetailActivity, "currentGuest", guest);
        setField(vmDetailActivity, "isActivityResumed", true);
        android.widget.TextView vmTitle = new android.widget.TextView(context);
        vmTitle.setText("Windows-11");
        setField(vmDetailActivity, "tvTitle", vmTitle);

        AutoRefreshSchedulerTest.TestClock vmClock = new AutoRefreshSchedulerTest.TestClock();
        AutoRefreshSchedulerTest.TestTaskScheduler vmTaskScheduler = new AutoRefreshSchedulerTest.TestTaskScheduler(vmClock);
        AtomicInteger vmRefreshDispatched = new AtomicInteger(0);
        AutoRefreshScheduler vmScheduler = new AutoRefreshScheduler(
                vmClock,
                vmTaskScheduler,
                HomelabApp.getInstance().getRefreshPreferenceStore(),
                callback -> vmRefreshDispatched.incrementAndGet(),
                () -> vmDetailActivity.getCurrentPowerTask() != null
        );
        vmScheduler.resume();
        setField(vmDetailActivity, "scheduler", vmScheduler);

        VmDetailActivity.ExecutePowerTask vmPowerTask = vmDetailActivity.new ExecutePowerTask("start", null);
        vmDetailActivity.setCurrentPowerTask(vmPowerTask);

        AlertDialog.reset();
        java.lang.reflect.Method mPostVm = vmPowerTask.getClass().getDeclaredMethod("onPostExecute", TaskResult.class);
        mPostVm.setAccessible(true);
        mPostVm.invoke(vmPowerTask, acknowledgedResult);

        // Record actual AlertDialog title and message rendered by VmDetailActivity.ExecutePowerTask.onPostExecute
        AlertDialog vmDialog = AlertDialog.getLastShownDialog();
        assertNotNull("Actual AlertDialog must be rendered by VmDetailActivity completion callback", vmDialog);
        String vmRecordedTitle = vmDialog.getTitle() != null ? vmDialog.getTitle().toString() : "";
        String vmRecordedMessage = vmDialog.getMessage() != null ? vmDialog.getMessage().toString() : "";
        assertEquals("Task Completed with Warnings", vmRecordedTitle);
        assertTrue("Rendered dialog message must contain WARNINGS: 1", vmRecordedMessage.contains("WARNINGS: 1"));

        // THEN invoke actual refresh failure owner on VmDetailActivity instance
        vmDetailActivity.markGuestMetricsStale(subsequentTelemetryError);
        assertTrue("Subsequent refresh transitions metrics to stale on Activity instance", vmDetailActivity.isGuestMetricsStale());

        // Assert recorded acknowledgement remains truthful:
        assertEquals("Recorded AlertDialog title remains truthful after telemetry failure", "Task Completed with Warnings", vmRecordedTitle);
        assertTrue("Recorded AlertDialog message remains truthful after telemetry failure", vmRecordedMessage.contains("WARNINGS: 1"));
        assertNotNull(vmDetailActivity.getLastAcknowledgedAction());
        assertEquals("Task Completed with Warnings", vmDetailActivity.getLastAcknowledgedAction().dialogTitle);
        assertTrue("VmDetail caller instance preserves acknowledged success", vmDetailActivity.getLastAcknowledgedAction().isSuccess);

        // 4B. GuestsView instance verification:
        GuestsView guestsView = createGuestsView();
        GuestsView.PowerActionTask guestsPowerTask = guestsView.new PowerActionTask(guest, pdmConfig, "start", null);

        AlertDialog.reset();
        java.lang.reflect.Method mPostGuests = guestsPowerTask.getClass().getDeclaredMethod("onPostExecute", TaskResult.class);
        mPostGuests.setAccessible(true);
        mPostGuests.invoke(guestsPowerTask, acknowledgedResult);

        // Record actual AlertDialog title and message rendered by GuestsView.PowerActionTask.onPostExecute
        AlertDialog guestsDialog = AlertDialog.getLastShownDialog();
        assertNotNull("Actual AlertDialog must be rendered by GuestsView completion callback", guestsDialog);
        String guestsRecordedTitle = guestsDialog.getTitle() != null ? guestsDialog.getTitle().toString() : "";
        String guestsRecordedMessage = guestsDialog.getMessage() != null ? guestsDialog.getMessage().toString() : "";
        assertEquals("Task Completed with Warnings", guestsRecordedTitle);
        assertTrue("Rendered dialog message must contain WARNINGS: 1", guestsRecordedMessage.contains("WARNINGS: 1"));

        // THEN invoke actual refresh failure owner on GuestsView instance
        guestsView.render(Collections.singletonList(guest), false, subsequentTelemetryError, System.currentTimeMillis());
        assertEquals("GuestsView records telemetry error", subsequentTelemetryError, guestsView.getLastError());
        assertEquals("Rendered error banner must be visible in actual TextView",
                android.view.View.VISIBLE, guestsView.getErrorTextView().getVisibility());
        assertTrue("Rendered error banner text must display the connection failure",
                guestsView.getErrorTextView().getText().toString().contains("Failed to connect") ||
                guestsView.getErrorTextView().getText().toString().contains("unreachable") ||
                guestsView.getErrorTextView().getText().toString().contains(subsequentTelemetryError));

        // Assert recorded acknowledgement remains truthful:
        assertEquals("Recorded AlertDialog title remains truthful after telemetry failure", "Task Completed with Warnings", guestsRecordedTitle);
        assertTrue("Recorded AlertDialog message remains truthful after telemetry failure", guestsRecordedMessage.contains("WARNINGS: 1"));
        assertNotNull(guestsView.getLastAcknowledgedAction());
        assertEquals("Task Completed with Warnings", guestsView.getLastAcknowledgedAction().dialogTitle);
        assertTrue("GuestsView caller instance preserves acknowledged success", guestsView.getLastAcknowledgedAction().isSuccess);

        // Step 5: Verify truthful acknowledgement isolation through the actual transition:
        // Acknowledged result remains SUCCESS with warnings; subsequent telemetry failure CANNOT mutate it
        assertTrue("Acknowledged success must be preserved regardless of telemetry failure",
                vmDetailActivity.getLastAcknowledgedAction().isAcknowledgedSuccessPreserved(staleState));
        assertTrue("Acknowledged success must be preserved regardless of telemetry failure",
                guestsView.getLastAcknowledgedAction().isAcknowledgedSuccessPreserved(staleState));
        assertTrue("Acknowledged success must be preserved in VmDetail caller transition",
                transitionFromVmDetail.isAcknowledgedSuccessPreserved(staleState));
        assertTrue("Acknowledged success must be preserved in GuestsView caller transition",
                transitionFromGuests.isAcknowledgedSuccessPreserved(staleState));
        assertEquals("Task Completed with Warnings", transitionFromGuests.dialogTitle);
        assertEquals("Task Completed with Warnings", transitionFromVmDetail.dialogTitle);
        assertTrue(transitionFromGuests.isSuccess);
        assertTrue(transitionFromVmDetail.isSuccess);
    }

    // =========================================================================
    // 5. Exact Shutdown Fixture & Preflight Safety
    // =========================================================================

    @Test
    public void testExactShutdownFixtureDispatchesPostWithCsrfAndNodeBodyAndPollsStatus() throws Exception {
        PdmApiDriver driver = new PdmApiDriver(pdmConfig);

        // Dispatch shutdown
        String upid = driver.shutdownGuest("home-lab/home-lab", 100, GuestInfo.GuestType.QEMU, creds, 5000L);

        assertNotNull(upid);
        assertEquals(1, postActionCount.get());
        assertEquals("POST", lastPostMethod.get());
        assertEquals("/api2/json/pve/remotes/home-lab/qemu/100/shutdown", lastPostPath.get());
        assertEquals("TEST_CSRF", lastPostCsrf.get());
        assertNotNull(lastPostBody.get());
        assertEquals("node=home-lab", lastPostBody.get());
        assertEquals("pve:home-lab!UPID:home-lab:00000001:00000001:66000000:qmshutdown:100:root@pam:", upid);
        assertEquals("home-lab", PdmApiDriver.extractRemote("home-lab/home-lab"));
        assertEquals("home-lab", PdmApiDriver.extractNode("home-lab/home-lab"));

        // Poll task status for this exact UPID
        TaskResult pollResult = driver.pollTask("home-lab", upid, creds);
        assertNotNull(pollResult);
        assertTrue(pollResult.isFinished());
        assertTrue(pollResult.isSuccess());
        assertEquals(TaskResult.TaskState.OK, pollResult.state);
        assertEquals("OK", pollResult.exitStatus);
        assertEquals("pve:home-lab!UPID:home-lab:00000001:00000001:66000000:qmshutdown:100:root@pam:", pollResult.upid);
        assertTrue(pollResult.upid.contains(":qmshutdown:"));
        assertEquals("home-lab", pollResult.node);
        assertEquals(upid, pollResult.upid);
        assertNotNull(lastPollUpid.get());
        String expectedPollPath = "/api2/json/pve/remotes/home-lab/tasks/" + java.net.URLEncoder.encode(upid, "UTF-8") + "/status";
        assertEquals("Poll path must match exact URL-encoded UPID path", expectedPollPath, lastPollUpid.get());
        assertEquals("Decoded poll path must equal exact canonical task status path",
                "/api2/json/pve/remotes/home-lab/tasks/" + upid + "/status",
                java.net.URLDecoder.decode(lastPollUpid.get(), "UTF-8"));
    }

    @Test
    public void testSelectedUnreachableRemotePreflightRejectsActionWithZeroPost() throws Exception {
        resourcesPayload.set(buildTwoRemoteFleetPayload(true));

        PdmApiDriver driver = new PdmApiDriver(pdmConfig);
        PdmApiDriver.PdmFleetResult fleet = driver.fetchFleetResources(creds, 5000L);

        // Find placeholder for unreachable sibling remote
        NodeStatus unreachableNode = null;
        for (NodeStatus n : fleet.placeholderNodes) {
            if ("home-lab-2".equals(n.node)) {
                unreachableNode = n;
                break;
            }
        }
        assertNotNull("Placeholder for unreachable remote must exist", unreachableNode);

        // Power presentation check: power controls must be disabled on unreachable node
        UiPresentationPolicy.NodeMetricsBindingState metricsState =
                UiPresentationPolicy.evaluateNodeMetricsBinding(unreachableNode, false, pdmConfig);
        assertFalse("Unreachable node must NOT claim valid telemetry", metricsState.hasData);

        // 1. Target lookup preflight on unreachable remote: fails closed with ApiException
        try {
            driver.fetchGuestDetailWithDeadline("home-lab-2/node1", 100, GuestInfo.GuestType.QEMU, creds,
                    System.nanoTime() + 5_000_000_000L);
            fail("Target lookup on unreachable remote home-lab-2 must fail closed with ApiException");
        } catch (ApiException expected) {
            assertTrue("Exception message must reference unreachable remote", expected.getMessage().contains("home-lab-2"));
        }

        // 2. NodeDetailActivity load seam on unreachable remote: returns offline
        NodeDetailActivity.NodeDataResult nodeRes = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab-2/node1", "test-pdm-id");
        assertNotNull(nodeRes);
        assertTrue("Node on unreachable remote must be marked offline", nodeRes.isOffline);
        assertNotNull("Storage error must indicate offline", nodeRes.storageError);
        assertNotNull("Guest error must indicate offline", nodeRes.guestError);

        // 3. UI binding on unreachable remote guest: power controls strictly disabled, action confirmation forbidden
        GuestInfo unreachableGuest = new GuestInfo(100, "Windows-11", GuestInfo.GuestType.QEMU,
                GuestInfo.RunState.STOPPED, "home-lab-2/node1", "pdm", 0.0, 2, 1000, 2000, 1000, 2000, 0, 0, 0, 0, 100, "", false, 1000);
        UiPresentationPolicy.GuestMetricsBindingState guestMetrics =
                UiPresentationPolicy.evaluateGuestMetricsBinding(unreachableGuest, true, pdmConfig, "No route to host");
        assertFalse("Power controls must be disabled for guest on unreachable remote", guestMetrics.isPowerControlsEnabled);
        assertFalse("Action confirmation strictly forbidden on unreachable remote", guestMetrics.isActionConfirmationAllowed);

        // 4. Actual production action preflight check wired to VmDetailActivity:
        // Must reject cold-start (null guest), stale telemetry on unreachable remote, and session locked
        assertFalse("VmDetail preflight must reject null target on unreachable remote",
                com.homelabpocket.ui.vms.VmDetailActivity.preflightActionCheck(null, "home-lab-2/node1", 100, GuestInfo.GuestType.QEMU, false, false));
        assertFalse("VmDetail preflight must reject stale guest on unreachable remote",
                com.homelabpocket.ui.vms.VmDetailActivity.preflightActionCheck(unreachableGuest, "home-lab-2/node1", 100, GuestInfo.GuestType.QEMU, true, false));
        assertFalse("VmDetail preflight must reject when session locked",
                com.homelabpocket.ui.vms.VmDetailActivity.preflightActionCheck(unreachableGuest, "home-lab-2/node1", 100, GuestInfo.GuestType.QEMU, false, true));

        // 5. Actual production action preflight check wired to GuestsView:
        // Must reject target on unreachable remote when stale or session locked
        assertFalse("GuestsView preflight must reject stale target on unreachable remote",
                com.homelabpocket.ui.guests.GuestsView.preflightActionCheck(unreachableGuest, "home-lab-2/node1", 100, GuestInfo.GuestType.QEMU, true, false));
        assertFalse("GuestsView preflight must reject when session locked",
                com.homelabpocket.ui.guests.GuestsView.preflightActionCheck(unreachableGuest, "home-lab-2/node1", 100, GuestInfo.GuestType.QEMU, false, true));

        // 6. Invoke actual production action dispatch owners on unreachable remote target:
        // Case A: VmDetailActivity.executeAction blocks at preflight before task launch when metrics are stale
        VmDetailActivity vmDetail = createVmDetailActivity();
        setField(vmDetail, "currentGuest", unreachableGuest);
        setField(vmDetail, "node", "home-lab-2/node1");
        setField(vmDetail, "vmid", 100);
        setField(vmDetail, "type", GuestInfo.GuestType.QEMU);
        setField(vmDetail, "serverId", "test-pdm-id");
        setField(vmDetail, "isGuestMetricsStale", true);

        // When metrics are stale, executeAction returns immediately at preflight without creating task or dispatching POST
        vmDetail.executeAction("start");
        assertNull("No power task created when preflight rejects stale target", vmDetail.getCurrentPowerTask());
        assertEquals("Preflight rejection must issue zero POST requests", 0, postActionCount.get());
        assertTrue("Toast must notify action blocked on stale target",
                Toast.getLastShownText() != null && Toast.getLastShownText().contains("Action blocked"));

        // Case B: Even if caller passed non-stale flag, fresh target verification fails closed on unreachable remote
        setField(vmDetail, "isGuestMetricsStale", false);
        VmDetailActivity.ExecutePowerTask powerTask = vmDetail.new ExecutePowerTask("start", null);
        TaskResult taskRes = (TaskResult) powerTask.doInBackground((Void[]) null);
        assertNull("ExecutePowerTask returns null when remote target lookup fails closed", taskRes);
        assertEquals("Unreachable remote lookup failure must dispatch ZERO POST requests", 0, postActionCount.get());

        // Case C: GuestsView.PowerActionTask also fails closed on unreachable remote without any POST
        GuestsView guestsView = createGuestsView();
        GuestsView.PowerActionTask gTask = guestsView.new PowerActionTask(unreachableGuest, pdmConfig, "start", null);
        TaskResult gTaskRes = (TaskResult) gTask.doInBackground((Void[]) null);
        assertNull("GuestsView power task returns null on unreachable remote lookup failure", gTaskRes);
        assertEquals("Production caller target verification must dispatch ZERO POST requests to unreachable remote", 0, postActionCount.get());
    }

    // =========================================================================
    // 6. UI Presentation Policy & Sibling Remote Failure Handling
    // =========================================================================

    @Test
    public void testInitialNodeMetricsStateHasExplicitUnavailableWithoutFakeZeros() {
        UiPresentationPolicy.NodeMetricsBindingState initialState =
                UiPresentationPolicy.evaluateNodeMetricsBinding(null, false, pdmConfig);

        assertNotNull(initialState);
        assertFalse("Initial state has no valid telemetry data", initialState.hasData);
        assertFalse("Initial state is not stale", initialState.isStale);
        assertTrue("CPU must show explicit unavailable, not fake 0.0%",
                initialState.cpuText.contains("-") && !initialState.cpuText.contains("0.0%"));
        assertTrue("RAM must show explicit unavailable, not fake 0 / 0 GB",
                initialState.ramText.contains("-") && !initialState.ramText.contains("0 / 0 GB"));
        assertTrue("Root FS must show explicit unavailable, not fake 0 / 0 GB",
                initialState.diskText.contains("-") && !initialState.diskText.contains("0 / 0 GB"));
        assertEquals(0, initialState.cpuPercent);
        assertEquals(0, initialState.ramPercent);
    }

    @Test
    public void testOfflineOrPlaceholderNodeDoesNotDisplayFakeZeros() {
        NodeStatus offlineNode = new NodeStatus("home-lab-2", "pdm", NodeStatus.Status.UNKNOWN,
                Double.NaN, 0, -1, -1, -1, -1, -1, -1, null, -1, System.currentTimeMillis(), true);

        UiPresentationPolicy.NodeMetricsBindingState state =
                UiPresentationPolicy.evaluateNodeMetricsBinding(offlineNode, false, pdmConfig);

        assertNotNull(state);
        assertTrue("Offline/unknown node must show unavailable CPU, NOT 0% (0 cores)",
                state.cpuText.contains("-") || state.cpuText.contains("unavailable"));
        assertTrue("Offline/unknown node must show unavailable RAM, NOT 0 B / 0 B",
                state.ramText.contains("-") || state.ramText.contains("unavailable"));
        assertTrue("Offline/unknown node Root FS must show unavailable, NOT 0 B / 0 B",
                state.diskText.contains("-") || state.diskText.contains("unavailable"));
    }

    private String buildTwoRemoteFleetPayload(boolean healthyFirst) {
        String healthyRemote = "{"
                + "\"remote\":\"home-lab\","
                + "\"resources\":["
                + "  {\"type\":\"node\",\"node\":\"home-lab\",\"status\":\"online\",\"cpu\":0.15,\"maxcpu\":4,\"mem\":4294967296,\"maxmem\":17179869184,\"disk\":10737418240,\"maxdisk\":107374182400,\"uptime\":86400},"
                + "  {\"type\":\"pve-qemu\",\"vmid\":100,\"name\":\"Windows-11\",\"node\":\"home-lab\",\"status\":\"running\",\"cpu\":0.05,\"maxcpu\":2,\"mem\":2147483648,\"maxmem\":8589934592,\"disk\":42949672960,\"maxdisk\":68719476736,\"uptime\":3600},"
                + "  {\"type\":\"pve-storage\",\"storage\":\"local-lvm\",\"node\":\"home-lab\",\"disk\":10737418240,\"maxdisk\":107374182400,\"status\":\"available\"}"
                + "]"
                + "}";
        String failedRemote = "{"
                + "\"remote\":\"home-lab-2\","
                + "\"error\":\"connection error: No route to host (os error 113)\""
                + "}";

        return healthyFirst
                ? "{\"data\":[" + healthyRemote + "," + failedRemote + "]}"
                : "{\"data\":[" + failedRemote + "," + healthyRemote + "]}";
    }

    @Test
    public void testHealthyNodeFinishesWhenHealthyRemoteIsFirst() throws Exception {
        resourcesPayload.set(buildTwoRemoteFleetPayload(true));

        NodeDetailActivity.NodeDataResult res = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab/home-lab", "test-pdm-id");

        assertNotNull(res);
        assertFalse(res.isOffline);
        assertNotNull(res.node);
        assertEquals(1, res.guests.size());
        assertEquals("Windows-11", res.guests.get(0).name);
        assertEquals(1, res.storagePools.size());
        assertEquals("local-lvm", res.storagePools.get(0).name);

        TestRefreshCallback cb = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState pres = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, res, pdmConfig, null, null, cb, null);
        assertTrue(pres.decision.accepted);
        assertTrue(pres.decision.isSuccess);
        assertTrue(cb.success);
        assertFalse("Storage loading must be cleared", pres.storageState.isLoading);
        assertEquals(1, pres.storageState.displayedPools.size());
        assertFalse("Guests loading must be cleared", pres.guestsState.isLoading);
        assertEquals(1, pres.guestsState.displayedGuests.size());
        assertTrue(pres.metricsState.hasData);
    }

    @Test
    public void testHealthyNodeFinishesWhenFailedRemoteIsFirst() throws Exception {
        resourcesPayload.set(buildTwoRemoteFleetPayload(false));

        NodeDetailActivity.NodeDataResult res = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab/home-lab", "test-pdm-id");

        assertNotNull(res);
        assertFalse(res.isOffline);
        assertNotNull(res.node);
        assertEquals("Windows-11", res.guests.get(0).name);
        assertEquals("local-lvm", res.storagePools.get(0).name);

        TestRefreshCallback cb = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState pres = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, res, pdmConfig, null, null, cb, null);
        assertTrue(pres.decision.accepted);
        assertTrue(pres.decision.isSuccess);
        assertTrue(cb.success);
        assertFalse("Storage loading must be cleared", pres.storageState.isLoading);
        assertEquals(1, pres.storageState.displayedPools.size());
        assertFalse("Guests loading must be cleared", pres.guestsState.isLoading);
        assertEquals(1, pres.guestsState.displayedGuests.size());
        assertTrue(pres.metricsState.hasData);
    }

    @Test
    public void testNodeDetailOwnerIncludesClusterSharedStorageWithoutNodeProvenance() throws Exception {
        // Multi-node cluster with:
        // 1. local-lvm explicitly belonging to home-lab
        // 2. sibling-pool explicitly belonging to home-lab-sibling (MUST NOT match)
        // 3. ceph-pool with NO node field, but shared=1 (MUST match cluster-wide)
        // 4. mystery-pool with NO node field and shared=0 (MUST NOT match)
        String payload = "{"
                + "\"data\":[{"
                + "  \"remote\":\"home-lab\","
                + "  \"resources\":["
                + "    {\"type\":\"node\",\"node\":\"home-lab\",\"status\":\"online\"},"
                + "    {\"type\":\"node\",\"node\":\"home-lab-sibling\",\"status\":\"online\"},"
                + "    {\"type\":\"pve-storage\",\"storage\":\"local-lvm\",\"node\":\"home-lab\",\"status\":\"available\",\"disk\":100,\"maxdisk\":1000},"
                + "    {\"type\":\"pve-storage\",\"storage\":\"sibling-pool\",\"node\":\"home-lab-sibling\",\"status\":\"available\",\"disk\":200,\"maxdisk\":2000},"
                + "    {\"type\":\"pve-storage\",\"storage\":\"ceph-shared\",\"shared\":1,\"status\":\"available\",\"disk\":500,\"maxdisk\":5000},"
                + "    {\"type\":\"pve-storage\",\"storage\":\"mystery-unshared\",\"shared\":0,\"status\":\"available\",\"disk\":300,\"maxdisk\":3000}"
                + "  ]"
                + "}]"
                + "}";
        resourcesPayload.set(payload);

        NodeDetailActivity.NodeDataResult res = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab/home-lab", "test-pdm-id");

        assertNotNull(res);
        assertFalse(res.isOffline);
        assertNotNull(res.storagePools);
        assertEquals("Selected node must include exactly 2 pools: local-lvm and ceph-shared", 2, res.storagePools.size());
        List<String> poolNames = new ArrayList<>();
        for (StoragePool sp : res.storagePools) {
            poolNames.add(sp.name);
        }
        assertTrue("Must include local pool", poolNames.contains("local-lvm"));
        assertTrue("Must include shared cluster pool", poolNames.contains("ceph-shared"));
        assertFalse("Sibling pool must NOT be included", poolNames.contains("sibling-pool"));
        assertFalse("Unshared pool without node provenance must NOT be included", poolNames.contains("mystery-unshared"));
    }

    @Test
    public void testNodeDetailOwnerApplyPresentationStateClearsLoadingAndMatchesComputedState() {
        // Explicitly tests view-binding owner protocol:
        // Verifies that completeAndBind computes exact presentation states, and applyPresentation binds those exact states.
        // NOTE: This test exercises the headless JVM view-binding owner protocol and lifecycle checks;
        // it does NOT claim physical phone hardware runtime execution.
        StoragePool pool = new StoragePool("local-lvm", "lvmthin", 1000, 500, 500, true, true, false, "", "home-lab/home-lab");
        GuestInfo guest = new GuestInfo(100, "Windows-11", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING, "home-lab/home-lab", "pdm", 0.05, 2, 1000, 2000, 1000, 2000, 0, 0, 0, 0, 100, "", false, 1000);
        NodeStatus node = new NodeStatus("home-lab/home-lab", "pdm", NodeStatus.Status.ONLINE, 0.15, 4, 1000, 2000, 1000, 2000, 0, 0, "Proxmox VE", 1000, System.currentTimeMillis());

        NodeDetailActivity.NodeDataResult healthyResult = new NodeDetailActivity.NodeDataResult(
                node, Collections.singletonList(pool), null, Collections.singletonList(guest), null, false
        );

        TestRefreshCallback cb = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState pres = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, healthyResult, pdmConfig, null, null, cb, null);

        assertTrue("Healthy presentation state must be accepted", pres.decision.accepted);
        assertTrue("Healthy presentation state must be marked success", pres.decision.isSuccess);
        assertTrue(cb.success);

        // Verify storage state computed by completeAndBind
        assertNotNull(pres.storageState);
        assertFalse("Storage loading must be cleared on successful completion", pres.storageState.isLoading);
        assertEquals(1, pres.storageState.displayedPools.size());
        assertEquals("local-lvm", pres.storageState.displayedPools.get(0).name);

        // Verify guest state computed by completeAndBind
        assertNotNull(pres.guestsState);
        assertFalse("Guests loading must be cleared on successful completion", pres.guestsState.isLoading);
        assertEquals(1, pres.guestsState.displayedGuests.size());
        assertEquals("Windows-11", pres.guestsState.displayedGuests.get(0).name);

        // Verify metrics state computed by completeAndBind
        assertNotNull(pres.metricsState);
        assertTrue(pres.metricsState.hasData);
        assertFalse(pres.metricsState.isStale);

        NodeDetailFixture fixture = createNodeDetailFixture();
        NodeDetailActivity activity = fixture.activity;
        android.widget.LinearLayout layoutStorage = fixture.layoutStorage;
        android.widget.LinearLayout layoutGuests = fixture.layoutGuests;
        android.widget.TextView tvCpu = fixture.tvCpu;
        android.widget.TextView tvRam = fixture.tvRam;
        android.widget.TextView tvDisk = fixture.tvDisk;
        android.widget.TextView tvUptime = fixture.tvUptime;
        android.widget.TextView tvKernel = fixture.tvKernel;
        android.widget.Button btnShell = fixture.btnShell;

        // Attach headless view recorder to Activity instance
        NodeDetailActivity.HeadlessViewRecorder recorder = new NodeDetailActivity.HeadlessViewRecorder();
        activity.setViewBindingListener(recorder);

        // Exercise initial loading state on actual views and recorder
        activity.setStorageLoading();
        activity.setGuestsLoading();
        assertTrue("Storage loading spinner active before presentation", recorder.isStorageLoading);
        assertTrue("Guests loading spinner active before presentation", recorder.isGuestsLoading);
        assertEquals("Storage layout must have loading TextView child", 1, layoutStorage.getChildCount());
        assertTrue("Storage loading text displayed",
                ((android.widget.TextView) layoutStorage.getChildAt(0)).getText().toString().contains("Loading"));
        assertEquals("Guests layout must have loading TextView child", 1, layoutGuests.getChildCount());
        assertTrue("Guests loading text displayed",
                ((android.widget.TextView) layoutGuests.getChildAt(0)).getText().toString().contains("Loading"));

        // Apply presentation to the real Activity instance
        activity.applyPresentation(pres);

        // Verify Activity owner cached state after healthy presentation
        assertEquals("Healthy node must be cached after applyPresentation", node, activity.getCachedNode());
        assertFalse("Node metrics must not be stale on healthy presentation", activity.isNodeMetricsStale());
        assertEquals("Cached storage pools must be populated", 1, activity.getCachedStoragePools().size());
        assertEquals("local-lvm", activity.getCachedStoragePools().get(0).name);
        assertEquals("Cached guests must be populated", 1, activity.getCachedGuests().size());
        assertEquals("Windows-11", activity.getCachedGuests().get(0).name);

        // Rendered view binding proof on Activity instance: loading spinners CLEARED!
        assertFalse("Rendered storage loading spinner must be CLEARED on healthy presentation", recorder.isStorageLoading);
        assertFalse("Rendered guests loading spinner must be CLEARED on healthy presentation", recorder.isGuestsLoading);
        assertNotNull("Rendered metrics state recorded", recorder.lastMetricsState);
        assertEquals("local-lvm", recorder.lastStorageState.displayedPools.get(0).name);
        assertEquals("Windows-11", recorder.lastGuestsState.displayedGuests.get(0).name);

        // Rendered view binding verified in actual fields: loading cleared, pools and metrics bound
        assertEquals("Storage layout child count represents bound cards", 1, layoutStorage.getChildCount());
        assertFalse("Loading spinner removed from storage layout", layoutStorage.getChildAt(0) instanceof android.widget.TextView);
        android.widget.LinearLayout card = (android.widget.LinearLayout) layoutStorage.getChildAt(0);
        android.widget.LinearLayout header = (android.widget.LinearLayout) card.getChildAt(0);
        android.widget.TextView tvPoolName = (android.widget.TextView) header.getChildAt(0);
        assertEquals("local-lvm", tvPoolName.getText().toString());
        assertNotNull("CPU text bound in TextView", tvCpu.getText());
        assertTrue("CPU usage text bound", tvCpu.getText().toString().contains("15"));
        assertNotNull("RAM text bound in TextView", tvRam.getText());
        assertNotNull("Disk text bound in TextView", tvDisk.getText());
        assertNotNull("Uptime text bound in TextView", tvUptime.getText());
        assertNotNull("Kernel text bound in TextView", tvKernel.getText());
        assertFalse("Shell button disabled for PDM node", btnShell.isEnabled());

        // Transition from healthy to subsequent refresh error:
        NodeDetailActivity.NodeDataResult errResult = new NodeDetailActivity.NodeDataResult(
                null, Collections.emptyList(), "Host unreachable (connection refused)",
                Collections.emptyList(), "Host unreachable", true
        );
        TestRefreshCallback cbErr = new TestRefreshCallback();
        NodeDetailActivity.NodeDetailPresentationState presErr = NodeDetailActivity.completeAndBind(
                true, false, false, false, true, errResult, pdmConfig,
                activity.getCachedNode(), activity.getCachedStoragePools(), activity.getCachedGuests(), cbErr, null);

        assertTrue("Error presentation decision must be accepted", presErr.decision.accepted);
        assertFalse("Error presentation decision must not be marked success", presErr.decision.isSuccess);
        assertNotNull(presErr.metricsState);
        assertTrue("Previous healthy telemetry data must be retained as stale on failure", presErr.metricsState.hasData);
        assertTrue("Metrics must be marked stale on failure", presErr.metricsState.isStale);

        // Apply error presentation to the same activity instance
        activity.applyPresentation(presErr);
        assertEquals("Cached node must remain preserved on error", node, activity.getCachedNode());
        assertTrue("isNodeMetricsStale must be true after error presentation", activity.isNodeMetricsStale());
        assertEquals("Cached storage pools must remain preserved", 1, activity.getCachedStoragePools().size());
        assertEquals("Cached guests must remain preserved", 1, activity.getCachedGuests().size());

        // Rendered view binding on error: loading stays cleared, error message bound
        assertFalse("Rendered storage loading stays cleared on error", recorder.isStorageLoading);
        assertFalse("Rendered guests loading stays cleared on error", recorder.isGuestsLoading);
        assertNotNull(recorder.lastStorageState.errorMessage);
        assertTrue(recorder.lastStorageState.errorMessage.contains("showing cached data"));

        // Stale transition check
        activity.markNodeMetricsStale();
        assertTrue("markNodeMetricsStale must keep stale true", activity.isNodeMetricsStale());
        assertEquals("Cached node must remain preserved after markNodeMetricsStale", node, activity.getCachedNode());
    }

    @Test
    public void testSlowSiblingDelayFixtureCompletesWithinDeadlineWithoutFreezing() throws Exception {
        // Sibling failure delays transport response by 100ms
        resourcesDelayMs.set(100);
        resourcesPayload.set(buildTwoRemoteFleetPayload(false)); // failed remote first

        long start = System.currentTimeMillis();
        NodeDetailActivity.NodeDataResult res = NodeDetailActivity.loadNodeData(
                pdmConfig, creds, "home-lab/home-lab", "test-pdm-id");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("Fixture must exercise real transport delay", elapsed >= 80);
        assertNotNull(res);
        assertFalse("Healthy node must not be marked offline despite slow sibling", res.isOffline);
        assertNotNull(res.node);
        assertEquals("Windows-11", res.guests.get(0).name);
        assertEquals("local-lvm", res.storagePools.get(0).name);
    }

    @Test
    public void testNodeDetailOwnerLifecycleStaleCallbackRejectionFiresTerminalErrorCallbackAndRejects() {
        // NOTE: This test exercises the headless JVM view-binding owner protocol and lifecycle checks;
        // it does NOT claim physical phone hardware runtime execution.
        StoragePool pool = new StoragePool("local-lvm", "lvmthin", 1000, 500, 500, true, true, false, "", "home-lab/home-lab");
        NodeStatus node = new NodeStatus("home-lab/home-lab", "pdm", NodeStatus.Status.ONLINE, 0.15, 4, 1000, 2000, 1000, 2000, 0, 0, "Proxmox VE", 1000, System.currentTimeMillis());
        NodeDetailActivity.NodeDataResult healthyResult = new NodeDetailActivity.NodeDataResult(
                node, Collections.singletonList(pool), null, Collections.emptyList(), null, false
        );

        TestRefreshCallback cb = new TestRefreshCallback();
        // canCallbackAccept = false simulates stale, replaced, or detached scheduler callback
        NodeDetailActivity.NodeDetailPresentationState pres = NodeDetailActivity.completeAndBind(
                true, false, false, false, false, healthyResult, pdmConfig, null, null, cb, null);

        assertFalse("Stale callback must be rejected", pres.decision.accepted);
        assertEquals("Stale", pres.decision.rejectionReason);
        assertFalse(cb.success);
        assertNotNull(cb.error);
        assertEquals("Stale", cb.errorReason);
        assertNull("No storage presentation state must be emitted when rejected", pres.storageState);
        assertNull("No guests presentation state must be emitted when rejected", pres.guestsState);
        assertNull("No metrics presentation state must be emitted when rejected", pres.metricsState);
    }

    @Test
    public void testNodeDetailOwnerLifecycleSessionLockedRejects() {
        // NOTE: This test exercises the headless JVM view-binding owner protocol and lifecycle checks;
        // it does NOT claim physical phone hardware runtime execution.
        NodeStatus node = new NodeStatus("home-lab/home-lab", "pdm", NodeStatus.Status.ONLINE, 0.15, 4, 1000, 2000, 1000, 2000, 0, 0, "Proxmox VE", 1000, System.currentTimeMillis());
        NodeDetailActivity.NodeDataResult res = new NodeDetailActivity.NodeDataResult(
                node, Collections.emptyList(), null, Collections.emptyList(), null, false
        );

        TestRefreshCallback cb = new TestRefreshCallback();
        // isSessionLocked = true
        NodeDetailActivity.NodeDetailPresentationState pres = NodeDetailActivity.completeAndBind(
                true, true, false, false, true, res, pdmConfig, null, null, cb, null);

        assertFalse("Session locked completion must be rejected", pres.decision.accepted);
        assertEquals("Session locked", pres.decision.rejectionReason);
        assertEquals("Session locked", cb.errorReason);
    }

    @Test
    public void testNodeDetailOwnerLifecycleActionInProgressRejects() {
        // NOTE: This test exercises the headless JVM view-binding owner protocol and lifecycle checks;
        // it does NOT claim physical phone hardware runtime execution.
        NodeStatus node = new NodeStatus("home-lab/home-lab", "pdm", NodeStatus.Status.ONLINE, 0.15, 4, 1000, 2000, 1000, 2000, 0, 0, "Proxmox VE", 1000, System.currentTimeMillis());
        NodeDetailActivity.NodeDataResult res = new NodeDetailActivity.NodeDataResult(
                node, Collections.emptyList(), null, Collections.emptyList(), null, false
        );

        TestRefreshCallback cb = new TestRefreshCallback();
        // isActionRunning = true
        NodeDetailActivity.NodeDetailPresentationState pres = NodeDetailActivity.completeAndBind(
                true, false, true, false, true, res, pdmConfig, null, null, cb, null);

        assertFalse("Action in progress must reject telemetry binding", pres.decision.accepted);
        assertEquals("Action in progress", pres.decision.rejectionReason);
        assertEquals("Action in progress", cb.errorReason);
    }

    @Test
    public void testNodeDetailOwnerLifecyclePauseResumeSingleTransportOwnershipCancelsInFlightTask() throws Exception {
        // NOTE: This test exercises the production NodeDetailActivity lifecycle owner,
        // onPause / onResume methods, AutoRefreshScheduler dispatch, and actual LoadNodeTask
        // with real loopback HTTPS transport. The headless JVM AsyncTask test double does not
        // execute callbacks on the Android main/UI thread (no Looper/Handler main queue),
        // routes execute() to THREAD_POOL_EXECUTOR rather than SERIAL_EXECUTOR, and does not
        // model queued serial cancel callbacks. It does NOT claim physical phone hardware runtime execution.
        resourcesPayload.set(buildTwoRemoteFleetPayload(false));
        CountDownLatch blockLatch = new CountDownLatch(1);
        resourcesLatch.set(blockLatch);
        resourcesEntered.set(false);

        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object u = f.get(null);
        java.lang.reflect.Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);
        NodeDetailActivity activity = (NodeDetailActivity) alloc.invoke(u, NodeDetailActivity.class);

        // Bind activity configuration fields
        java.lang.reflect.Field fNodeName = NodeDetailActivity.class.getDeclaredField("nodeName");
        fNodeName.setAccessible(true);
        fNodeName.set(activity, "home-lab/home-lab");

        java.lang.reflect.Field fServerId = NodeDetailActivity.class.getDeclaredField("serverId");
        fServerId.setAccessible(true);
        fServerId.set(activity, "test-pdm-id");

        java.lang.reflect.Field fServer = NodeDetailActivity.class.getDeclaredField("server");
        fServer.setAccessible(true);
        fServer.set(activity, pdmConfig);

        assertNull("Activity initial state has no cached node", activity.getCachedNode());

        Class<?> foundTaskClass = null;
        for (Class<?> c : NodeDetailActivity.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("LoadNodeTask")) {
                foundTaskClass = c;
                break;
            }
        }
        assertNotNull("LoadNodeTask inner class must exist on NodeDetailActivity", foundTaskClass);
        final Class<?> loadTaskClass = foundTaskClass;

        // Wire scheduler with controllable test clock/taskScheduler and production dispatch action
        AutoRefreshSchedulerTest.TestClock testClock = new AutoRefreshSchedulerTest.TestClock();
        AutoRefreshSchedulerTest.TestTaskScheduler testTaskScheduler = new AutoRefreshSchedulerTest.TestTaskScheduler(testClock);

        AtomicInteger dispatchCount = new AtomicInteger(0);

        AutoRefreshScheduler scheduler = new AutoRefreshScheduler(
                testClock,
                testTaskScheduler,
                HomelabApp.getInstance().getRefreshPreferenceStore(),
                callback -> {
                    dispatchCount.incrementAndGet();
                    try {
                        java.lang.reflect.Field fLoadTask = NodeDetailActivity.class.getDeclaredField("currentLoadTask");
                        fLoadTask.setAccessible(true);
                        Object prev = fLoadTask.get(activity);
                        if (prev instanceof AsyncTask) {
                            ((AsyncTask<?, ?, ?>) prev).cancel(true);
                        }
                        java.lang.reflect.Constructor<?> ctor = loadTaskClass.getDeclaredConstructor(
                                NodeDetailActivity.class, AutoRefreshScheduler.RefreshCallback.class);
                        ctor.setAccessible(true);
                        Object task = ctor.newInstance(activity, callback);
                        fLoadTask.set(activity, task);
                        // Cast to AsyncTask<Void, ?, ?> and pass (Void[]) null explicitly to prevent
                        // javac bridge invocation from passing an Object[] array which fails with
                        // ClassCastException on JVM runtime bridge cast to Void[].
                        @SuppressWarnings("unchecked")
                        AsyncTask<Void, ?, ?> asyncTask = (AsyncTask<Void, ?, ?>) task;
                        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                () -> ActionGuard.getInstance().isRunning("home-lab/home-lab")
        );

        java.lang.reflect.Field fScheduler = NodeDetailActivity.class.getDeclaredField("scheduler");
        fScheduler.setAccessible(true);
        fScheduler.set(activity, scheduler);

        java.lang.reflect.Method mResume = NodeDetailActivity.class.getDeclaredMethod("onResume");
        mResume.setAccessible(true);
        java.lang.reflect.Method mPause = NodeDetailActivity.class.getDeclaredMethod("onPause");
        mPause.setAccessible(true);
        java.lang.reflect.Field fCurrentTask = NodeDetailActivity.class.getDeclaredField("currentLoadTask");
        fCurrentTask.setAccessible(true);

        // Step 1: Initial lifecycle transition - invoke production onResume()
        mResume.invoke(activity);

        System.out.println("DEBUG dispatchCount=" + dispatchCount.get() +
                " schedulerLifecycle=" + scheduler.getLifecycleState() +
                " schedulerExec=" + scheduler.getExecutionState() +
                " currentLoadTask=" + fCurrentTask.get(activity));

        // Wait until actual LoadNodeTask has entered loopback HTTPS and is actively blocked
        long waitDeadline = System.currentTimeMillis() + 3000L;
        while (!resourcesEntered.get() && System.currentTimeMillis() < waitDeadline) {
            Thread.sleep(10);
        }
        System.out.println("DEBUG after wait: resourcesEntered=" + resourcesEntered.get() +
                " dispatchCount=" + dispatchCount.get() +
                " currentLoadTask=" + fCurrentTask.get(activity));
        assertTrue("Actual LoadNodeTask must be actively blocked in-flight on loopback HTTPS", resourcesEntered.get());
        assertEquals("Initial dispatch must occur", 1, dispatchCount.get());
        assertEquals("Single transport in flight on wire", 1, activeResourcesRequests.get());
        assertEquals("Max concurrent requests on wire is 1", 1, maxConcurrentResourcesRequests.get());

        // Step 2: Lifecycle transition - invoke production onPause() while HTTPS transport is actively blocked
        mPause.invoke(activity);

        assertNull("Production onPause() clears currentLoadTask reference", fCurrentTask.get(activity));
        assertEquals("Original transport is still in-flight on wire after onPause()", 1, activeResourcesRequests.get());

        // Step 3: Lifecycle transition - invoke production onResume() BEFORE releasing the original response!
        mResume.invoke(activity);

        // Crucial single-transport assertion: scheduler must NOT launch duplicate concurrent transport
        assertEquals("Scheduler must NOT launch duplicate dispatch while transport 1 is in-flight", 1, dispatchCount.get());
        assertEquals("Concurrent in-flight requests on wire must remain 1", 1, activeResourcesRequests.get());
        assertEquals("Max concurrent requests on wire must remain 1", 1, maxConcurrentResourcesRequests.get());

        // Step 4: Advance watchdog, backoff, and cadence while original response remains blocked
        // Watchdog timeout is FETCH_TIMEOUT_MS (60,000 ms): advance clock past 60s and verify watchdog firing preserves single-transport ownership
        testTaskScheduler.advanceTime(60_000L);
        assertEquals("Watchdog timeout must not launch overlapping transport", 1, dispatchCount.get());
        assertEquals("Max concurrent requests on wire remains 1", 1, maxConcurrentResourcesRequests.get());

        // Advance cadence / backoff check (30,000 ms) while transport still blocked
        testTaskScheduler.advanceTime(30_000L);
        assertEquals("Cadence/backoff must not launch overlapping transport while transport 1 is in-flight", 1, dispatchCount.get());
        assertEquals("Max concurrent requests on wire remains 1", 1, maxConcurrentResourcesRequests.get());

        // Step 5: Unblock original loopback HTTPS response
        resourcesLatch.set(null); // Allow subsequent requests to complete freely
        blockLatch.countDown();

        // Wait for terminal retirement of Task 1 and dispatch + completion of fresh resumed Task 2
        long finishDeadline = System.currentTimeMillis() + 5000L;
        while (activity.getCachedNode() == null && System.currentTimeMillis() < finishDeadline) {
            Thread.sleep(20);
        }

        // Step 6: Verify terminal retirement and subsequent healthy detail binding
        assertNotNull("Resumed fresh transport must authoritatively update Activity cached node", activity.getCachedNode());
        assertEquals("home-lab/home-lab", activity.getCachedNode().node);
        assertFalse("Metrics must not be stale", activity.isNodeMetricsStale());
        assertEquals(1, activity.getCachedStoragePools().size());
        assertEquals("local-lvm", activity.getCachedStoragePools().get(0).name);
        assertEquals(1, activity.getCachedGuests().size());
        assertEquals("Windows-11", activity.getCachedGuests().get(0).name);

        assertEquals("Dispatch count must be exactly 2 (initial cancelled + resumed fresh)", 2, dispatchCount.get());
        assertEquals("Maximum simultaneous transports on wire must NEVER exceed 1", 1, maxConcurrentResourcesRequests.get());
    }

    // =========================================================================
    // 12. Reproduction & Ranking: VmDetail Power Task & Watchdog vs Cumulative
    // =========================================================================

    @Test
    public void testVmDetailPowerTaskClearsOwnerBeforePostOperationRefresh() throws Exception {
        // Hypothesis 1 (Rank 1): VmDetailActivity onPostExecute must clear currentPowerTask
        // BEFORE invoking transitionCallerAction / requestPostOperationRefresh().
        // If currentPowerTask remains non-null when requestPostOperationRefresh() evaluates
        // its actionGuardPredicate, the scheduler considers an action in progress and defers
        // the refresh by 1500ms rather than dispatching immediate fresh read.
        VmDetailActivity activity = createVmDetailActivity();
        setField(activity, "serverId", "test-pdm-id");
        setField(activity, "node", "home-lab/home-lab");
        setField(activity, "vmid", 100);
        setField(activity, "type", GuestInfo.GuestType.QEMU);
        setField(activity, "isActivityResumed", true);

        AutoRefreshSchedulerTest.TestClock clock = new AutoRefreshSchedulerTest.TestClock();
        AutoRefreshSchedulerTest.TestTaskScheduler taskScheduler = new AutoRefreshSchedulerTest.TestTaskScheduler(clock);
        AtomicInteger fetchDispatched = new AtomicInteger(0);

        AutoRefreshScheduler scheduler = new AutoRefreshScheduler(
                clock,
                taskScheduler,
                HomelabApp.getInstance().getRefreshPreferenceStore(),
                callback -> fetchDispatched.incrementAndGet(),
                () -> activity.getCurrentPowerTask() != null
        );
        scheduler.resume();

        java.lang.reflect.Field fSched = VmDetailActivity.class.getDeclaredField("scheduler");
        fSched.setAccessible(true);
        fSched.set(activity, scheduler);

        VmDetailActivity.ExecutePowerTask powerTask = activity.new ExecutePowerTask("start", null);
        activity.setCurrentPowerTask(powerTask);
        assertNotNull("Current power task set on activity", activity.getCurrentPowerTask());

        // Execute onPostExecute with successful task completion
        TaskResult okResult = new TaskResult("UPID:home-lab:100", TaskResult.TaskState.OK, null, null, 0, 0, "home-lab");
        java.lang.reflect.Method mPost = powerTask.getClass().getDeclaredMethod("onPostExecute", TaskResult.class);
        mPost.setAccessible(true);
        mPost.invoke(powerTask, okResult);

        // 1. currentPowerTask must be cleared to null on the activity instance
        assertNull("currentPowerTask must be cleared to null after onPostExecute", activity.getCurrentPowerTask());

        // 2. Scheduler must NOT have deferred refresh: immediate fresh fetch must be dispatched
        assertEquals("Post-operation fresh read must be dispatched immediately without 1500ms deferral", 1, fetchDispatched.get());
        assertEquals("Scheduler execution state must be FETCHING, not DEFERRED",
                AutoRefreshScheduler.ExecutionState.FETCHING, scheduler.getExecutionState());

        // 3. Last acknowledged action must be recorded on Activity instance
        assertNotNull(activity.getLastAcknowledgedAction());
        assertTrue(activity.getLastAcknowledgedAction().isSuccess);
    }

    @Test
    public void testWatchdogTimeoutVsCumulativeLoadingBehavior() throws Exception {
        // Hypothesis 2 (Rank 2): NodeDetailActivity load cumulative deadline is 20s (15s fleet + 5s enrichment).
        // Under watchdog timeout (e.g. 60s), late responses arriving after watchdog timeout are rejected by
        // completeAndBind to preserve stale isolation; a subsequent retry or completion within deadline
        // accepted by completeAndBind clears loading through HeadlessViewRecorder.
        NodeDetailFixture fixture = createNodeDetailFixture();
        NodeDetailActivity activity = fixture.activity;
        android.widget.LinearLayout layoutStorage = fixture.layoutStorage;
        android.widget.LinearLayout layoutGuests = fixture.layoutGuests;
        android.widget.Button btnShell = fixture.btnShell;

        NodeDetailActivity.HeadlessViewRecorder recorder = new NodeDetailActivity.HeadlessViewRecorder();
        activity.setViewBindingListener(recorder);

        // 1. Initial cold start enters loading on recorder and actual view fields
        activity.setStorageLoading();
        activity.setGuestsLoading();
        assertTrue("Cold start storage loading is active", recorder.isStorageLoading);
        assertTrue("Cold start guests loading is active", recorder.isGuestsLoading);
        assertEquals("Storage layout must have loading TextView child", 1, layoutStorage.getChildCount());
        assertTrue("Storage loading text displayed",
                ((android.widget.TextView) layoutStorage.getChildAt(0)).getText().toString().contains("Loading"));
        assertEquals("Guests layout must have loading TextView child", 1, layoutGuests.getChildCount());
        assertTrue("Guests loading text displayed",
                ((android.widget.TextView) layoutGuests.getChildAt(0)).getText().toString().contains("Loading"));

        // 2. Simulate watchdog timeout: callback can no longer accept results (stale token)
        TestRefreshCallback staleCallback = new TestRefreshCallback();
        staleCallback.canAccept = false; // watchdog fired and invalidated generation

        StoragePool pool = new StoragePool("local-lvm", "lvmthin", 1000, 500, 500, true, true, false, "", "home-lab/home-lab");
        GuestInfo guest = new GuestInfo(100, "Windows-11", GuestInfo.GuestType.QEMU, GuestInfo.RunState.RUNNING, "home-lab/home-lab", "pdm", 0.05, 2, 1000, 2000, 1000, 2000, 0, 0, 0, 0, 100, "", false, 1000);
        NodeStatus node = new NodeStatus("home-lab/home-lab", "pdm", NodeStatus.Status.ONLINE, 0.15, 4, 1000, 2000, 1000, 2000, 0, 0, "Proxmox VE", 1000, System.currentTimeMillis());
        NodeDetailActivity.NodeDataResult lateResult = new NodeDetailActivity.NodeDataResult(
                node, Collections.singletonList(pool), null, Collections.singletonList(guest), null, false
        );

        // 3. Late response after watchdog timeout is rejected by completeAndBind
        NodeDetailActivity.NodeDetailPresentationState latePres = NodeDetailActivity.completeAndBind(
                true, false, false, false, staleCallback.canAcceptResult(), lateResult, pdmConfig, null, null, staleCallback, null);
        assertFalse("Late response after watchdog timeout must be rejected for stale isolation", latePres.decision.accepted);
        assertEquals("Stale", latePres.decision.rejectionReason);

        // Rejected presentation is NOT applied: cold view preserves loading until fresh valid retry
        activity.applyPresentation(latePres);
        assertTrue("Loading remains active when late response was rejected", recorder.isStorageLoading);
        assertTrue("Loading remains active when late response was rejected", recorder.isGuestsLoading);
        assertEquals("Storage layout retains loading TextView child on rejected presentation", 1, layoutStorage.getChildCount());
        assertTrue("Storage loading text still displayed",
                ((android.widget.TextView) layoutStorage.getChildAt(0)).getText().toString().contains("Loading"));

        // 4. Fresh retry arrives with valid token within deadline: accepted and clears loading
        TestRefreshCallback freshCallback = new TestRefreshCallback();
        freshCallback.canAccept = true;
        NodeDetailActivity.NodeDetailPresentationState freshPres = NodeDetailActivity.completeAndBind(
                true, false, false, false, freshCallback.canAcceptResult(), lateResult, pdmConfig, null, null, freshCallback, null);
        assertTrue("Fresh response within deadline must be accepted", freshPres.decision.accepted);
        activity.applyPresentation(freshPres);

        // Rendered loading is cleared and views bound!
        assertFalse("Rendered storage loading must be cleared on accepted presentation", recorder.isStorageLoading);
        assertFalse("Rendered guests loading must be cleared on accepted presentation", recorder.isGuestsLoading);
        assertEquals("local-lvm", recorder.lastStorageState.displayedPools.get(0).name);
        assertEquals("Windows-11", recorder.lastGuestsState.displayedGuests.get(0).name);

        // Verified on actual view fields: loading removed, cards bound, shell disabled for PDM
        assertEquals("Storage layout child count represents bound cards", 1, layoutStorage.getChildCount());
        assertFalse("Loading spinner removed from storage layout", layoutStorage.getChildAt(0) instanceof android.widget.TextView);
        android.widget.LinearLayout card = (android.widget.LinearLayout) layoutStorage.getChildAt(0);
        android.widget.LinearLayout header = (android.widget.LinearLayout) card.getChildAt(0);
        android.widget.TextView tvPoolName = (android.widget.TextView) header.getChildAt(0);
        assertEquals("local-lvm", tvPoolName.getText().toString());
        assertFalse("Loading text absent from pool label", tvPoolName.getText().toString().contains("Loading"));
        assertEquals("Guests layout child count represents bound cards", 1, layoutGuests.getChildCount());
        assertFalse("Shell button disabled for PDM node", btnShell.isEnabled());
    }
}
