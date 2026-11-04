// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Genuine Endpoint Identity Validator
package com.homelabpocket.security;

import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

/**
 * Genuine endpoint identity validator for explicitly approved private/self-signed certificates.
 *
 * Enforces:
 * 1. Target host matches the configured server endpoint (RFC identity match).
 * 2. TLS session peer certificate matches the explicitly pinned SHA-256 fingerprint for this host.
 * 3. Genuine RFC 6125 / RFC 2818 Subject Alternative Name (SAN) DNS and IP verification:
 *    - SAN precedence: When SAN is present (with dNSName or iPAddress), CN is strictly ignored.
 *    - IP address verification: Target IP must match a type 7 (iPAddress) SAN entry.
 *    - DNS name verification: Target domain must match a type 2 (dNSName) SAN entry.
 *    - Safe wildcard rules: Wildcard '*' allowed only in the left-most label of domain patterns,
 *      never matches multiple labels, top-level domains, or IP addresses.
 *    - Fallback to Common Name (CN) ONLY when SAN extension is completely absent / contains no DNS/IP entries.
 *    - No reverse DNS: IP and domain comparisons are performed strictly in memory without network resolution.
 *    - No hostname bypass: Both caller hostname and session peerHost (if present) must match expected host.
 */
public class EndpointHostnameVerifier implements HostnameVerifier {

    private final String expectedHost;
    private final String expectedFingerprint;

    public EndpointHostnameVerifier(String expectedHost, String expectedFingerprint) {
        if (expectedHost == null || expectedHost.trim().isEmpty()) {
            throw new IllegalArgumentException("Expected host cannot be null or empty");
        }
        if (expectedFingerprint == null || expectedFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("Expected fingerprint cannot be null or empty");
        }
        this.expectedHost = normalizeHost(expectedHost);
        this.expectedFingerprint = expectedFingerprint.trim();
    }

    @Override
    public boolean verify(String hostname, SSLSession session) {
        if (hostname == null || session == null) {
            return false;
        }

        // 1. Caller hostname must match expected host (no hostname bypass)
        String normHostname = normalizeHost(hostname);
        if (!expectedHost.equalsIgnoreCase(normHostname)) {
            return false;
        }

        // 2. PeerHost from session (if provided) must not disagree with expected host
        String peerHost = session.getPeerHost();
        if (peerHost != null && !peerHost.trim().isEmpty()) {
            String normPeerHost = normalizeHost(peerHost);
            if (!expectedHost.equalsIgnoreCase(normPeerHost)) {
                return false;
            }
        }

        // 3. Extract peer certificates
        try {
            Certificate[] peerCerts = session.getPeerCertificates();
            if (peerCerts == null || peerCerts.length == 0 || !(peerCerts[0] instanceof X509Certificate)) {
                return false;
            }
            X509Certificate serverCert = (X509Certificate) peerCerts[0];

            // 4. Approved pinned fingerprint verification
            String actualFp = PinnedTrustManager.computeFingerprint(serverCert);
            if (!PinnedTrustManager.matches(actualFp, expectedFingerprint)) {
                return false;
            }

            // 5. Genuine RFC certificate identity verification (SAN / CN)
            return verifyCertificateIdentity(expectedHost, serverCert);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifies that the certificate identity matches the target host following RFC 6125 / RFC 2818 rules.
     */
    public static boolean verifyCertificateIdentity(String targetHost, X509Certificate cert) {
        if (targetHost == null || cert == null) {
            return false;
        }
        String host = normalizeHost(targetHost);
        if (host.isEmpty()) {
            return false;
        }

        boolean isIp = isIpAddress(host);

        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            boolean hasSan = false;
            if (sans != null) {
                for (List<?> item : sans) {
                    if (item != null && item.size() >= 2 && item.get(0) instanceof Integer) {
                        int type = (Integer) item.get(0);
                        if (type == 2 || type == 7) { // 2 = dNSName, 7 = iPAddress
                            hasSan = true;
                            break;
                        }
                    }
                }
            }

            if (hasSan) {
                // RFC 6125 SAN Precedence: When SAN contains dNSName or iPAddress, CN is strictly IGNORED.
                for (List<?> item : sans) {
                    if (item == null || item.size() < 2 || !(item.get(0) instanceof Integer)) {
                        continue;
                    }
                    int type = (Integer) item.get(0);
                    Object valObj = item.get(1);
                    if (valObj == null) continue;
                    String sanValue = valObj.toString().trim();

                    if (isIp && type == 7) { // iPAddress
                        if (matchIpAddress(host, sanValue)) {
                            return true;
                        }
                    } else if (!isIp && type == 2) { // dNSName
                        if (matchDomain(host, sanValue)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            // Fallback to CN only when SAN extension is completely absent or contains no DNS/IP entries
            String cn = extractCommonName(cert);
            if (cn != null && !cn.trim().isEmpty()) {
                cn = cn.trim();
                if (isIp) {
                    return matchIpAddress(host, cn);
                } else {
                    return matchDomain(host, cn);
                }
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a domain matches a pattern according to RFC 6125 safe wildcard rules.
     */
    public static boolean matchDomain(String targetHost, String pattern) {
        if (targetHost == null || pattern == null) return false;
        String host = normalizeHost(targetHost);
        String pat = normalizeHost(pattern);

        if (host.equals(pat)) {
            return true;
        }

        // Safe wildcard rules:
        // 1. Wildcard character '*' must be only in the left-most label (i.e. starts with "*.")
        if (!pat.startsWith("*.")) {
            return false;
        }

        // 2. Wildcards never match IP addresses.
        if (isIpAddress(host)) {
            return false;
        }

        String suffix = pat.substring(1); // e.g. ".example.com"
        if (!host.endsWith(suffix)) {
            return false;
        }

        // 3. Suffix must contain at least two domain components (e.g. "*.com" is disallowed).
        String domainPart = suffix.substring(1); // "example.com"
        if (!domainPart.contains(".")) {
            return false;
        }

        // 4. Wildcard matches exactly one domain component (cannot match multiple levels, nor empty label).
        String prefix = host.substring(0, host.length() - suffix.length());
        if (prefix.isEmpty() || prefix.contains(".")) {
            return false;
        }

        return true;
    }

    /**
     * Compares two IP addresses without performing reverse DNS resolution.
     */
    public static boolean matchIpAddress(String ip1, String ip2) {
        if (ip1 == null || ip2 == null) return false;
        String s1 = normalizeHost(ip1);
        String s2 = normalizeHost(ip2);

        if (s1.equalsIgnoreCase(s2)) {
            return true;
        }

        if (isIpv4Address(s1) && isIpv4Address(s2)) {
            byte[] b1 = parseIpv4Bytes(s1);
            byte[] b2 = parseIpv4Bytes(s2);
            return b1 != null && b2 != null && Arrays.equals(b1, b2);
        }

        if (isIpv6Address(s1) && isIpv6Address(s2)) {
            byte[] b1 = parseIpv6Bytes(s1);
            byte[] b2 = parseIpv6Bytes(s2);
            return b1 != null && b2 != null && Arrays.equals(b1, b2);
        }

        return false;
    }

    public static boolean isIpAddress(String host) {
        return isIpv4Address(host) || isIpv6Address(host);
    }

    public static boolean isIpv4Address(String host) {
        if (host == null) return false;
        String s = normalizeHost(host);
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            int val = Integer.parseInt(p);
            if (val < 0 || val > 255) return false;
            if (p.length() > 1 && p.startsWith("0")) return false; // disallow leading zeros
        }
        return true;
    }

    public static boolean isIpv6Address(String host) {
        if (host == null) return false;
        String s = normalizeHost(host);
        if (!s.contains(":")) return false;
        int colonCount = 0;
        boolean doubleColon = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':') {
                colonCount++;
                if (i > 0 && s.charAt(i - 1) == ':') {
                    if (doubleColon) return false; // at most one "::"
                    doubleColon = true;
                }
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.')) {
                return false;
            }
        }
        return colonCount >= 2;
    }

    public static byte[] parseIpv4Bytes(String ip) {
        if (!isIpv4Address(ip)) return null;
        String[] parts = normalizeHost(ip).split("\\.", -1);
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return bytes;
    }

    public static byte[] parseIpv6Bytes(String ip) {
        if (ip == null) return null;
        String s = normalizeHost(ip);
        if (!isIpv6Address(s)) return null;

        String[] doubleColonParts = s.split("::", -1);
        if (doubleColonParts.length > 2) return null;

        int[] words = new int[8];
        if (doubleColonParts.length == 1) {
            String[] parts = s.split(":", -1);
            if (parts.length != 8) return null;
            for (int i = 0; i < 8; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 4) return null;
                try {
                    words[i] = Integer.parseInt(parts[i], 16);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        } else {
            String left = doubleColonParts[0];
            String right = doubleColonParts[1];
            String[] leftParts = left.isEmpty() ? new String[0] : left.split(":", -1);
            String[] rightParts = right.isEmpty() ? new String[0] : right.split(":", -1);
            int totalParts = leftParts.length + rightParts.length;
            if (totalParts >= 8) return null;

            for (int i = 0; i < leftParts.length; i++) {
                if (leftParts[i].isEmpty() || leftParts[i].length() > 4) return null;
                try {
                    words[i] = Integer.parseInt(leftParts[i], 16);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            int rightOffset = 8 - rightParts.length;
            for (int i = 0; i < rightParts.length; i++) {
                if (rightParts[i].isEmpty() || rightParts[i].length() > 4) return null;
                try {
                    words[rightOffset + i] = Integer.parseInt(rightParts[i], 16);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i * 2] = (byte) ((words[i] >> 8) & 0xFF);
            bytes[i * 2 + 1] = (byte) (words[i] & 0xFF);
        }
        return bytes;
    }

    public static String extractCommonName(X509Certificate cert) {
        if (cert == null) return null;
        Principal principal = cert.getSubjectDN();
        if (principal == null) return null;
        String dn = principal.getName();
        for (String part : dn.split("(?<!\\\\),")) {
            part = part.trim();
            if (part.regionMatches(true, 0, "CN=", 0, 3)) {
                String val = part.substring(3).trim();
                if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                    val = val.substring(1, val.length() - 1);
                }
                return val;
            }
        }
        return null;
    }

    public static String normalizeHost(String s) {
        if (s == null) return "";
        String trimmed = s.trim().toLowerCase(Locale.US);
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String getExpectedHost() {
        return expectedHost;
    }

    public String getExpectedFingerprint() {
        return expectedFingerprint;
    }
}
