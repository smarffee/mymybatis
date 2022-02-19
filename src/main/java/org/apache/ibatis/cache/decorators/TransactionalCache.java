/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * The 2nd level cache transactional buffer.
 * 
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back. 
 * Blocking cache support has been added. Therefore any get() that returns a cache miss 
 * will be followed by a put() so any lock associated with the key can be released. 
 * 
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  private final Cache delegate;
  private boolean clearOnCommit;

  // 在事务被提交前，所有从数据库中查询的结果将缓存在此集合中
  private final Map<Object, Object> entriesToAddOnCommit;

  /*
   * 在事务被提交前，当缓存未命中时，CacheKey 将会被存储在此集合中
   * 这个集合是用于存储未命中缓存的查询请求所对应的 CacheKey。
   *
   * 单独分析与 entriesMissedInCache 相关的逻辑没什么意义，
   * 要搞清 entriesMissedInCache 的实际用途，需要把它和 BlockingCache 的逻辑结合起来进行分析。
   *
   * 在 BlockingCache，同一时刻仅允许一个线程通过 getObject 方法查询指定 key 对应的缓存项。
   * 如果缓存未命中，getObject 方法不会释放锁，导致其他线程被阻塞住。
   * 其他线程要想恢复运行，必须进行解锁，解锁逻辑由 BlockingCache 的 putObject 和 removeObject 方法执行。
   * 其中 putObject 会在TransactionalCache 的flushPendingEntries方法中被调用，
   * removeObject方法则由 TransactionalCache 的 unlockMissedEntries 方法调用。
   *
   * flushPendingEntries() 和 unlockMissedEntries() 最终都会遍历 entriesMissedInCache 集合，
   * 并将集合元素传给BlockingCache 的相关方法。
   */
  private final Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<Object, Object>();
    this.entriesMissedInCache = new HashSet<Object>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public Object getObject(Object key) {
    // issue #116
    // 查询 delegate 所代表的缓存
    Object object = delegate.getObject(key);
    if (object == null) {
      // 缓存未命中，则将 key 存入到 entriesMissedInCache 中
      entriesMissedInCache.add(key);
    }
    // issue #146
    if (clearOnCommit) {
      return null;
    } else {
      return object;
    }
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  @Override
  public void putObject(Object key, Object object) {
    // 将键值对存入到 entriesToAddOnCommit 中，而非 delegate 缓存中
    entriesToAddOnCommit.put(key, object);
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    clearOnCommit = true;
    // 清空 entriesToAddOnCommit，但不清空 delegate 缓存
    entriesToAddOnCommit.clear();
  }

  public void commit() {
    if (clearOnCommit) {
      // 根据 clearOnCommit 的值决定是否清空 delegate
      delegate.clear();
    }
    // 刷新未缓存的结果到 delegate 缓存中
    flushPendingEntries();
    // 重置 entriesToAddOnCommit 和 entriesMissedInCache
    reset();
  }

  public void rollback() {
    unlockMissedEntries();
    reset();
  }

  private void reset() {
    clearOnCommit = false;
    // 清空集合
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }

  private void flushPendingEntries() {
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      // 将 entriesToAddOnCommit 中的内容转存到 delegate 中
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) {
        // 存入空值
        delegate.putObject(entry, null);
      }
    }
  }

  private void unlockMissedEntries() {
    for (Object entry : entriesMissedInCache) {
      try {
        // 调用 removeObject 进行解锁
        delegate.removeObject(entry);
      } catch (Exception e) {
        log.warn("Unexpected exception while notifiying a rollback to the cache adapter."
            + "Consider upgrading your cache adapter to the latest version.  Cause: " + e);
      }
    }
  }

}
