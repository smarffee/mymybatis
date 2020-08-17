/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.binding;

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 *
 * Mapper接口注册器
 */
public class MapperRegistry {

  private final Configuration config;

  /**
   * 保存mapper接口注册信息
   * key:Mapper接口；value:Mapper接口代理对象工厂类
   *
   * MyBatis 在解析配置文件的<mappers>节点的过程中，
   * 会调用MapperRegistry 的 addMapper 方法{@link MapperRegistry#addMapper(java.lang.Class)}，
   * 将 Class 到 MapperProxyFactory 对象的映射关系存入到knownMappers
   */
  private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<Class<?>, MapperProxyFactory<?>>();

  public MapperRegistry(Configuration config) {
    this.config = config;
  }

  //根据接口Class获取接口的代理类
  @SuppressWarnings("unchecked")
  public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
    // 从 knownMappers 中获取与 type 对应的 MapperProxyFactory
    final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);

    if (mapperProxyFactory == null) {
      throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
    }

    try {
      // 创建代理对象
      // 在获取到 MapperProxyFactory 对象后，即可调用工厂方法为 Mapper 接口生成代理对象了。
      return mapperProxyFactory.newInstance(sqlSession);
    } catch (Exception e) {
      throw new BindingException("Error getting mapper instance. Cause: " + e, e);
    }
  }
  
  public <T> boolean hasMapper(Class<T> type) {
    return knownMappers.containsKey(type);
  }

  /**
   * 注册mapper映射接口
   * @param type
   * @param <T>
   */
  public <T> void addMapper(Class<T> type) {
    if (type.isInterface()) {
      //如果已经注册过，则抛异常
      if (hasMapper(type)) {
        throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
      }

      //是否加载完标识
      boolean loadCompleted = false;

      try {
        // 将 type 和 MapperProxyFactory 进行绑定，
        // MapperProxyFactory 可为 mapper 接口生成代理类
        knownMappers.put(type, new MapperProxyFactory<T>(type));

        // It's important that the type is added before the parser is run
        // otherwise the binding may automatically be attempted by the
        // mapper parser. If the type is already known, it won't try.
        // 创建注解解析器。在 MyBatis 中，有 XML 和 注解两种配置方式可选
        MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
        // 解析注解中的信息
        parser.parse();

        //加载完毕
        loadCompleted = true;

      } finally {
        if (!loadCompleted) {
          knownMappers.remove(type);
        }
      }
    }
  }

  /**
   * @since 3.2.2
   */
  public Collection<Class<?>> getMappers() {
    return Collections.unmodifiableCollection(knownMappers.keySet());
  }

  /**
   * 注册包下的mapper文件
   * @since 3.2.2
   */
  public void addMappers(String packageName, Class<?> superType) {
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<Class<?>>();
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
    for (Class<?> mapperClass : mapperSet) {
      addMapper(mapperClass);
    }
  }

  /**
   * 注册包下的mapper文件
   * @since 3.2.2
   */
  public void addMappers(String packageName) {
    addMappers(packageName, Object.class);
  }
  
}
