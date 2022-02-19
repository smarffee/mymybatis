/**
 *    Copyright 2009-2022 the original author or authors.
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * Simple blocking decorator 
 * 
 * Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 *
 * 当指定 key 对应元素不存在于缓存中时，BlockingCache 会根据 lock 进行加锁。
 * 此时，其他线程将会进入等待状态，直到与 key 对应的元素被填充到缓存中，而不是让所有线程都去访问数据库。
 * 
 * @author Eduardo Macarron
 *
 * 具备阻塞功能的缓存
 *
 */
public class BlockingCache implements Cache {

  private long timeout;
  private final Cache delegate;
  private final ConcurrentHashMap<Object, ReentrantLock> locks;

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<Object, ReentrantLock>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  /**
   * 会在 TransactionalCache 的 {@link TransactionalCache#flushPendingEntries()} 方法中被调用，
   *
   * @param key Can be any object but usually it is a {@link CacheKey}
   * @param value The result of a select.
   */
  @Override
  public void putObject(Object key, Object value) {
    try {
      // 存储缓存项
      delegate.putObject(key, value);
    } finally {
      // 释放锁
      releaseLock(key);
    }
  }

  /**
   * 在查询缓存时，getObject 方法会先获取与 key 对应的锁，并加锁。
   * 若缓存命中，getObject 方法会释放锁，否则将一直锁定。
   * getObject 方法若返回 null，表示缓存未命中。
   * 此时 MyBatis 会向数据库发起查询请求，并调用 putObject 方法存储查询结果。
   * 此时，putObject 方法会将指定 key 对应的锁进行解锁，这样被阻塞的线程即可恢复运行。
   *
   * 同一时刻仅允许一个线程通过 getObject 方法查询指定 key 对应的缓存项。
   * 如果缓存未命中，getObject 方法不会释放锁，导致其他线程被阻塞住。
   * 其他线程要想恢复运行，必须进行解锁，解锁逻辑由 putObject 和 removeObject 方法执行。
   *
   * @param key The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    // 请求锁
    acquireLock(key);
    Object value = delegate.getObject(key);
    // 若缓存命中，则释放锁。需要注意的是，未命中则不释放锁
    if (value != null) {
      // 释放锁
      releaseLock(key);
    }        
    return value;
  }

  /**
   * 由 TransactionalCache 的 {@link TransactionalCache#unlockMissedEntries()} 方法调用。
   *
   * @param key The key
   * @return
   */
  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    // 释放锁
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }
  
  private ReentrantLock getLockForKey(Object key) {
    ReentrantLock lock = new ReentrantLock();
    // 存储 <key, Lock> 键值对到 locks 中
    ReentrantLock previous = locks.putIfAbsent(key, lock);
    return previous == null ? lock : previous;
  }
  
  private void acquireLock(Object key) {
    Lock lock = getLockForKey(key);
    if (timeout > 0) {
      try {
        // 尝试加锁
        boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
        if (!acquired) {
          throw new CacheException("Couldn't get a lock in " + timeout + " for the key " +  key + " at the cache " + delegate.getId());  
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    } else {
      // 加锁
      lock.lock();
    }
  }
  
  private void releaseLock(Object key) {
    // 获取与当前 key 对应的锁
    ReentrantLock lock = locks.get(key);
    if (lock.isHeldByCurrentThread()) {
      // 释放锁
      lock.unlock();
    }
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }  
}