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
package org.apache.ibatis.mapping;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.builder.InitializingObject;
import org.apache.ibatis.cache.decorators.BlockingCache;
import org.apache.ibatis.cache.decorators.LoggingCache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.decorators.ScheduledCache;
import org.apache.ibatis.cache.decorators.SerializedCache;
import org.apache.ibatis.cache.decorators.SynchronizedCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * @author Clinton Begin
 */
public class CacheBuilder {

  //实际上是所在的mapper映射文件的命名空间名称
  private final String id;

  //具体的缓存实现类
  private Class<? extends Cache> implementation;

  //缓存装饰器
  private final List<Class<? extends Cache>> decorators;

  //缓存大小
  private Integer size;

  //刷新缓存的时间间隔
  private Long clearInterval;

  private boolean readWrite;

  //缓存的一些其他属性
  private Properties properties;

  private boolean blocking;

  public CacheBuilder(String id) {
    this.id = id;
    this.decorators = new ArrayList<Class<? extends Cache>>();
  }

  public CacheBuilder implementation(Class<? extends Cache> implementation) {
    this.implementation = implementation;
    return this;
  }

  public CacheBuilder addDecorator(Class<? extends Cache> decorator) {
    if (decorator != null) {
      this.decorators.add(decorator);
    }
    return this;
  }

  public CacheBuilder size(Integer size) {
    this.size = size;
    return this;
  }

  public CacheBuilder clearInterval(Long clearInterval) {
    this.clearInterval = clearInterval;
    return this;
  }

  public CacheBuilder readWrite(boolean readWrite) {
    this.readWrite = readWrite;
    return this;
  }

  public CacheBuilder blocking(boolean blocking) {
    this.blocking = blocking;
    return this;
  }
  
  public CacheBuilder properties(Properties properties) {
    this.properties = properties;
    return this;
  }

  /**
   * 构建Cache对象
   * @return
   */
  public Cache build() {
    //1.设置默认的缓存类型（PerpetualCache）和缓存装饰器（LruCache）
    setDefaultImplementations();
    //通过反射创建缓存实例
    Cache cache = newBaseCacheInstance(implementation, id);
    //设置缓存相关属性
    setCacheProperties(cache);
    // issue #352, do not apply decorators to custom caches
    //仅对内置缓存 PerpetualCache 应用装饰器
    if (PerpetualCache.class.equals(cache.getClass())) {
      //2.应用装饰器到 PerpetualCache 对象上
      //遍历装饰器集合，应用装饰器
      for (Class<? extends Cache> decorator : decorators) {
        //通过反射创建装饰器实例
        cache = newCacheDecoratorInstance(decorator, cache);
        //设置缓存到装饰器实例中
        setCacheProperties(cache);
      }
      //3.应用标准的装饰器，比如LoggingCache 、SynchronizedCache
      cache = setStandardDecorators(cache);
    } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
      //4.对非 LoggingCache 类型的缓存应用 LoggingCache 装饰器
      //应用具有日志功能的缓存装饰器
      cache = new LoggingCache(cache);
    }
    return cache;
  }

  //设置默认的缓存类型（PerpetualCache）和缓存装饰器（LruCache）
  private void setDefaultImplementations() {
    //如果没有配置缓存实现类
    if (implementation == null) {
      //设置默认的缓存实现类
      implementation = PerpetualCache.class;
      //如果没有缓存装饰器
      if (decorators.isEmpty()) {
        //添加默认的Lru缓存装饰器
        decorators.add(LruCache.class);
      }
    }
  }

  //应用标准的装饰器，比如LoggingCache 、SynchronizedCache
  private Cache setStandardDecorators(Cache cache) {
    try {
      //创建“元信息”对象
      MetaObject metaCache = SystemMetaObject.forObject(cache);

      if (size != null && metaCache.hasSetter("size")) {
        //设置size属性
        metaCache.setValue("size", size);
      }

      if (clearInterval != null) {
        // clearInterval 不为空，应用 ScheduledCache 装饰器
        cache = new ScheduledCache(cache);
        ((ScheduledCache) cache).setClearInterval(clearInterval);
      }

      if (readWrite) {
        // readWrite 为 true，应用 SerializedCache 装饰器
        cache = new SerializedCache(cache);
      }

      /*
       * 应用 LoggingCache，SynchronizedCache 装饰器，
       * 使原缓存具备打印日志和线程同步的能力
       * 除了这两个装饰器是必备的，剩下的取决于配置
       */
      cache = new LoggingCache(cache);
      cache = new SynchronizedCache(cache);

      if (blocking) {
        // blocking 为 true，应用 BlockingCache 装饰器
        cache = new BlockingCache(cache);
      }

      return cache;
    } catch (Exception e) {
      throw new CacheException("Error building standard cache decorators.  Cause: " + e, e);
    }
  }

  //设置缓存相关属性
  private void setCacheProperties(Cache cache) {
    //如果配置了属性
    if (properties != null) {
      //为缓存实例生成一个“元信息”实例，forObject方法调用层次比加深
      //但是最终调用了MetaClass的forClass方法
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      //循环遍历每一个属性配置
      for (Map.Entry<Object, Object> entry : properties.entrySet()) {
        //获取属性名
        String name = (String) entry.getKey();
        //获取属性值
        String value = (String) entry.getValue();
        if (metaCache.hasSetter(name)) {
          //获取setter方法的参数类型
          Class<?> type = metaCache.getSetterType(name);

          /*
           * 根据参数类型对属性值进行转换，并将转换后的值，通过setter方法，设置到cache中
           * 包含了两个步骤：
           * 1.类型转换
           * 2.将转换后的值，通过setter方法设置在缓存实例中
           */
          if (String.class == type) {
            metaCache.setValue(name, value);
          } else if (int.class == type
              || Integer.class == type) {
            metaCache.setValue(name, Integer.valueOf(value));
          } else if (long.class == type
              || Long.class == type) {
            metaCache.setValue(name, Long.valueOf(value));
          } else if (short.class == type
              || Short.class == type) {
            metaCache.setValue(name, Short.valueOf(value));
          } else if (byte.class == type
              || Byte.class == type) {
            metaCache.setValue(name, Byte.valueOf(value));
          } else if (float.class == type
              || Float.class == type) {
            metaCache.setValue(name, Float.valueOf(value));
          } else if (boolean.class == type
              || Boolean.class == type) {
            metaCache.setValue(name, Boolean.valueOf(value));
          } else if (double.class == type
              || Double.class == type) {
            metaCache.setValue(name, Double.valueOf(value));
          } else {
            throw new CacheException("Unsupported property type for cache: '" + name + "' of type " + type);
          }
        }
      }
    }

    //如果缓存实现了InitializingObject接口，则调用initialize方法执行初始化逻辑
    if (InitializingObject.class.isAssignableFrom(cache.getClass())){
      try {
        ((InitializingObject) cache).initialize();
      } catch (Exception e) {
        throw new CacheException("Failed cache initialization for '" +
            cache.getId() + "' on '" + cache.getClass().getName() + "'", e);
      }
    }
  }

  //利用反射创建出缓存实例
  private Cache newBaseCacheInstance(Class<? extends Cache> cacheClass, String id) {
    Constructor<? extends Cache> cacheConstructor = getBaseCacheConstructor(cacheClass);
    try {
      return cacheConstructor.newInstance(id);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache implementation (" + cacheClass + "). Cause: " + e, e);
    }
  }

  private Constructor<? extends Cache> getBaseCacheConstructor(Class<? extends Cache> cacheClass) {
    try {
      return cacheClass.getConstructor(String.class);
    } catch (Exception e) {
      throw new CacheException("Invalid base cache implementation (" + cacheClass + ").  " +
          "Base cache implementations must have a constructor that takes a String id as a parameter.  Cause: " + e, e);
    }
  }

  private Cache newCacheDecoratorInstance(Class<? extends Cache> cacheClass, Cache base) {
    Constructor<? extends Cache> cacheConstructor = getCacheDecoratorConstructor(cacheClass);
    try {
      return cacheConstructor.newInstance(base);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache decorator (" + cacheClass + "). Cause: " + e, e);
    }
  }

  private Constructor<? extends Cache> getCacheDecoratorConstructor(Class<? extends Cache> cacheClass) {
    try {
      return cacheClass.getConstructor(Cache.class);
    } catch (Exception e) {
      throw new CacheException("Invalid cache decorator (" + cacheClass + ").  " +
          "Cache decorators must have a constructor that takes a Cache instance as a parameter.  Cause: " + e, e);
    }
  }
}
