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
package org.apache.ibatis.cache;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.reflection.ArrayUtil;

/**
 * @author Clinton Begin
 *
 * MyBatis 引入了缓存，那么大家思考过缓存中的 key 和 value 的值分别是什么吗？
 * 大家可能很容易能回答出 value 的内容，不就是 SQL 的查询结果吗。那 key 是什么呢？是字符串，还是其他什么对象？
 * 如果是字符串的话，那么大家首先能想到的是用 SQL 语句作为 key。但这是不对的，比如：
 *
 * SELECT * FROM author where id > ?
 *
 * id > 1 和 id > 10 查出来的结果可能是不同的，所以我们不能简单的使用 SQL 语句作为 key。
 * 从这里可以看出来，运行时参数将会影响查询结果，因此我们的 key 应该涵盖运行时参数。
 * 除此之外呢，如果进行分页查询，查询结果也会不同，因此 key 也应该涵盖分页参数。
 * 综上，我们不能使用简单的 SQL 语句作为 key。应该考虑使用一种复合对象，能涵盖可影响查询结果的因子。
 * 在 MyBatis 中，这种复合对象就是 CacheKey。
 *
 * 在计算 CacheKey 的过程中，有很多影响因子参与了计算。
 * 比如 MappedStatement 的 id 字段，SQL 语句，分页参数，运行时变量，Environment 的 id 字段等。
 * 通过让这些影响因子参与计算，可以很好的区分不同查询请求。
 * 所以，我们可以简单的把 CacheKey 看做是一个查询请求的 id。
 */
public class CacheKey implements Cloneable, Serializable {

  private static final long serialVersionUID = 1146682552656046210L;

  public static final CacheKey NULL_CACHE_KEY = new NullCacheKey();

  private static final int DEFAULT_MULTIPLYER = 37;
  private static final int DEFAULT_HASHCODE = 17;


  /******** 除了 multiplier 是恒定不变的 ，其他变量将在更新操作中被修改 ********/
  // 乘子，默认为 37，是恒定不变的
  private final int multiplier;
  // CacheKey 的 hashCode，综合了各种影响因子
  private int hashcode;
  // 校验和
  private long checksum;
  // 影响因子个数
  private int count;
  // 8/21/2017 - Sonarlint flags this as needing to be marked transient.  While true if content is not serializable, this is not always true and thus should not be marked transient.
  // 影响因子集合
  private List<Object> updateList;

  public CacheKey() {
    this.hashcode = DEFAULT_HASHCODE;
    this.multiplier = DEFAULT_MULTIPLYER;
    this.count = 0;
    this.updateList = new ArrayList<Object>();
  }

  public CacheKey(Object[] objects) {
    this();
    updateAll(objects);
  }

  public int getUpdateCount() {
    return updateList.size();
  }

  /**
   * 每当执行更新操作时，表示有新的影响因子参与计算。
   * 当不断有新的影响因子参与计算时，hashcode 和 checksum 将会变得愈发复杂和随机。
   * 这样可降低冲突率，使 CacheKey 可在缓存中更均匀的分布。
   *
   *
   * @param object
   */
  public void update(Object object) {
    int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object);

    // 自增 count
    count++;

    // 计算校验和
    checksum += baseHashCode;
    // 更新 baseHashCode
    baseHashCode *= count;

    // 计算 hashCode
    hashcode = multiplier * hashcode + baseHashCode;

    // 保存影响因子
    updateList.add(object);
  }

  public void updateAll(Object[] objects) {
    for (Object o : objects) {
      update(o);
    }
  }

  /**
   * CacheKey 最终要作为键存入 HashMap，因此它需要覆盖 equals 和 hashCode 方法。
   *
   * @param object
   * @return
   */
  @Override
  public boolean equals(Object object) {
    // 检测是否为同一个对象
    if (this == object) {
      return true;
    }
    // 检测 object 是否为 CacheKey
    if (!(object instanceof CacheKey)) {
      return false;
    }

    final CacheKey cacheKey = (CacheKey) object;

    // 检测 hashCode 是否相等
    if (hashcode != cacheKey.hashcode) {
      return false;
    }
    // 检测校验和是否相同
    if (checksum != cacheKey.checksum) {
      return false;
    }
    // 检测 count 是否相同
    if (count != cacheKey.count) {
      return false;
    }

    // 如果上面的检测都通过了，下面分别对每个影响因子进行比较
    for (int i = 0; i < updateList.size(); i++) {
      Object thisObject = updateList.get(i);
      Object thatObject = cacheKey.updateList.get(i);
      if (!ArrayUtil.equals(thisObject, thatObject)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    // 返回 hashcode 变量
    return hashcode;
  }

  @Override
  public String toString() {
    StringBuilder returnValue = new StringBuilder().append(hashcode).append(':').append(checksum);
    for (Object object : updateList) {
      returnValue.append(':').append(ArrayUtil.toString(object));
    }
    return returnValue.toString();
  }

  @Override
  public CacheKey clone() throws CloneNotSupportedException {
    CacheKey clonedCacheKey = (CacheKey) super.clone();
    clonedCacheKey.updateList = new ArrayList<Object>(updateList);
    return clonedCacheKey;
  }

}
