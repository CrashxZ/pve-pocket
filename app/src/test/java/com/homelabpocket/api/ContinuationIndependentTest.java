package com.homelabpocket.api;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import com.homelabpocket.security.CredentialStore;
import com.homelabpocket.model.*;
public class ContinuationIndependentTest {
 private CredentialStore store(final boolean slowToken) {return new CredentialStore(new android.content.Context(){@Override public android.content.SharedPreferences getSharedPreferences(String n,int m){return null;}}){
 @Override public boolean hasApiToken(String id){if(slowToken)try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();}return slowToken;}
 @Override public String getApiToken(String id){return slowToken?"synthetic-token":null;}
 @Override public boolean hasPassword(String id){return false;}
 };}
 private ServerConfig cfg(){return new ServerConfig("independent","Synthetic","127.0.0.1",1,ServerConfig.ServerType.PDM,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");}
 @Test public void refreshDeadlineIncludesContendedMonitor() throws Exception {
  SessionManager sm=new SessionManager(store(false));ReentrantLock lock=sm.getSessionLockForTesting();CountDownLatch ready=new CountDownLatch(1);
  Thread holder=new Thread(()->{lock.lock();try{ready.countDown();Thread.sleep(400);}catch(InterruptedException e){}finally{lock.unlock();}});holder.start();assertTrue(ready.await(1,TimeUnit.SECONDS));
  long start=System.nanoTime();try{sm.refreshSessionWithDeadline(cfg(),start+40_000_000L);}catch(ApiException expected){}long elapsed=(System.nanoTime()-start)/1_000_000L;holder.join();assertTrue("refresh exceeded 40ms deadline waiting on monitor: "+elapsed+"ms",elapsed<200);
 }
 @Test public void lateTokenMustNotReturnOrCache() throws Exception {
  SessionManager sm=new SessionManager(store(true), null);boolean rejected=false;try{AuthCredentials c=sm.getCredentialsWithDeadline(cfg(),System.nanoTime()+40_000_000L);assertNull("Expired token result must not be returned",c);}catch(ApiException expected){rejected=true;}assertTrue("Late storage must reject expired acquisition",rejected);assertFalse("Late token must not be cached",sm.hasActiveSession("independent"));
 }
}
