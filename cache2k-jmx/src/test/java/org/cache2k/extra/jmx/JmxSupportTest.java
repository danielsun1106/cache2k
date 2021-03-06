package org.cache2k.extra.jmx;

/*
 * #%L
 * cache2k JMX support
 * %%
 * Copyright (C) 2000 - 2020 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.CacheManager;
import static org.junit.Assert.*;

import org.cache2k.Weigher;
import org.cache2k.core.CacheMXBeanImpl;
import org.cache2k.core.log.Log;
import org.cache2k.testing.category.FastTests;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Simple test to check that the support and the management object appear
 * and disappear.
 *
 * @author Jens Wilke
 */
@Category(FastTests.class)
public class JmxSupportTest {

  private static final MBeanServerConnection SERVER =  ManagementFactory.getPlatformMBeanServer();

  private ObjectName objectName;

  @Test
  public void testManagerPresent() throws Exception {
    String name = getClass().getName() + ".testManagerPresent";
    CacheManager m = CacheManager.getInstance(name);
    MBeanInfo i = getCacheManagerInfo(name);
    assertEquals(ManagerMXBeanImpl.class.getName(), i.getClassName());
    m.close();
  }

  @Test
  public void emptyCacheManager_healthOkay() throws Exception {
    String name = getClass().getName() + ".emptyCacheManager_healthOkay";
    CacheManager m = CacheManager.getInstance(name);
    MBeanInfo i = getCacheManagerInfo(name);
    assertEquals(ManagerMXBeanImpl.class.getName(), i.getClassName());
    String health = (String) SERVER.getAttribute(getCacheManagerObjectName(name), "HealthStatus");
    assertEquals("ok", health);
    m.close();
  }

  /**
   * Construct three caches with multiple issues and check the health string of the manager JMX.
   */
  @Test
  public void multipleWarnings() throws Exception {
    final String managerName = getClass().getName() + ".multipleWarnings";
    final String cacheNameBadHashing = "cacheWithBadHashing";
    final String cacheNameKeyMutation = "cacheWithKeyMutation";
    final String cacheNameMultipleIssues = "cacheWithMultipleIssues";
    String suppress1 =  "org.cache2k.Cache/" + managerName + ":" + cacheNameKeyMutation;
    String suppress2 =  "org.cache2k.Cache/" + managerName + ":" + cacheNameMultipleIssues;
    Log.registerSuppression(suppress1, new Log.SuppressionCounter());
    Log.registerSuppression(suppress2, new Log.SuppressionCounter());
    CacheManager m = CacheManager.getInstance(managerName);
    Cache cacheWithBadHashing = Cache2kBuilder.of(Object.class, Object.class)
      .manager(m)
      .name(cacheNameBadHashing)
      .eternal(true)
      .build();
    Cache cacheWithKeyMutation = Cache2kBuilder.of(Object.class, Object.class)
      .manager(m)
      .name(cacheNameKeyMutation)
      .eternal(true)
      .entryCapacity(50)
      .build();
    Cache cacheWithMultipleIssues = Cache2kBuilder.of(Object.class, Object.class)
      .manager(m)
      .name(cacheNameMultipleIssues)
      .entryCapacity(50)
      .eternal(true)
      .build();
    for (int i = 0; i < 9; i++) {
      cacheWithBadHashing.put(new KeyForMutation(), 1);
    }
    for (int i = 0; i < 100; i++) {
      cacheWithMultipleIssues.put(new KeyForMutation(), 1);
    }
    for (int i = 0; i < 100; i++) {
      KeyForMutation v = new KeyForMutation();
      cacheWithKeyMutation.put(v, 1);
      cacheWithMultipleIssues.put(v, 1);
      v.value = 1;
    }
    String health =
      (String) SERVER.getAttribute(getCacheManagerObjectName(managerName), "HealthStatus");
    assertEquals(
      "WARNING: [cacheWithKeyMutation] key mutation detected; " +
      "WARNING: [cacheWithMultipleIssues] key mutation detected", health);
    Log.deregisterSuppression(suppress1);
    Log.deregisterSuppression(suppress2);
    m.close();
  }

  private static class KeyForMutation {
    int value = 0;
    public int hashCode() { return value; }
  }

  static MBeanInfo getCacheManagerInfo(String name) throws Exception {
    ObjectName on = getCacheManagerObjectName(name);
    return SERVER.getMBeanInfo(on);
  }

  private static ObjectName getCacheManagerObjectName(String name)
    throws MalformedObjectNameException {
    if (needsQuoting(name)) {
      return new ObjectName("org.cache2k:type=CacheManager,name=\"" + name + "\"");
    } else {
      return new ObjectName("org.cache2k:type=CacheManager,name=" + name);
    }
  }

  private static boolean needsQuoting(String name) {
    return name.contains(",");
  }

  @Test(expected = InstanceNotFoundException.class)
  public void testManagerDestroyed() throws Exception {
    String name = getClass().getName() + ".testManagerDestroyed";
    CacheManager m = CacheManager.getInstance(name);
    MBeanInfo i = getCacheManagerInfo(name);
    assertEquals(ManagerMXBeanImpl.class.getName(), i.getClassName());
    m.close();
    getCacheManagerInfo(name);
  }

  @Test
  public void managerClear_noCache() throws Exception {
    String name = getClass().getName() + ".managerClear_noCache";
    CacheManager m = CacheManager.getInstance(name);
    SERVER.invoke(getCacheManagerObjectName(name), "clear", new Object[0], new String[0]);
    m.close();
  }

  @Test
  public void managerAttributes() throws Exception {
    String name = getClass().getName() + ".managerAttributes";
    CacheManager m = CacheManager.getInstance(name);
    objectName = getCacheManagerObjectName(name);
    checkAttribute("Version", CacheManager.PROVIDER.getVersion());
    checkAttribute("BuildNumber", "not used");
    m.close();
  }

  @Test
  public void testCacheCreated() throws Exception {
    String name = getClass().getName() + ".testCacheCreated";
    Cache c = Cache2kBuilder.of(Object.class, Object.class)
      .name(name)
      .eternal(true)
      .enableJmx(true)
      .build();
    MBeanInfo i = getCacheInfo(name);
    assertNotNull(i);
    c.close();
    try {
      i = getCacheInfo(name);
      fail("exception expected");
    } catch (InstanceNotFoundException expected) { }
  }

  @Test(expected = InstanceNotFoundException.class)
  public void testCacheCreated_notFound() throws Exception {
    String name = getClass().getName() + ".testCacheCreated";
    Cache c = Cache2kBuilder.of(Object.class, Object.class)
      .name(name)
      .eternal(true)
      .build();
    MBeanInfo i = getCacheInfo(name);
    c.close();
  }

  @Test
  public void testInitialProperties() throws Exception {
    Date beforeCreateion = new Date();
    String name = getClass().getName() + ".testInitialProperties";
    Cache c = new Cache2kBuilder<Long, List<Collection<Long>>>() { }
      .name(name)
      .eternal(true)
      .enableJmx(true)
      .build();
    objectName = constructCacheObjectName(name);
    checkAttribute("KeyType", "Long");
    checkAttribute("ValueType", "java.util.List<java.util.Collection<Long>>");
    checkAttribute("Size", 0L);
    checkAttribute("EntryCapacity", 2000L);
    checkAttribute("MaximumWeight", -1L);
    checkAttribute("TotalWeight", 0L);
    checkAttribute("InsertCount", 0L);
    checkAttribute("MissCount", 0L);
    checkAttribute("RefreshCount", 0L);
    checkAttribute("RefreshFailedCount", 0L);
    checkAttribute("RefreshedHitCount", 0L);
    checkAttribute("ExpiredCount", 0L);
    checkAttribute("EvictedCount", 0L);
    checkAttribute("PutCount", 0L);
    checkAttribute("RemoveCount", 0L);
    checkAttribute("ClearedEntriesCount", 0L);
    checkAttribute("ClearCount", 0L);
    checkAttribute("KeyMutationCount", 0L);
    checkAttribute("LoadExceptionCount", 0L);
    checkAttribute("SuppressedLoadExceptionCount", 0L);
    checkAttribute("HitRate", 0.0);
    checkAttribute("HashQuality", -1);
    checkAttribute("MillisPerLoad", 0.0);
    checkAttribute("TotalLoadMillis", 0L);
    checkAttribute("Implementation", "HeapCache");
    checkAttribute("ClearedTime", null);
    checkAttribute("Alert", 0);
    assertTrue("reasonable CreatedTime",
      ((Date) retrieve("CreatedTime")).compareTo(beforeCreateion) >= 0);
    assertTrue("reasonable InfoCreatedTime",
      ((Date) retrieve("InfoCreatedTime")).compareTo(beforeCreateion) >= 0);
    assertTrue("reasonable InfoCreatedDeltaMillis",
      ((Integer) retrieve("InfoCreatedDeltaMillis")) >= 0);
    assertTrue("reasonable EvictionStatistics",
      retrieve("EvictionStatistics").toString().contains("impl="));
    assertTrue("reasonable IntegrityDescriptor",
      retrieve("IntegrityDescriptor").toString().startsWith("0."));
    c.close();
  }

  @Test
  public void testWeigherWithSegmentation() throws Exception {
    String name = getClass().getName() + ".testInitialProperties";
    Cache c = new Cache2kBuilder<Long, List<Collection<Long>>>() { }
      .name(name)
      .eternal(true)
      .enableJmx(true)
      .maximumWeight(123456789L)
      .weigher(new Weigher<Long, List<Collection<Long>>>() {
        @Override
        public int weigh(Long key, List<Collection<Long>> value) {
          return 1;
        }
      })
      .build();
    objectName = constructCacheObjectName(name);
    checkAttribute("KeyType", "Long");
    checkAttribute("ValueType", "java.util.List<java.util.Collection<Long>>");
    checkAttribute("Size", 0L);
    checkAttribute("EntryCapacity", -1L);
    long v = (Long) retrieve("MaximumWeight");
    assertTrue(v >= 123456789L);
    checkAttribute("TotalWeight", 0L);
    checkAttribute("EvictedWeight", 0L);
    c.close();
  }

  @Test
  public void testDisabled() throws Exception {
    String name = getClass().getName() + ".testInitialProperties";
    Cache c = new Cache2kBuilder<Long, List<Collection<Long>>>() { }
      .name(name)
      .disableStatistics(true)
      .eternal(true)
      .build();
    objectName = constructCacheObjectName(name);
    try {
      retrieve("Alert");
      fail("exception expected");
    } catch (InstanceNotFoundException ex) {
    }
    c.close();
  }

  private void checkAttribute(String name, Object expected) throws Exception {
    Object v = retrieve(name);
    assertEquals("Value of attribute '" + name + "'", expected, v);
  }

  private Object retrieve(String name)
    throws MBeanException, AttributeNotFoundException, InstanceNotFoundException,
    ReflectionException, IOException {
    return SERVER.getAttribute(objectName, name);
  }

  static MBeanInfo getCacheInfo(String name) throws Exception {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName on = constructCacheObjectName(name);
    return mbs.getMBeanInfo(on);
  }

  static ObjectName constructCacheObjectName(String name) throws MalformedObjectNameException {
    if (needsQuoting(name)) {
      return new ObjectName("org.cache2k:type=Cache,manager=default,name=\"" + name + "\"");
    } else {
      return new ObjectName("org.cache2k:type=Cache,manager=default,name=" + name);
    }
  }

  @Test(expected = InstanceNotFoundException.class)
  public void testCacheDestroyed() throws Exception {
    String name = getClass().getName() + ".testCacheDestroyed";
    Cache c = Cache2kBuilder.of(Object.class, Object.class)
      .name(name)
      .eternal(true)
      .build();
    MBeanInfo i = getCacheInfo(name);
    assertEquals(CacheMXBeanImpl.class.getName(), i.getClassName());
    c.close();
    getCacheInfo(name);
  }

}
