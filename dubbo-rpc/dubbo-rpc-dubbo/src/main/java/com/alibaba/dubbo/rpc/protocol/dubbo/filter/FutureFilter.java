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
package com.alibaba.dubbo.rpc.protocol.dubbo.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.StaticContext;
import com.alibaba.dubbo.rpc.protocol.dubbo.FutureAdapter;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Future;

/**
 * EventFilter
 */
@Activate(group = Constants.CONSUMER)
public class FutureFilter implements Filter {

    protected static final Logger logger = LoggerFactory.getLogger(FutureFilter.class);

    @Override
    public Result invoke(final Invoker<?> invoker, final Invocation invocation) throws RpcException {
        // 是否是异步的调用
        final boolean isAsync = RpcUtils.isAsync(invoker.getUrl(), invocation);

        fireInvokeCallback(invoker, invocation);

        // need to configure if there's return value before the invocation in order to help invoker to judge if it's
        // necessary to return future.
        Result result = invoker.invoke(invocation);
        if (isAsync) {
            // 调用异步处理
            asyncCallback(invoker, invocation);
        } else {
            // 调用同步结果处理
            syncCallback(invoker, invocation, result);
        }
        return result;
    }

    private void syncCallback(final Invoker<?> invoker, final Invocation invocation, final Result result) {
        // 如果有异常
        if (result.hasException()) {
            // 则调用异常的结果处理
            fireThrowCallback(invoker, invocation, result.getException());
        } else {
            // 调用正常的结果处理
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    /**
     * 异步调用回调
     * @param invoker
     * @param invocation
     */
    private void asyncCallback(final Invoker<?> invoker, final Invocation invocation) {
        Future<?> f = RpcContext.getContext().getFuture();
        if (f instanceof FutureAdapter) {
            ResponseFuture future = ((FutureAdapter<?>) f).getFuture();
            // 设置回调
            future.setCallback(new ResponseCallback() {
                @Override
                public void done(Object rpcResult) {
                    // 如果结果为空，则打印错误日志
                    if (rpcResult == null) {
                        logger.error(new IllegalStateException("invalid result value : null, expected " + Result.class.getName()));
                        return;
                    }

                    ///must be rpcResult
                    // 如果不是Result则打印错误日志
                    if (!(rpcResult instanceof Result)) {
                        logger.error(new IllegalStateException("invalid result type :" + rpcResult.getClass() + ", expected " + Result.class.getName()));
                        return;
                    }

                    Result result = (Result) rpcResult;
                    if (result.hasException()) {
                        // 如果有异常，则调用异常处理方法
                        fireThrowCallback(invoker, invocation, result.getException());
                    } else {
                        // 如果正常的返回结果，则调用正常的处理方法
                        fireReturnCallback(invoker, invocation, result.getValue());
                    }
                }

                @Override
                public void caught(Throwable exception) {
                    fireThrowCallback(invoker, invocation, exception);
                }
            });
        }
    }

    /**
     * 调用方法
     * @param invoker
     * @param invocation
     */
    private void fireInvokeCallback(final Invoker<?> invoker, final Invocation invocation) {
        // 获取事件配置信息
        // 获得调用的方法
        final Method onInvokeMethod = (Method) StaticContext.getSystemContext().get(
            StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(),
                Constants.ON_INVOKE_METHOD_KEY));
        // 获得调用的服务
        final Object onInvokeInst = StaticContext.getSystemContext().get(
            StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(),
                Constants.ON_INVOKE_INSTANCE_KEY));

        if (onInvokeMethod == null && onInvokeInst == null) {
            return;
        }
        if (onInvokeMethod == null || onInvokeInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onInvokeMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        // 如果不可以访问，则设置为可访问
        if (!onInvokeMethod.isAccessible()) {
            onInvokeMethod.setAccessible(true);
        }

        // 获得方法参数数组
        Object[] params = invocation.getArguments();
        try {
            // 调用方法，触发oninvoke事件
            onInvokeMethod.invoke(onInvokeInst, params);
        } catch (InvocationTargetException e) {
            // 触发onthrow事件
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            // 触发onthrow事件
            fireThrowCallback(invoker, invocation, e);
        }
    }

    /**
     * 处理回调结果
     * @param invoker
     * @param invocation
     * @param result
     */
    private void fireReturnCallback(final Invoker<?> invoker, final Invocation invocation, final Object result) {
        final Method onReturnMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_RETURN_METHOD_KEY));
        final Object onReturnInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_RETURN_INSTANCE_KEY));

        //not set onreturn callback
        if (onReturnMethod == null && onReturnInst == null) {
            return;
        }

        if (onReturnMethod == null || onReturnInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onReturnMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onReturnMethod.isAccessible()) {
            onReturnMethod.setAccessible(true);
        }

        Object[] args = invocation.getArguments();
        Object[] params;
        // 获得返回结果类型
        Class<?>[] rParaTypes = onReturnMethod.getParameterTypes();
        // 设置参数和返回结果
        if (rParaTypes.length > 1) {
            if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                params = new Object[2];
                params[0] = result;
                params[1] = args;
            } else {
                params = new Object[args.length + 1];
                params[0] = result;
                System.arraycopy(args, 0, params, 1, args.length);
            }
        } else {
            params = new Object[]{result};
        }
        try {
            // 调用方法
            onReturnMethod.invoke(onReturnInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    private void fireThrowCallback(final Invoker<?> invoker, final Invocation invocation, final Throwable exception) {
        final Method onthrowMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_THROW_METHOD_KEY));
        final Object onthrowInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_THROW_INSTANCE_KEY));

        //onthrow callback not configured
        if (onthrowMethod == null && onthrowInst == null) {
            return;
        }
        if (onthrowMethod == null || onthrowInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onthrow callback config , but no such " + (onthrowMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onthrowMethod.isAccessible()) {
            onthrowMethod.setAccessible(true);
        }
        // 获得抛出异常的类型
        Class<?>[] rParaTypes = onthrowMethod.getParameterTypes();
        if (rParaTypes[0].isAssignableFrom(exception.getClass())) {
            try {
                Object[] args = invocation.getArguments();
                Object[] params;

                // 把类型和抛出的异常值放入返回结果
                if (rParaTypes.length > 1) {
                    if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                        params = new Object[2];
                        params[0] = exception;
                        params[1] = args;
                    } else {
                        params = new Object[args.length + 1];
                        params[0] = exception;
                        System.arraycopy(args, 0, params, 1, args.length);
                    }
                } else {
                    params = new Object[]{exception};
                }
                // 调用下一个调用连
                onthrowMethod.invoke(onthrowInst, params);
            } catch (Throwable e) {
                logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), e);
            }
        } else {
            logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), exception);
        }
    }
}
