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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DubboInvoker
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {

    /**
     * 信息交换客户端数组
     */
    private final ExchangeClient[] clients;

    /**
     * 客户端数组位置
     */
    private final AtomicPositiveInteger index = new AtomicPositiveInteger();

    /**
     * 版本号
     */
    private final String version;

    /**
     * 销毁锁
     */
    private final ReentrantLock destroyLock = new ReentrantLock();

    /**
     * Invoker对象集合
     */
    private final Set<Invoker<?>> invokers;

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients) {
        this(serviceType, url, clients, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(serviceType, url, new String[]{Constants.INTERFACE_KEY, Constants.GROUP_KEY, Constants.TOKEN_KEY, Constants.TIMEOUT_KEY});
        this.clients = clients;
        // get version.
        // 从url配置中获得版本配置，默认0.0.0
        this.version = url.getParameter(Constants.VERSION_KEY, "0.0.0");
        this.invokers = invokers;
    }

    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {
        // rpc会话域
        RpcInvocation inv = (RpcInvocation) invocation;
        // 获得方法名
        final String methodName = RpcUtils.getMethodName(invocation);
        // 把path放入到附加值中
        inv.setAttachment(Constants.PATH_KEY, getUrl().getPath());
        // 把版本号放入到附加值
        inv.setAttachment(Constants.VERSION_KEY, version);

        // 当前的客户端
        ExchangeClient currentClient;
        // 如果数组内就一个客户端，则直接取出
        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            // 取模轮询 从数组中取，当取到最后一个时，从头开始
            currentClient = clients[index.getAndIncrement() % clients.length];
        }
        try {
            // 读取async配置，是否启用异步
            boolean isAsync = RpcUtils.isAsync(getUrl(), invocation);
            // 是否是单向发送
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
            // 获得超时时间
            int timeout = getUrl().getMethodParameter(methodName, Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);

            if (isOneway) { // 单项发送
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
                currentClient.send(inv, isSent);
                // 不等待服务端应答，没有返回值，future为null
                RpcContext.getContext().setFuture(null);
                return new RpcResult();
            } else if (isAsync) { // 异步调用
                ResponseFuture future = currentClient.request(inv, timeout);
                // 保存future
                RpcContext.getContext().setFuture(new FutureAdapter<Object>(future));
                return new RpcResult();
            } else { // 同步调用，等待返回结果
                // future为null
                RpcContext.getContext().setFuture(null);
                return (Result) currentClient.request(inv, timeout).get();
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable())
            return false;
        for (ExchangeClient client : clients) {
            // 只要有一个客户端连接并且不是只读，则表示存活
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        if (super.isDestroyed()) {
            return;
        } else {
            // double check to avoid dup close
            // 获得销毁锁
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }
                // 销毁
                super.destroy();
                // 从集合中移除
                if (invokers != null) {
                    invokers.remove(this);
                }
                for (ExchangeClient client : clients) {
                    try {
                        // 关闭每一个客户端
                        client.close(ConfigUtils.getServerShutdownTimeout());
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }

            } finally {
                // 释放锁
                destroyLock.unlock();
            }
        }
    }
}
