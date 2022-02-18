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
package org.apache.ibatis.binding;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.apache.ibatis.lang.UsesJava7;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 * Mapper接口的实际代理对象，
 * 在调用Mapper接口中方式的时候，会被 invoke() 方法拦截，
 * 在invoke() 方法中，实现了对具体SQL的调用
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

  private static final long serialVersionUID = -6424540398559729838L;

  private final SqlSession sqlSession;

  //Mapper接口的Class
  private final Class<T> mapperInterface;

  //MapperMethod 缓存
  //key：Mapper接口中的方法；value：与之对应的MapperMethod
  private final Map<Method, MapperMethod> methodCache;

  public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethod> methodCache) {
    this.sqlSession = sqlSession;
    this.mapperInterface = mapperInterface;
    this.methodCache = methodCache;
  }

  /**
   * 执⾏代理逻辑
   * @param proxy
   * @param method
   * @param args
   * @return
   * @throws Throwable
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      if (Object.class.equals(method.getDeclaringClass())) {

        // 如果方法是定义在 Object 类中的，则直接调用
        return method.invoke(this, args);

      } else if (isDefaultMethod(method)) {
        /*
         * 下面的代码最早出现在 mybatis-3.4.2 版本中，用于支持 JDK 1.8 中的新特性 - 默认方法。
         * Github 上相关的讨论（issue #709），链接如下：
         * https://github.com/mybatis/mybatis-3/issues/709
         */
        return invokeDefaultMethod(proxy, method, args);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }

    // 从缓存中获取 MapperMethod 对象，若缓存未命中，则创建 MapperMethod 对象
    final MapperMethod mapperMethod = cachedMapperMethod(method);

    // 调用 execute 方法执行 SQL
    return mapperMethod.execute(sqlSession, args);
  }

  /**
   * 从缓存中获取 MapperMethod 对象，若缓存未命中，则创建 MapperMethod 对象
   *
   * @param method 正在执行的Mapper接口中method方法
   *
   * @return
   */
  private MapperMethod cachedMapperMethod(Method method) {
    //从缓存中获取
    MapperMethod mapperMethod = methodCache.get(method);

    //如果没有找到，则创建
    if (mapperMethod == null) {
      mapperMethod = new MapperMethod(mapperInterface, method, sqlSession.getConfiguration());
      methodCache.put(method, mapperMethod);
    }

    return mapperMethod;
  }

  @UsesJava7
  private Object invokeDefaultMethod(Object proxy, Method method, Object[] args)
      throws Throwable {
    final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
        .getDeclaredConstructor(Class.class, int.class);
    if (!constructor.isAccessible()) {
      constructor.setAccessible(true);
    }
    final Class<?> declaringClass = method.getDeclaringClass();
    return constructor
        .newInstance(declaringClass,
            MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
                | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC)
        .unreflectSpecial(method, declaringClass).bindTo(proxy).invokeWithArguments(args);
  }

  /**
   * Backport of java.lang.reflect.Method#isDefault()
   */
  private boolean isDefaultMethod(Method method) {
    return (method.getModifiers()
        & (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC)) == Modifier.PUBLIC
        && method.getDeclaringClass().isInterface();
  }
}
