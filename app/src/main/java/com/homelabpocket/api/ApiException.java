// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — API exception
package com.homelabpocket.api;

/**
 * Exception thrown by API clients for HTTP errors, TLS failures, and timeouts.
 * Always includes an HTTP status code (0 for non-HTTP errors).
 */
public class ApiException extends Exception {

    public final int httpCode;

    public int getHttpCode() {
        return httpCode;
    }

    public ApiException(int httpCode, String message) {
        super(message);
        this.httpCode = httpCode;
    }

    public ApiException(int httpCode, String message, Throwable cause) {
        super(message, cause);
        this.httpCode = httpCode;
    }

    public boolean isAuthError() {
        return httpCode == 401 || httpCode == 403;
    }

    public boolean isNetworkError() {
        return httpCode == 0;
    }

    public boolean isTlsError() {
        Throwable cause = getCause();
        return cause instanceof javax.net.ssl.SSLHandshakeException ||
               cause instanceof java.security.cert.CertificateException;
    }

    /**
     * User-friendly description for display in the UI.
     * Does NOT include any credential information.
     */
    public String getUserMessage() {
        if (isTlsError()) {
            return "TLS/certificate error: " + getMessage()
                   + "\nIf the server certificate changed, verify with your administrator.";
        }
        if (isAuthError()) {
            return getMessage(); // Already user-friendly from ApiClient
        }
        if (isNetworkError()) {
            return "Network error: " + getMessage()
                   + "\nCheck server address and that the host is reachable.";
        }
        return getMessage();
    }
}
