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
package com.lin;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class MybatisStudyTest {

    private SqlSessionFactory sqlSessionFactory;

    @Before
    public void before() throws IOException {

        String resource = "mybatis-config.xml";
        InputStream is = Resources.getResourceAsStream(resource);
        this.sqlSessionFactory = new SqlSessionFactoryBuilder().build(is);

        is.close();
    }

    @Test
    public void test1() {
        SqlSession sqlSession = sqlSessionFactory.openSession();

        try {
            UserMapper userMapper = sqlSession.getMapper(UserMapper.class);

            User user = userMapper.selectById(Arrays.asList(new Integer[]{1, 2}), 88);
            System.out.println(user);

        } finally {
            sqlSession.close();
        }
    }


}
