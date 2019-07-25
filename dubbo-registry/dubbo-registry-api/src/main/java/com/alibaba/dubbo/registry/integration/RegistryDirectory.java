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
package com.alibaba.dubbo.registry.integration;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.directory.AbstractDirectory;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.cluster.support.ClusterUtils;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    /**
     * cluster实现类对象
     */
    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * 路由工厂
     */
    private static final RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();

    /**
     * 配置规则工厂
     */
    private static final ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).getAdaptiveExtension();

    /**
     * 服务key
     */
    private final String serviceKey; // Initialization at construction time, assertion not null
    /**
     * 服务类型
     */
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    /**
     * 消费者URL的配置项 Map
     */
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    /**
     * 原始的目录 URL
     */
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    /**
     * 服务方法集合
     */
    private final String[] serviceMethods;
    /**
     * 是否使用多分组
     */
    private final boolean multiGroup;
    /**
     * 协议
     */
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    /**
     * 注册中心
     */
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    /**
     * 是否禁止访问
     */
    private volatile boolean forbidden = false;

    /**
     * 覆盖目录的url
     */
    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    /**
     * override rules Priority: override>-D>consumer>provider Rule one: for a certain provider <ip:port,timeout=100> Rule two: for all providers <* ,timeout=5000> 配置规则数组
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<url, Invoker> cache service url to invoker mapping.
    /**
     * url与服务提供者 Invoker 集合的映射缓存
     */
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<methodName, Invoker> cache service method to invokers mapping.
    /**
     * 方法名和服务提供者 Invoker 集合的映射缓存
     */
    private volatile Map<String, List<Invoker<T>>> methodInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    /**
     * 服务提供者Invoker 集合缓存
     */
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null)
            throw new IllegalArgumentException("service type is null.");
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0)
            throw new IllegalArgumentException("registry serviceKey is null.");
        this.serviceType = serviceType;
        this.serviceKey = url.getServiceKey();
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        this.overrideDirectoryUrl = this.directoryUrl = url.setPath(url.getServiceInterface()).clearParameters().addParameters(queryMap).removeParameter(Constants.MONITOR_KEY);
        String group = directoryUrl.getParameter(Constants.GROUP_KEY, "");
        this.multiGroup = group != null && ("*".equals(group) || group.contains(","));
        String methods = queryMap.get(Constants.METHODS_KEY);
        this.serviceMethods = methods == null ? null : Constants.COMMA_SPLIT_PATTERN.split(methods);
    }

    /**
     * 处理配置规则url集合 Convert override urls to map for use when re-refer. Send all rules every time, the urls will be reassembled and calculated 转换覆盖网址以映射以便在重新引用时使用。 每次发送所有规则，网址将被重新组装和计算
     *
     * @param urls Contract:
     *             </br>1.override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules (all of the providers take effect)
     *             </br>2.override://ip:port...?anyhost=false Special rules (only for a certain provider)
     *             </br>3.override:// rule is not supported... ,needs to be calculated by registry itself.
     *             </br>4.override://0.0.0.0/ without parameters means clearing the override
     * @return
     */
    public static List<Configurator> toConfigurators(List<URL> urls) {
        // 如果为空，则返回空集合
        if (urls == null || urls.isEmpty()) {
            return Collections.emptyList();
        }

        List<Configurator> configurators = new ArrayList<Configurator>(urls.size());
        // 遍历url集合
        for (URL url : urls) {
            //如果是协议是empty的值，则清空配置集合
            if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            // 覆盖的参数集合
            Map<String, String> override = new HashMap<String, String>(url.getParameters());
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            // 覆盖的anyhost参数可以自动添加，也不能改变更改url的判断
            override.remove(Constants.ANYHOST_KEY);
            // 如果需要覆盖添加的值为0，则清空配置
            if (override.size() == 0) {
                configurators.clear();
                continue;
            }
            // 加入配置规则集合
            configurators.add(configuratorFactory.getConfigurator(url));
        }
        // 排序
        Collections.sort(configurators);
        return configurators;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public void subscribe(URL url) {
        setConsumerUrl(url);
        registry.subscribe(url, this);
    }

    /**
     * 销毁
     */
    @Override
    public void destroy() {
        // 如果销毁了，则返回
        if (isDestroyed()) {
            return;
        }
        // unsubscribe.
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                // 取消订阅
                registry.unsubscribe(getConsumerUrl(), this);
            }
        } catch (Throwable t) {
            logger.warn("unexpeced error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            // 清空所有的invoker
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    /**
     * 通知监听器，变化结果
     *
     * @param urls 已注册信息列表，总不为空，含义同{@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}的返回值。
     */
    @Override
    public synchronized void notify(List<URL> urls) {
        List<URL> invokerUrls = new ArrayList<URL>();
        List<URL> routerUrls = new ArrayList<URL>();
        List<URL> configuratorUrls = new ArrayList<URL>();
        // 遍历url
        for (URL url : urls) {
            // 获得协议
            String protocol = url.getProtocol();
            // 获得类别
            String category = url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
            // 如果是路由规则
            if (Constants.ROUTERS_CATEGORY.equals(category)
                    || Constants.ROUTE_PROTOCOL.equals(protocol)) {
                // 则在路由规则集合中加入
                routerUrls.add(url);
            } else if (Constants.CONFIGURATORS_CATEGORY.equals(category)
                    || Constants.OVERRIDE_PROTOCOL.equals(protocol)) {
                // 如果是配置规则，则加入配置规则集合
                configuratorUrls.add(url);
            } else if (Constants.PROVIDERS_CATEGORY.equals(category)) {
                // 如果是服务提供者，则加入服务提供者集合
                invokerUrls.add(url);
            } else {
                logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
            }
        }
        // configurators
        if (configuratorUrls != null && !configuratorUrls.isEmpty()) {
            // 处理配置规则url集合
            this.configurators = toConfigurators(configuratorUrls);
        }
        // routers
        if (routerUrls != null && !routerUrls.isEmpty()) {
            // 处理路由规则 URL 集合
            List<Router> routers = toRouters(routerUrls);
            if (routers != null) { // null - do nothing
                // 并且设置路由集合
                setRouters(routers);
            }
        }
        List<Configurator> localConfigurators = this.configurators; // local reference
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            // 遍历配置规则集合 逐个进行配置
            for (Configurator configurator : localConfigurators) {
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
        // providers
        // 处理服务提供者 URL 集合
        refreshInvoker(invokerUrls);
    }

    /**
     * 处理服务提供者 URL 集合 Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows: 1.If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache, and notice that any parameter changes in the URL will be re-referenced. 2.If
     * the incoming invoker list is not empty, it means that it is the latest invoker list 3.If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route rule, which needs to be re-contrasted to decide whether to re-reference.
     * <p>
     * 根据 invokerURL 列表转换为 invoker 列表。转换规则如下： 1.如果 url 已经被转换为 invoker ，则不在重新引用，直接从缓存中获取，注意如果 url 中任何一个参数变更也会重新引用 2.如果传入的 invoker 列表不为空，则表示最新的 invoker 列表 3.如果传入的 invokerUrl 列表是空，则表示只是下发的 override 规则或 route 规则，需要重新交叉对比，决定是否需要重新引用。
     *
     * @param invokerUrls this parameter can't be null
     */
    // TODO: 2017/8/31 FIXME The thread pool should be used to refresh the address, otherwise the task may be accumulated.
    private void refreshInvoker(List<URL> invokerUrls) {
        if (invokerUrls != null && invokerUrls.size() == 1 && invokerUrls.get(0) != null
                && Constants.EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // 设置禁止访问
            this.forbidden = true; // Forbid to access
            // methodInvokerMap 置空
            this.methodInvokerMap = null; // Set the method invoker map to null
            // 关闭所有的invoker
            destroyAllInvokers(); // Close all invokers
        } else {
            // 关闭禁止访问
            this.forbidden = false; // Allow to access
            // 引用老的 urlInvokerMap
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            // 传入的 invokerUrls 为空，说明是路由规则或配置规则发生改变，此时 invokerUrls 是空的，直接使用 cachedInvokerUrls 。
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                // 否则把所有的invokerUrls加入缓存
                this.cachedInvokerUrls = new HashSet<URL>();
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }
            // 如果invokerUrls为空，则直接返回
            if (invokerUrls.isEmpty()) {
                return;
            }
            // 将传入的 invokerUrls ，转成新的 urlInvokerMap
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map
            // 转换出新的 methodInvokerMap
            Map<String, List<Invoker<T>>> newMethodInvokerMap = toMethodInvokers(newUrlInvokerMap); // Change method name to map Invoker Map
            // state change
            // If the calculation is wrong, it is not processed.
            // 如果为空，则打印错误日志并且返回
            if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls.toString()));
                return;
            }
            // 更新invoker
            // 若服务引用多 group ，则按照 method + group 聚合 Invoker 集合
            this.methodInvokerMap = multiGroup ? toMergeMethodInvokerMap(newMethodInvokerMap) : newMethodInvokerMap;
            this.urlInvokerMap = newUrlInvokerMap;
            try {
                // 销毁不再使用的 Invoker 集合
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    /**
     * 按照 method + group 聚合 Invoker 集合
     *
     * @param methodMap
     * @return
     */
    private Map<String, List<Invoker<T>>> toMergeMethodInvokerMap(Map<String, List<Invoker<T>>> methodMap) {
        // 循环方法，按照 method + group 聚合 Invoker 集合
        Map<String, List<Invoker<T>>> result = new HashMap<String, List<Invoker<T>>>();
        // 遍历方法集合
        for (Map.Entry<String, List<Invoker<T>>> entry : methodMap.entrySet()) {
            // 获得方法
            String method = entry.getKey();
            // 获得invoker集合
            List<Invoker<T>> invokers = entry.getValue();
            // 获得组集合
            Map<String, List<Invoker<T>>> groupMap = new HashMap<String, List<Invoker<T>>>();
            // 遍历invoker集合
            for (Invoker<T> invoker : invokers) {
                // 获得url携带的组配置
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY, "");
                // 获得该组对应的invoker集合
                List<Invoker<T>> groupInvokers = groupMap.get(group);
                // 如果为空，则新创建一个，然后加入集合
                if (groupInvokers == null) {
                    groupInvokers = new ArrayList<Invoker<T>>();
                    groupMap.put(group, groupInvokers);
                }
                groupInvokers.add(invoker);
            }
            // 如果只有一个组
            if (groupMap.size() == 1) {
                // 返回该组的invoker集合
                result.put(method, groupMap.values().iterator().next());
            } else if (groupMap.size() > 1) {
                // 如果不止一个组
                List<Invoker<T>> groupInvokers = new ArrayList<Invoker<T>>();
                // 遍历组
                for (List<Invoker<T>> groupList : groupMap.values()) {
                    // 每次从集群中选择一个invoker加入groupInvokers
                    groupInvokers.add(cluster.join(new StaticDirectory<T>(groupList)));
                }
                // 加入需要返回的集合
                result.put(method, groupInvokers);
            } else {
                result.put(method, invokers);
            }
        }
        return result;
    }

    /**
     * 处理路由规则 URL 集合
     *
     * @param urls
     * @return null : no routers ,do nothing else :routers list
     */
    private List<Router> toRouters(List<URL> urls) {
        List<Router> routers = new ArrayList<Router>();
        // 如果为空，则直接返回空集合
        if (urls == null || urls.isEmpty()) {
            return routers;
        }
        if (urls != null && !urls.isEmpty()) {
            // 遍历url集合
            for (URL url : urls) {
                // 如果为empty协议，则直接跳过
                if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                    continue;
                }
                // 获得路由规则
                String routerType = url.getParameter(Constants.ROUTER_KEY);
                if (routerType != null && routerType.length() > 0) {
                    // 设置协议
                    url = url.setProtocol(routerType);
                }
                try {
                    // 获得路由
                    Router router = routerFactory.getRouter(url);
                    if (!routers.contains(router))
                        // 加入集合
                        routers.add(router);
                } catch (Throwable t) {
                    logger.error("convert router url to router error, url: " + url, t);
                }
            }
        }
        return routers;
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference. 将url转换为调用者，如果url已被引用，则不会重新引用
     *
     * @param urls
     * @return invokers
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<String, Invoker<T>>();
        // 如果为空，则返回空集合
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<String>();
        // 获得引用服务的协议
        String queryProtocols = this.queryMap.get(Constants.PROTOCOL_KEY);
        // 遍历url
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            // 如果在参考侧配置协议，则仅选择匹配协议
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                // 分割协议
                String[] acceptProtocols = queryProtocols.split(",");
                // 遍历协议
                for (String acceptProtocol : acceptProtocols) {
                    // 如果匹配，则是接受的协议
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    continue;
                }
            }
            // 如果协议是empty，则跳过
            if (Constants.EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }
            // 如果该协议不是dubbo支持的，则打印错误日志，跳过
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() + " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost()
                        + ", supported protocol: " + ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            // 合并url参数
            URL url = mergeUrl(providerUrl);

            String key = url.toFullString(); // The parameter urls are sorted
            if (keys.contains(key)) { // Repeated url
                continue;
            }
            // 添加到keys
            keys.add(key);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            // 如果服务端 URL 发生变化，则重新 refer 引用
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            if (invoker == null) { // Not in the cache, refer again
                try {
                    // 判断是否开启
                    boolean enabled = true;
                    // 获得enabled配置
                    if (url.hasParameter(Constants.DISABLED_KEY)) {
                        enabled = !url.getParameter(Constants.DISABLED_KEY, false);
                    } else {
                        enabled = url.getParameter(Constants.ENABLED_KEY, true);
                    }
                    // 若开启，创建 Invoker 对象
                    if (enabled) {
                        invoker = new InvokerDelegate<T>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                // 添加到 newUrlInvokerMap 中
                if (invoker != null) { // Put new invoker in cache
                    newUrlInvokerMap.put(key, invoker);
                }
            } else {
                newUrlInvokerMap.put(key, invoker);
            }
        }
        // 清空 keys
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider 合并url参数
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        // 合并消费端参数
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        // 合并配置规则
        List<Configurator> localConfigurators = this.configurators; // local reference
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {
                providerUrl = configurator.configure(providerUrl);
            }
        }

        // 不检查连接是否成功，总是创建 Invoker
        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        // 合并提供者参数，因为 directoryUrl 与 override 合并是在 notify 的最后，这里不能够处理
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        // 1.0版本兼容
        if ((providerUrl.getPath() == null || providerUrl.getPath().length() == 0)
                && "dubbo".equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(Constants.INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    /**
     * 根据路由规则 ，匹配合适的 Invoker 集合。
     *
     * @param invokers
     * @param method
     * @return
     */
    private List<Invoker<T>> route(List<Invoker<T>> invokers, String method) {
        Invocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
        List<Router> routers = getRouters();
        if (routers != null) {
            for (Router router : routers) {
                if (router.getUrl() != null) {
                    invokers = router.route(invokers, getConsumerUrl(), invocation);
                }
            }
        }
        return invokers;
    }

    /**
     * Transform the invokers list into a mapping relationship with a method 将调用者列表转换为与方法的映射关系
     *
     * @param invokersMap Invoker Map
     * @return Mapping relation between Invoker and method
     */
    private Map<String, List<Invoker<T>>> toMethodInvokers(Map<String, Invoker<T>> invokersMap) {
        Map<String, List<Invoker<T>>> newMethodInvokerMap = new HashMap<String, List<Invoker<T>>>();
        // According to the methods classification declared by the provider URL, the methods is compatible with the registry to execute the filtered methods
        List<Invoker<T>> invokersList = new ArrayList<Invoker<T>>();
        if (invokersMap != null && invokersMap.size() > 0) {
            // 遍历调用者列表
            for (Invoker<T> invoker : invokersMap.values()) {
                String parameter = invoker.getUrl().getParameter(Constants.METHODS_KEY);
                // 按服务提供者 URL 所声明的 methods 分类
                if (parameter != null && parameter.length() > 0) {
                    // 分割参数得到方法集合
                    String[] methods = Constants.COMMA_SPLIT_PATTERN.split(parameter);
                    if (methods != null && methods.length > 0) {
                        // 遍历方法集合
                        for (String method : methods) {
                            if (method != null && method.length() > 0
                                    && !Constants.ANY_VALUE.equals(method)) {
                                // 获得该方法对应的invoker，如果为空，则创建
                                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                                if (methodInvokers == null) {
                                    methodInvokers = new ArrayList<Invoker<T>>();
                                    newMethodInvokerMap.put(method, methodInvokers);
                                }
                                methodInvokers.add(invoker);
                            }
                        }
                    }
                }
                invokersList.add(invoker);
            }
        }
        // 根据路由规则，匹配合适的 Invoker 集合。
        List<Invoker<T>> newInvokersList = route(invokersList, null);
        // 添加 `newInvokersList` 到 `newMethodInvokerMap` 中，表示该服务提供者的全量 Invoker 集合
        newMethodInvokerMap.put(Constants.ANY_VALUE, newInvokersList);
        if (serviceMethods != null && serviceMethods.length > 0) {
            // 循环方法，获得每个方法路由匹配的invoker集合
            for (String method : serviceMethods) {
                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                if (methodInvokers == null || methodInvokers.isEmpty()) {
                    methodInvokers = newInvokersList;
                }
                newMethodInvokerMap.put(method, route(methodInvokers, method));
            }
        }
        // sort and unmodifiable
        // 循环排序每个方法的 Invoker 集合，排序
        for (String method : new HashSet<String>(newMethodInvokerMap.keySet())) {
            List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
            Collections.sort(methodInvokers, InvokerComparator.getComparator());
            newMethodInvokerMap.put(method, Collections.unmodifiableList(methodInvokers));
        }
        // 设置为不可变
        return Collections.unmodifiableMap(newMethodInvokerMap);
    }

    /**
     * Close all invokers 关闭所有的服务
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        // 如果invoker集合不为空
        if (localUrlInvokerMap != null) {
            // 遍历
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                try {
                    // 销毁invoker
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            // 清空集合
            localUrlInvokerMap.clear();
        }
        methodInvokerMap = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak 销毁不再使用的 Invoker 集合
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        // 记录已经删除的invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            // 遍历旧的invoker集合
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<String>();
                    }
                    // 加入该invoker
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            // 遍历需要删除的invoker  url集合
            for (String url : deleted) {
                if (url != null) {
                    // 移除该url
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            // 销毁invoker
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] faild. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<Invoker<T>> doList(Invocation invocation) {
        // 如果禁止访问，则抛出异常
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION,
                    "No provider available from registry " + getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " + NetUtils.getLocalHost()
                            + " use dubbo version " + Version.getVersion() + ", please check status of providers(disabled, not registered or in blacklist).");
        }
        List<Invoker<T>> invokers = null;
        // 获取invoker是从methodInvokerMap中获取
        Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // local reference
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            // 获得方法名
            String methodName = RpcUtils.getMethodName(invocation);
            // 获得参数名
            Object[] args = RpcUtils.getArguments(invocation);
            if (args != null && args.length > 0 && args[0] != null
                    && (args[0] instanceof String || args[0].getClass().isEnum())) {
                // 根据第一个参数枚举路由
                invokers = localMethodInvokerMap.get(methodName + "." + args[0]); // The routing can be enumerated according to the first parameter
            }
            if (invokers == null) {
                // 根据方法名获得 Invoker 集合
                invokers = localMethodInvokerMap.get(methodName);
            }
            if (invokers == null) {
                // 使用全量 Invoker 集合。例如，`#$echo(name)` ，回声方法
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }
            if (invokers == null) {
                // 使用 `methodInvokerMap` 第一个 Invoker 集合。防御性编程。
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }
        return invokers == null ? new ArrayList<Invoker<T>>(0) : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, List<Invoker<T>>> getMethodInvokerMap() {
        return methodInvokerMap;
    }

    private static class InvokerComparator implements Comparator<Invoker<?>> {

        private static final InvokerComparator comparator = new InvokerComparator();

        private InvokerComparator() {
        }

        public static InvokerComparator getComparator() {
            return comparator;
        }

        @Override
        public int compare(Invoker<?> o1, Invoker<?> o2) {
            return o1.getUrl().toString().compareTo(o2.getUrl().toString());
        }

    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }
}
