// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Tests for RefreshPreferenceStore
package com.homelabpocket.scheduler;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RefreshPreferenceStoreTest {

    private Map<String, Object> backingMap;
    private SharedPreferences fakePrefs;
    private RefreshPreferenceStore store;

    @Before
    public void setUp() {
        backingMap = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                RefreshPreferenceStoreTest.class.getClassLoader(),
                new Class[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("putInt".equals(name)) {
                        backingMap.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if ("commit".equals(name)) {
                        return true;
                    }
                    if ("apply".equals(name)) {
                        return null;
                    }
                    if ("clear".equals(name)) {
                        backingMap.clear();
                        return proxy;
                    }
                    return proxy;
                }
        );

        fakePrefs = (SharedPreferences) Proxy.newProxyInstance(
                RefreshPreferenceStoreTest.class.getClassLoader(),
                new Class[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("edit".equals(name)) {
                        return editor;
                    }
                    if ("getInt".equals(name)) {
                        Object val = backingMap.get(args[0]);
                        return val instanceof Integer ? (Integer) val : (Integer) args[1];
                    }
                    if ("contains".equals(name)) {
                        return backingMap.containsKey(args[0]);
                    }
                    return null;
                }
        );

        store = new RefreshPreferenceStore(fakePrefs);
    }

    @Test
    public void testDefaultIntervalIs10Seconds() {
        assertEquals("Default auto-refresh interval must be 10 seconds",
                RefreshPreferenceStore.DEFAULT_INTERVAL_SECONDS, store.getRefreshIntervalSeconds());
        assertEquals("Default interval in milliseconds must be 10,000",
                10_000L, store.getRefreshIntervalMillis());
        assertTrue("Auto-refresh should be enabled by default", store.isAutoRefreshEnabled());
    }

    @Test
    public void testSupportedIntervalValidation() {
        assertTrue("Off (0) must be valid", RefreshPreferenceStore.isValidInterval(RefreshPreferenceStore.INTERVAL_OFF));
        assertTrue("5s must be valid", RefreshPreferenceStore.isValidInterval(RefreshPreferenceStore.INTERVAL_5S));
        assertTrue("10s must be valid", RefreshPreferenceStore.isValidInterval(RefreshPreferenceStore.INTERVAL_10S));
        assertTrue("30s must be valid", RefreshPreferenceStore.isValidInterval(RefreshPreferenceStore.INTERVAL_30S));
        assertTrue("60s must be valid", RefreshPreferenceStore.isValidInterval(RefreshPreferenceStore.INTERVAL_60S));

        assertFalse("Negative interval must be invalid", RefreshPreferenceStore.isValidInterval(-1));
        assertFalse("1s must be invalid", RefreshPreferenceStore.isValidInterval(1));
        assertFalse("15s must be invalid", RefreshPreferenceStore.isValidInterval(15));
        assertFalse("120s must be invalid", RefreshPreferenceStore.isValidInterval(120));
    }

    @Test
    public void testPersistingValidIntervals() {
        assertTrue("Setting 5s must succeed", store.setRefreshIntervalSeconds(5));
        assertEquals(5, store.getRefreshIntervalSeconds());
        assertEquals(5000L, store.getRefreshIntervalMillis());
        assertTrue(store.isAutoRefreshEnabled());

        assertTrue("Setting 30s must succeed", store.setRefreshIntervalSeconds(30));
        assertEquals(30, store.getRefreshIntervalSeconds());

        assertTrue("Setting 60s must succeed", store.setRefreshIntervalSeconds(60));
        assertEquals(60, store.getRefreshIntervalSeconds());

        assertTrue("Setting Off (0) must succeed", store.setRefreshIntervalSeconds(0));
        assertEquals(0, store.getRefreshIntervalSeconds());
        assertEquals(0L, store.getRefreshIntervalMillis());
        assertFalse("Auto-refresh must be reported disabled when interval is 0", store.isAutoRefreshEnabled());
    }

    @Test
    public void testRejectingInvalidIntervals() {
        store.setRefreshIntervalSeconds(10);

        assertFalse("Setting 15s must be rejected", store.setRefreshIntervalSeconds(15));
        assertEquals("Interval must remain 10 after rejected update", 10, store.getRefreshIntervalSeconds());

        assertFalse("Setting negative interval must be rejected", store.setRefreshIntervalSeconds(-10));
        assertEquals(10, store.getRefreshIntervalSeconds());
    }

    @Test
    public void testListenerNotificationOnChange() {
        AtomicInteger notifiedVal = new AtomicInteger(-1);
        RefreshPreferenceStore.OnPreferenceChangeListener listener = newVal -> notifiedVal.set(newVal);

        store.addListener(listener);

        store.setRefreshIntervalSeconds(30);
        assertEquals(30, notifiedVal.get());

        // Same value does not re-notify
        notifiedVal.set(-1);
        store.setRefreshIntervalSeconds(30);
        assertEquals("Setting same value must not notify listener", -1, notifiedVal.get());

        // Remove listener
        store.removeListener(listener);
        store.setRefreshIntervalSeconds(5);
        assertEquals("Removed listener must not be notified", -1, notifiedVal.get());
        assertEquals(5, store.getRefreshIntervalSeconds());
    }
}
