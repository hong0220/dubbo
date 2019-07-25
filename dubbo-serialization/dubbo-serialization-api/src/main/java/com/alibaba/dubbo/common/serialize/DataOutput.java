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

public interface DataOutput {

    /**
     * 输出boolean类型
     */
    void writeBool(boolean v) throws IOException;

    /**
     * 输出byte类型
     */
    void writeByte(byte v) throws IOException;

    /**
     * 输出short类型
     */
    void writeShort(short v) throws IOException;

    /**
     * 输出integer类型
     */
    void writeInt(int v) throws IOException;

    /**
     * 输出long类型
     */
    void writeLong(long v) throws IOException;

    /**
     * 输出float类型
     */
    void writeFloat(float v) throws IOException;

    /**
     * 输出double类型
     */
    void writeDouble(double v) throws IOException;

    /**
     * 输出string类型
     */
    void writeUTF(String v) throws IOException;

    /**
     * 输出byte数组
     */
    void writeBytes(byte[] v) throws IOException;

    /**
     * 输出byte数组中部分数据
     */
    void writeBytes(byte[] v, int off, int len) throws IOException;

    /**
     * 刷新缓冲区
     */
    void flushBuffer() throws IOException;
}