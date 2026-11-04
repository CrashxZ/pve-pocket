// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — HTTP client with cert pinning, genuine endpoint identity, and total deadline enforcement
package com.homelabpocket.api;

import com.homelabpocket.model.AuthCredentials;
import com.homelabpocket.model.ServerConfig;
import com.homelabpocket.security.EndpointHostnameVerifier;
import com.homelabpocket.security.PinnedTrustManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * HTTP client for Proxmox API calls.
 *
 * Security & Reliability Guarantees:
 * - Per-server certificate fingerprint pinning (PinnedTrustManager) - fail-closed.
 * - Genuine endpoint identity verification (EndpointHostnameVerifier) - no hostname bypass.
 * - Automatic redirects disabled for credential-bearing traffic to prevent forwarding to other origins.
 * - SINGLE absolute monotonic request deadline enforced across all phases: connect, TLS handshake,
 *   request write, response headers, and response body read (no dripping-body bypass).
 * - Bounded maximum response size to prevent memory exhaustion from runaway streams.
 * - Static single-threaded daemon watchdog with disconnect & interruption (no thread leaks).
 * - Credentials added to headers/body only, never logged or leaked.
 * - No global trust-all SSL override.
 *
 * Thread model: synchronous request execution with monotonic deadline enforcement.
 */
public class ApiClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    public static final long DEFAULT_TOTAL_BUDGET_MS = 40_000L;
    public static final int MAX_RESPONSE_CHARS = 2 * 1024 * 1024; // 2MB response size limit

    // Shared single-thread daemon executor for timeout enforcement across all requests (no thread leaks)
    private static final ScheduledExecutorService TIMEOUT_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "homelab-api-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final ServerConfig config;
    private final PinnedTrustManager trustManager;
    private final SSLContext sslContext;

    public ApiClient(ServerConfig config) throws GeneralSecurityException {
        if (config == null) {
            throw new IllegalArgumentException("ServerConfig cannot be null");
        }
        if (config.isLegacyDemo()) {
            throw new GeneralSecurityException("Refusing API client connection: Demo server entries cannot make network connections.");
        }
        this.config = config;

        if (config.skipTlsVerify) {
            // Explicit per-server opt-in to bypass TLS verification.
            // Configured strictly on this instance/connection — NEVER set globally on default factories!
            this.trustManager = null;
            this.sslContext = createInsecureSslContext();
        } else {
            if (config.pinnedCertFingerprint == null || config.pinnedCertFingerprint.trim().isEmpty()) {
                throw new GeneralSecurityException("Refusing API client connection: Server '" + config.name +
                        "' has no approved TLS certificate fingerprint pinned. Pin must be approved before connecting.");
            }
            if (!PinnedTrustManager.isValidFingerprint(config.pinnedCertFingerprint)) {
                throw new GeneralSecurityException("Refusing API client connection: Malformed pinned TLS certificate fingerprint for server '" +
                        config.name + "': " + config.pinnedCertFingerprint);
            }

            this.trustManager = new PinnedTrustManager(config.pinnedCertFingerprint);
            this.sslContext = SSLContext.getInstance("TLS");
            this.sslContext.init(null, new TrustManager[]{trustManager}, null);
        }
    }

    private static SSLContext createInsecureSslContext() throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new javax.net.ssl.X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        }}, new java.security.SecureRandom());
        return ctx;
    }

    /**
     * HTTP GET with authentication headers and bounded network timeout.
     * Enforces a hard total monotonic deadline across connect, TLS, headers, and body streaming.
     */
    public String getBounded(String path, AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, IOException {
        return executeRequest(path, "GET", null, auth, remainingBudgetMs);
    }

    /**
     * HTTP GET with authentication headers and default total budget (40s).
     */
    public String get(String path, AuthCredentials auth) throws ApiException, IOException {
        return executeRequest(path, "GET", null, auth, DEFAULT_TOTAL_BUDGET_MS);
    }

    public String get(String path, AuthCredentials auth, long budgetMs) throws ApiException, IOException {
        return executeRequest(path, "GET", null, auth, budgetMs);
    }

    /**
     * HTTP GET with authentication headers and explicit connect/read timeouts.
     */
    public String get(String path, AuthCredentials auth, int connectTimeoutMs, int readTimeoutMs)
            throws ApiException, IOException {
        long totalBudget = Math.max(1, (long) connectTimeoutMs + (long) readTimeoutMs);
        return executeRequest(path, "GET", null, auth, totalBudget);
    }

    /**
     * HTTP POST with URL-encoded form body and authentication.
     */
    public String post(String path, String formBody, AuthCredentials auth)
            throws ApiException, IOException {
        return executeRequest(path, "POST", formBody, auth, DEFAULT_TOTAL_BUDGET_MS);
    }

    public String post(String path, String formBody, AuthCredentials auth, long budgetMs)
            throws ApiException, IOException {
        return executeRequest(path, "POST", formBody, auth, budgetMs);
    }

    /**
     * HTTP POST with URL-encoded form body, authentication, and bounded timeout.
     */
    public String postBounded(String path, String formBody, AuthCredentials auth, long remainingBudgetMs)
            throws ApiException, IOException {
        return executeRequest(path, "POST", formBody, auth, remainingBudgetMs);
    }

    /**
     * POST to /access/ticket to obtain a short-lived PVE ticket with default timeout.
     * Password is used only here and NOT stored after this call.
     */
    public AuthCredentials authenticate(String username, String password)
            throws ApiException, IOException {
        return authenticate(username, password, 15_000L);
    }

    /**
     * POST to /access/ticket to obtain a short-lived PVE ticket with explicit remaining budget limit.
     * Password is used only here and NOT stored after this call.
     */
    public AuthCredentials authenticate(String username, String password, long remainingBudgetMs)
            throws ApiException, IOException {
        long budget = (remainingBudgetMs > 0) ? remainingBudgetMs : 15_000L;
        String cleanUser = (username != null) ? username.trim() : "";
        String authUser = (!cleanUser.contains("@") && !cleanUser.isEmpty()) ? (cleanUser + "@pam") : cleanUser;
        String formBody = "username=" + urlEncode(authUser)
                + "&password=" + urlEncode(password != null ? password : "");
        String response = executeRequest("/access/ticket", "POST", formBody, null, budget);
        return parseTicketResponse(response);
    }

    /**
     * Core request executor with single monotonic request deadline enforcement.
     *
     * Enforces:
     * - Immediate failure if remaining budget <= 0.
     * - Monotonic deadline covering connect, TLS, headers, and body streaming.
     * - Watchdog timer that disconnects socket and interrupts blocking I/O upon deadline expiration.
     * - Dynamic socket timeouts bounded by remaining budget between phases.
     * - Chunked body reading with monotonic clock checks and bounded response size.
     * - Guaranteed cleanup of connection and watchdog cancellation in finally.
     */
    private String executeRequest(String path, String method, String formBody, AuthCredentials auth, long budgetMs)
            throws ApiException, IOException {

        if (budgetMs <= 0) {
            throw new ApiException(0, "Request deadline already expired before dispatch (" + budgetMs + "ms remaining)");
        }

        long startNanos = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMs);
        long deadlineNanos = startNanos + budgetNanos;

        final AtomicBoolean timedOut = new AtomicBoolean(false);
        final Thread callingThread = Thread.currentThread();

        HttpsURLConnection conn = openConnection(path);

        // Schedule watchdog to abort socket and interrupt blocking call if deadline is reached
        ScheduledFuture<?> watchdog = TIMEOUT_WATCHDOG.schedule(() -> {
            timedOut.set(true);
            try {
                conn.disconnect();
            } catch (Throwable ignored) {}
            callingThread.interrupt();
        }, Math.max(1, budgetMs), TimeUnit.MILLISECONDS);

        try {
            conn.setRequestMethod(method);
            addAuthHeaders(conn, auth);

            byte[] bodyBytes = null;
            if ("POST".equals(method) && formBody != null) {
                conn.setDoOutput(true);
                bodyBytes = formBody.getBytes("UTF-8");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            }

            // Phase 1: Connect & TLS Handshake
            long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0 || timedOut.get()) {
                throw new SocketTimeoutException("Request deadline exceeded before connection establishment (" + budgetMs + "ms limit)");
            }
            conn.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, Math.max(1, remainingMs)));
            conn.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, Math.max(1, remainingMs)));
            conn.connect();

            // Phase 2: Write Request Body (if POST)
            if ("POST".equals(method) && bodyBytes != null) {
                remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0 || timedOut.get()) {
                    throw new SocketTimeoutException("Request deadline exceeded before writing request body (" + budgetMs + "ms limit)");
                }
                try {
                    conn.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, Math.max(1, remainingMs)));
                } catch (Exception ignored) {}

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bodyBytes);
                    out.flush();
                }
            }

            // Phase 3: Response Headers
            remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0 || timedOut.get()) {
                throw new SocketTimeoutException("Request deadline exceeded before response headers (" + budgetMs + "ms limit)");
            }
            try {
                conn.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, Math.max(1, remainingMs)));
            } catch (Exception ignored) {}

            int code = conn.getResponseCode();

            // Refuse automatic redirects on credential-bearing traffic to prevent forwarding credentials to other origins
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                throw new ApiException(code, "HTTP redirect (" + code + " to " +
                        (location != null ? location : "other origin") +
                        ") blocked: Automatic redirects disabled on credential-bearing requests to prevent credential leakage.");
            }

            // Phase 4: Response Body Streaming with Monotonic Deadline & Bounded Size
            InputStream stream;
            if (code >= 200 && code < 300) {
                stream = conn.getInputStream();
            } else {
                stream = conn.getErrorStream();
                if (stream == null) {
                    throw new ApiException(code, "HTTP " + code + " with no error body");
                }
            }

            StringBuilder sb = new StringBuilder();
            try (InputStreamReader reader = new InputStreamReader(stream, "UTF-8")) {
                char[] buffer = new char[2048];
                int n;
                while (true) {
                    remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                    if (remainingMs <= 0 || timedOut.get()) {
                        throw new SocketTimeoutException("Total request deadline exceeded (" + budgetMs + "ms limit) while reading response body");
                    }
                    try {
                        conn.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, Math.max(1, remainingMs)));
                    } catch (Exception ignored) {}

                    n = reader.read(buffer);
                    if (n == -1) break;

                    sb.append(buffer, 0, n);
                    if (sb.length() > MAX_RESPONSE_CHARS) {
                        throw new ApiException(0, "Response exceeded maximum allowed size (" + MAX_RESPONSE_CHARS + " chars)");
                    }
                }
            }

            String body = sb.toString();
            if (code == 401) {
                throw new ApiException(401, "Authentication failed or token expired. Please re-authenticate.");
            } else if (code == 403) {
                throw new ApiException(403, "Permission denied. Check API token permissions or user roles.");
            } else if (code >= 400) {
                String msg = extractError(body, code);
                throw new ApiException(code, msg);
            }
            return body;

        } catch (SocketTimeoutException e) {
            throw new ApiException(0, "Total request deadline exceeded (" + budgetMs + "ms limit): " + e.getMessage());
        } catch (IOException e) {
            if (timedOut.get() || (System.nanoTime() >= deadlineNanos)) {
                throw new ApiException(0, "Total request deadline exceeded (" + budgetMs + "ms limit): " + e.getMessage());
            }
            throw e;
        } finally {
            watchdog.cancel(true);
            // Clear interrupted state if set by watchdog
            if (timedOut.get() || Thread.interrupted()) {
                Thread.interrupted();
            }
            conn.disconnect();
        }
    }

    private AuthCredentials parseTicketResponse(String response)
            throws ApiException {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject data = root.getJSONObject("data");
            String ticket = data.getString("ticket");
            String csrf = data.getString("CSRFPreventionToken");
            String username = data.optString("username", "");
            long expiresAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2);
            return AuthCredentials.ticket(username, ticket, csrf, expiresAt);
        } catch (JSONException e) {
            throw new ApiException(0, "Failed to parse ticket response: " + e.getMessage());
        }
    }

    private HttpsURLConnection openConnection(String path) throws IOException, ApiException {
        if (!config.skipTlsVerify) {
            if (config.pinnedCertFingerprint == null || config.pinnedCertFingerprint.trim().isEmpty()) {
                throw new ApiException(0, "Refusing connection: Server '" + config.name + "' has no pinned TLS certificate fingerprint.");
            }
        }
        String urlStr = config.getApiBaseUrl() + path;
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sslContext.getSocketFactory());

        if (config.skipTlsVerify) {
            // Opted-in server bypasses pin & hostname validation on this connection ONLY, NEVER globally
            conn.setHostnameVerifier((hostname, session) -> true);
        } else {
            // Genuine endpoint identity verification: strictly validates target host & pinned cert
            conn.setHostnameVerifier(new EndpointHostnameVerifier(config.host, config.pinnedCertFingerprint));
        }

        // Disable automatic redirects for credential-bearing requests to prevent credential forwarding
        conn.setInstanceFollowRedirects(false);

        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "HomelabPocket/1.0");
        return conn;
    }

    private void addAuthHeaders(HttpsURLConnection conn, AuthCredentials auth) {
        if (auth == null) return;
        if (auth.isApiToken()) {
            conn.setRequestProperty("Authorization", auth.getAuthorizationHeader(config.type));
        } else {
            conn.setRequestProperty("Cookie", auth.getTicketCookieValue(config.type));
            if (auth.csrfToken != null) {
                conn.setRequestProperty("CSRFPreventionToken", auth.csrfToken);
            }
        }
    }

    private String extractError(String body, int code) {
        try {
            JSONObject root = new JSONObject(body);
            if (root.has("errors")) {
                return "HTTP " + code + ": " + root.get("errors").toString();
            }
            if (root.has("message")) {
                return "HTTP " + code + ": " + root.getString("message");
            }
        } catch (Exception ignored) {}
        return "HTTP " + code;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public ServerConfig getConfig() {
        return config;
    }
}
