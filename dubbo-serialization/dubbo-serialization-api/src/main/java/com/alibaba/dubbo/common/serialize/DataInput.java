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
package com.alibaba.dubbo.common.serialize;

import java.io.IOException;

public interface DataInput {

    /**
     * 读取布尔类型
     */
    boolean readBool() throws IOException;

    /**
     * 读取字节
     */
    byte readByte() throws IOException;

    /**
     * 读取short类型
     */
    short readShort() throws IOException;

    /**
     * 读取integer类型
     */
    int readInt() throws IOException;

    /**
     * 读取long类型
     */
    long readLong() throws IOException;

    /**
     * 读取float类型
     */
    float readFloat() throws IOException;

    /**
     * 读取double类型
     */
    double readDouble() throws IOException;

    /**
     * 读取UTF-8 string
     */
    String readUTF() throws IOException;

    /**
     * 读取byte数组
     */
    byte[] readBytes() throws IOException;
}