/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.serialize.support;

import java.util.LinkedHashSet;
import java.util.Set;

public abstract class SerializableClassRegistry {

    /**
     * 可序列化类类的集合
     */
    private static final Set<Class> registrations = new LinkedHashSet<Class>();

    /**
     * 把可序列化的类加入到集合
     */
    public static void registerClass(Class clazz) {
        registrations.add(clazz);
    }

    /**
     * 获得可序列化的类的集合
     */
    public static Set<Class> getRegisteredClasses() {
        return registrations;
    }
}