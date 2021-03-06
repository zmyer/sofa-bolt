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
package com.alipay.remoting;

import com.alipay.remoting.exception.RemotingException;
import com.alipay.remoting.util.FutureTaskUtil;
import com.alipay.remoting.util.GlobalSwitch;
import com.alipay.remoting.util.RunStateRecordedFutureTask;
import com.alipay.remoting.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Abstract implementation of connection manager
 *
 * @author xiaomin.cxm
 * @version $Id: DefaultConnectionManager.java, v 0.1 Mar 8, 2016 10:43:51 AM xiaomin.cxm Exp $
 */
// TODO: 2018/4/23 by zmyer
public class DefaultConnectionManager implements ConnectionManager, ConnectionHeartbeatManager,
                                     Scannable {

    // ~~~ constants
    /**
     * logger
     */
    private static final Logger                                                     logger              = LoggerFactory
                                                                                                            .getLogger(DefaultConnectionManager.class);

    /**
     * default expire time to remove connection pool, time unit: milliseconds
     */
    private static final int                                                        DEFAULT_EXPIRE_TIME = 10 * 60 * 1000;

    /**
     * default retry times when falied to get result of FutureTask
     */
    private static final int                                                        DEFAULT_RETRY_TIMES = 2;

    // ~~~ members

    /**
     * min pool size for asyncCreateConnectionExecutor
     */
    private int                                                                     minPoolSize         = SystemProperties
                                                                                                            .conn_create_tp_min_size();

    /**
     * max pool size for asyncCreateConnectionExecutor
     */
    private int                                                                     maxPoolSize         = SystemProperties
                                                                                                            .conn_create_tp_max_size();

    /**
     * queue size for asyncCreateConnectionExecutor
     */
    private int                                                                     queueSize           = SystemProperties
                                                                                                            .conn_create_tp_queue_size();

    /**
     * keep alive time for asyncCreateConnectionExecutor
     */
    private long                                                                    keepAliveTime       = SystemProperties
                                                                                                            .conn_create_tp_keepalive();

    /**
     * executor initialie status
     */
    private volatile boolean                                                        executorInitialized;

    /**
     * executor to create connections in async way
     * note: this is lazy initialized
     */
    private Executor                                                                asyncCreateConnectionExecutor;

    /**
     * switch status
     */
    private GlobalSwitch                                                            globalSwitch;

    /**
     * connection pool initialize tasks
     */
    protected ConcurrentHashMap<String, RunStateRecordedFutureTask<ConnectionPool>> connTasks;

    /**
     * heal connection tasks
     */
    protected ConcurrentHashMap<String, FutureTask<Integer>>                        healTasks;

    /**
     * connection pool select strategy
     */
    protected ConnectionSelectStrategy                                              connectionSelectStrategy;

    /**
     * address parser
     */
    protected RemotingAddressParser                                                 addressParser;

    /**
     * connection factory
     */
    protected ConnectionFactory                                                     connectionFactory;

    /**
     * connection event handler
     */
    protected ConnectionEventHandler                                                connectionEventHandler;

    /**
     * connection event listener
     */
    protected ConnectionEventListener                                               connectionEventListener;

    // ~~~ constructors

    /**
     * Default constructor
     */
    // TODO: 2018/4/23 by zmyer
    public DefaultConnectionManager() {
        this.connTasks = new ConcurrentHashMap<String, RunStateRecordedFutureTask<ConnectionPool>>();
        this.healTasks = new ConcurrentHashMap<String, FutureTask<Integer>>();
        this.connectionSelectStrategy = new RandomSelectStrategy(globalSwitch);
    }

    /**
     * @param connectionSelectStrategy
     */
    // TODO: 2018/4/23 by zmyer
    public DefaultConnectionManager(ConnectionSelectStrategy connectionSelectStrategy) {
        this();
        this.connectionSelectStrategy = connectionSelectStrategy;
    }

    /**
     * @param connectionSelectStrategy
     * @param connctionFactory
     */
    // TODO: 2018/4/23 by zmyer
    public DefaultConnectionManager(ConnectionSelectStrategy connectionSelectStrategy,
                                    ConnectionFactory connctionFactory) {
        this(connectionSelectStrategy);
        this.connectionFactory = connctionFactory;
    }

    /**
     * @param connectionFactory
     * @param addressParser
     * @param connectionEventHandler
     */
    // TODO: 2018/4/23 by zmyer
    public DefaultConnectionManager(ConnectionFactory connectionFactory,
                                    RemotingAddressParser addressParser,
                                    ConnectionEventHandler connectionEventHandler) {
        this(new RandomSelectStrategy(), connectionFactory);
        this.addressParser = addressParser;
        this.connectionEventHandler = connectionEventHandler;
    }

    /**
     * @param connectionSelectStrategy
     * @param connctionFactory
     * @param connectionEventHandler
     * @param connectionEventListener
     */
    // TODO: 2018/4/23 by zmyer
    public DefaultConnectionManager(ConnectionSelectStrategy connectionSelectStrategy,
                                    ConnectionFactory connctionFactory,
                                    ConnectionEventHandler connectionEventHandler,
                                    ConnectionEventListener connectionEventListener) {
        this(connectionSelectStrategy, connctionFactory);
        this.connectionEventHandler = connectionEventHandler;
        this.connectionEventListener = connectionEventListener;
    }

    /**
     * @param connectionSelectStrategy
     * @param connctionFactory
     * @param connectionEventHandler
     * @param connectionEventListener
     * @param globalSwitch
     */
    // TODO: 2018/4/23 by zmyer
    public DefaultConnectionManager(ConnectionSelectStrategy connectionSelectStrategy,
                                    ConnectionFactory connctionFactory,
                                    ConnectionEventHandler connectionEventHandler,
                                    ConnectionEventListener connectionEventListener,
                                    GlobalSwitch globalSwitch) {
        this(connectionSelectStrategy, connctionFactory, connectionEventHandler,
            connectionEventListener);
        this.globalSwitch = globalSwitch;
    }

    // ~~~ interface methods

    /**
     * @see com.alipay.remoting.ConnectionManager#init()
     */
    // TODO: 2018/4/23 by zmyer
    public void init() {
        //为连接事件处理器注册连接管理器
        this.connectionEventHandler.setConnectionManager(this);
        //为连接事件处理器注册监听器
        this.connectionEventHandler.setConnectionEventListener(connectionEventListener);
        //初始化连接管理器
        this.connectionFactory.init(connectionEventHandler);
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#add(com.alipay.remoting.Connection)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void add(Connection connection) {
        //读取连接中所有的连接池键
        Set<String> poolKeys = connection.getPoolKeys();
        for (String poolKey : poolKeys) {
            //将连接插入到连接池中
            this.add(connection, poolKey);
        }
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#add(com.alipay.remoting.Connection, java.lang.String)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void add(Connection connection, String poolKey) {
        ConnectionPool pool = null;
        try {
            // get or create an empty connection pool
            //创建连接池
            pool = this.getConnectionPoolAndCreateIfAbsent(poolKey, new ConnectionPoolCall());
        } catch (Exception e) {
            // should not reach here.
            logger.error(
                "[NOTIFYME] Exception occurred when getOrCreateIfAbsent an empty ConnectionPool!",
                e);
        }
        if (pool != null) {
            //将连接对象插入到池中
            pool.add(connection);
        } else {
            // should not reach here.
            logger.error("[NOTIFYME] Connection pool NULL!");
        }
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#get(String)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public Connection get(String poolKey) {
        //读取指定的连接池
        ConnectionPool pool = this.getConnectionPool(this.connTasks.get(poolKey));
        //返回合适的连接对象
        return null == pool ? null : pool.get();
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#getAll(java.lang.String)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public List<Connection> getAll(String poolKey) {
        ConnectionPool pool = this.getConnectionPool(this.connTasks.get(poolKey));
        return null == pool ? new ArrayList<Connection>() : pool.getAll();
    }

    /**
     * Get all connections of all poolKey.
     *
     * @return a map with poolKey as key and a list of connections in ConnectionPool as value
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public Map<String, List<Connection>> getAll() {
        Map<String, List<Connection>> allConnections = new HashMap<String, List<Connection>>();
        Iterator<Map.Entry<String, RunStateRecordedFutureTask<ConnectionPool>>> iterator = this
            .getConnPools().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, RunStateRecordedFutureTask<ConnectionPool>> entry = iterator.next();
            //首先获取连接池对象
            ConnectionPool pool = FutureTaskUtil.getFutureTaskResult(entry.getValue(), logger);
            if (null != pool) {
                //将连接池对象中的连接插入到结果集中
                allConnections.put(entry.getKey(), pool.getAll());
            }
        }
        //返回结果
        return allConnections;
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#remove(com.alipay.remoting.Connection)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void remove(Connection connection) {
        if (null == connection) {
            return;
        }
        Set<String> poolKeys = connection.getPoolKeys();
        if (null == poolKeys || poolKeys.isEmpty()) {
            connection.close();
            logger.warn("Remove and close a standalone connection.");
        } else {
            for (String poolKey : poolKeys) {
                this.remove(connection, poolKey);
            }
        }
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#remove(com.alipay.remoting.Connection, java.lang.String)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void remove(Connection connection, String poolKey) {
        if (null == connection || StringUtils.isBlank(poolKey)) {
            return;
        }
        ConnectionPool pool = this.getConnectionPool(this.connTasks.get(poolKey));
        if (null == pool) {
            connection.close();
            logger.warn("Remove and close a standalone connection.");
        } else {
            //从连接池中删除指定的连接对象
            pool.removeAndTryClose(connection);
            if (pool.isEmpty()) {
                //删除指定的连接任务
                this.removeTask(poolKey);
                logger.warn(
                    "Remove and close the last connection in ConnectionPool with poolKey {}",
                    poolKey);
            } else {
                logger
                    .warn(
                        "Remove and close a connection in ConnectionPool with poolKey {}, {} connections left.",
                        poolKey, pool.size());
            }
        }
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#remove(java.lang.String)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void remove(String poolKey) {
        if (StringUtils.isBlank(poolKey)) {
            return;
        }

        RunStateRecordedFutureTask<ConnectionPool> task = this.connTasks.remove(poolKey);
        if (null != task) {
            ConnectionPool pool = this.getConnectionPool(task);
            if (null != pool) {
                pool.removeAllAndTryClose();
                logger.warn("Remove and close all connections in ConnectionPool of poolKey={}",
                    poolKey);
            }
        }
    }

    /**
     * Warning! This is weakly consistent implementation, to prevent lock the whole {@link ConcurrentHashMap}.
     *
     * @see ConnectionManager#removeAll()
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void removeAll() {
        if (null == this.connTasks || this.connTasks.isEmpty()) {
            return;
        }
        if (null != this.connTasks && !this.connTasks.isEmpty()) {
            Iterator<String> iter = this.connTasks.keySet().iterator();
            while (iter.hasNext()) {
                String poolKey = iter.next();
                this.removeTask(poolKey);
                iter.remove();
            }
            logger.warn("All connection pool and connections have been removed!");
        }
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#check(com.alipay.remoting.Connection)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void check(Connection connection) throws RemotingException {
        if (connection == null) {
            throw new RemotingException("Connection is null when do check!");
        }
        if (connection.getChannel() == null || !connection.getChannel().isActive()) {
            this.remove(connection);
            throw new RemotingException("Check connection failed for address: "
                                        + connection.getUrl());
        }
        if (!connection.getChannel().isWritable()) {
            // No remove. Most of the time it is unwritable temporarily.
            throw new RemotingException("Check connection failed for address: "
                                        + connection.getUrl() + ", maybe write overflow!");
        }
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#count(java.lang.String)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public int count(String poolKey) {
        if (StringUtils.isBlank(poolKey)) {
            return 0;
        }
        ConnectionPool pool = this.getConnectionPool(this.connTasks.get(poolKey));
        if (null != pool) {
            return pool.size();
        } else {
            return 0;
        }
    }

    /**
     * in case of cache pollution and connection leak, to do schedule scan
     *
     * @see com.alipay.remoting.Scannable#scan()
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void scan() {
        if (null != this.connTasks && !this.connTasks.isEmpty()) {
            Iterator<String> iter = this.connTasks.keySet().iterator();
            while (iter.hasNext()) {
                String poolKey = iter.next();
                ConnectionPool pool = this.getConnectionPool(this.connTasks.get(poolKey));
                if (null != pool) {
                    //扫描连接池
                    pool.scan();
                    if (pool.isEmpty()) {
                        if ((System.currentTimeMillis() - pool.getLastAccessTimestamp()) > DEFAULT_EXPIRE_TIME) {
                            iter.remove();
                            logger.warn("Remove expired pool task of poolKey {} which is empty.",
                                poolKey);
                        }
                    }
                }
            }
        }
    }

    /**
     * If no task cached, create one and initialize the connections.
     *
     * @see ConnectionManager#getAndCreateIfAbsent(Url)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public Connection getAndCreateIfAbsent(Url url) throws InterruptedException, RemotingException {
        // get and create a connection pool with initialized connections.
        ConnectionPool pool = this.getConnectionPoolAndCreateIfAbsent(url.getUniqueKey(),
            new ConnectionPoolCall(url));
        if (null != pool) {
            return pool.get();
        } else {
            logger.error("[NOTIFYME] bug detected! pool here must not be null!");
            return null;
        }
    }

    /**
     * If no task cached, create one and initialize the connections.
     * If task cached, check whether the number of connections adequate, if not then heal it.
     *
     * @param url
     * @return
     * @throws InterruptedException
     * @throws RemotingException
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void createConnectionAndHealIfNeed(Url url) throws InterruptedException,
                                                      RemotingException {
        // get and create a connection pool with initialized connections.
        ConnectionPool pool = this.getConnectionPoolAndCreateIfAbsent(url.getUniqueKey(),
            new ConnectionPoolCall(url));
        if (null != pool) {
            healIfNeed(pool, url);
        } else {
            logger.error("[NOTIFYME] bug detected! pool here must not be null!");
        }
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#create(com.alipay.remoting.Url)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public Connection create(Url url) throws RemotingException {
        Connection conn = null;
        try {
            //创建连接对象
            conn = this.connectionFactory.createConnection(url);
        } catch (Exception e) {
            throw new RemotingException("Create connection failed. The address is "
                                        + url.getOriginUrl(), e);
        }
        //返回结果
        return conn;
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#create(java.lang.String, int, int)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public Connection create(String ip, int port, int connectTimeout) throws RemotingException {
        Connection conn = null;
        try {
            //创建连接对象
            conn = this.connectionFactory.createConnection(ip, port, connectTimeout);
        } catch (Exception e) {
            throw new RemotingException("Create connection failed. The address is " + ip + ":"
                                        + port, e);
        }
        //返回结果
        return conn;
    }

    /**
     * @see com.alipay.remoting.ConnectionManager#create(java.lang.String, int)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public Connection create(String address, int connectTimeout) throws RemotingException {
        Url url = this.addressParser.parse(address);
        url.setConnectTimeout(connectTimeout);
        return create(url);
    }

    /**
     * @see com.alipay.remoting.ConnectionHeartbeatManager#disableHeartbeat(com.alipay.remoting.Connection)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void disableHeartbeat(Connection connection) {
        if (null != connection) {
            connection.getChannel().attr(Connection.HEARTBEAT_SWITCH).set(false);
        }
    }

    /**
     * @see com.alipay.remoting.ConnectionHeartbeatManager#enableHeartbeat(com.alipay.remoting.Connection)
     */
    // TODO: 2018/4/23 by zmyer
    @Override
    public void enableHeartbeat(Connection connection) {
        if (null != connection) {
            connection.getChannel().attr(Connection.HEARTBEAT_SWITCH).set(true);
        }
    }

    // ~~~ private methods

    /**
     * get connection pool from future task
     *
     * @param task
     * @return
     */
    // TODO: 2018/4/23 by zmyer
    private ConnectionPool getConnectionPool(RunStateRecordedFutureTask<ConnectionPool> task) {
        return FutureTaskUtil.getFutureTaskResult(task, logger);

    }

    /**
     * Get the mapping instance of {@link ConnectionPool} with the specified poolKey,
     * or create one if there is none mapping in connTasks.
     *
     * @param poolKey  mapping key of {@link ConnectionPool}
     * @param callable the callable task
     * @return a non-nullable instance of {@link ConnectionPool}
     * @throws RemotingException if there is no way to get an available {@link ConnectionPool}
     * @throws InterruptedException
     */
    // TODO: 2018/4/23 by zmyer
    private ConnectionPool getConnectionPoolAndCreateIfAbsent(String poolKey,
                                                              Callable<ConnectionPool> callable)
                                                                                                throws RemotingException,
                                                                                                InterruptedException {
        RunStateRecordedFutureTask<ConnectionPool> initialTask = null;
        ConnectionPool pool = null;

        int retry = DEFAULT_RETRY_TIMES;

        int timesOfResultNull = 0;
        int timesOfInterrupt = 0;

        for (int i = 0; (i < retry) && (pool == null); ++i) {
            //从连接任务队列中，读取当前正在执行的任务
            initialTask = this.connTasks.get(poolKey);
            if (null == initialTask) {
                //如果不存在，则直接创建
                initialTask = new RunStateRecordedFutureTask<ConnectionPool>(callable);
                //将创建的连接任务插入到集合中
                initialTask = this.connTasks.putIfAbsent(poolKey, initialTask);
                if (null == initialTask) {
                    //如果之前不存在，则从集合中读取任务信息
                    initialTask = this.connTasks.get(poolKey);
                    //开始连接任务
                    initialTask.run();
                }
            }

            try {
                //获取连接任务执行结果
                pool = initialTask.get();
                if (null == pool) {
                    if (i + 1 < retry) {
                        //如果创建失败，则重试
                        timesOfResultNull++;
                        continue;
                    }
                    //将任务从集合中删除
                    this.connTasks.remove(poolKey);
                    String errMsg = "Get future task result null for poolKey [" + poolKey
                                    + "] after [" + (timesOfResultNull + 1) + "] times try.";
                    throw new RemotingException(errMsg);
                }
            } catch (InterruptedException e) {
                if (i + 1 < retry) {
                    timesOfInterrupt++;
                    continue;// retry if interrupted
                }
                this.connTasks.remove(poolKey);
                logger
                    .warn(
                        "Future task of poolKey {} interrupted {} times. InterruptedException thrown and stop retry.",
                        poolKey, (timesOfInterrupt + 1), e);
                throw e;
            } catch (ExecutionException e) {
                // DO NOT retry if ExecutionException occurred
                this.connTasks.remove(poolKey);

                Throwable cause = e.getCause();
                if (cause instanceof RemotingException) {
                    throw (RemotingException) cause;
                } else {
                    FutureTaskUtil.launderThrowable(cause);
                }
            }
        }
        return pool;
    }

    /**
     * remove task and remove all connections
     *
     * @param poolKey
     */
    // TODO: 2018/4/23 by zmyer
    private void removeTask(String poolKey) {
        RunStateRecordedFutureTask<ConnectionPool> task = this.connTasks.remove(poolKey);
        if (null != task) {
            //获取连接池
            ConnectionPool pool = FutureTaskUtil.getFutureTaskResult(task, logger);
            if (null != pool) {
                //删除连接
                pool.removeAllAndTryClose();
            }
        }
    }

    /**
     * execute heal connection tasks if the actual number of connections in pool is less than expected
     *
     * @param pool
     * @param url
     */
    // TODO: 2018/4/23 by zmyer
    private void healIfNeed(ConnectionPool pool, Url url) throws RemotingException,
                                                         InterruptedException {
        String poolKey = url.getUniqueKey();
        // only when async creating connections done
        // and the actual size of connections less than expected, the healing task can be run.
        if (pool.isAsyncCreationDone() && pool.size() < url.getConnNum()) {
            //获取恢复连接任务
            FutureTask<Integer> task = this.healTasks.get(poolKey);
            if (null == task) {
                //创建恢复连接任务对象
                task = new FutureTask<Integer>(new HealConnectionCall(url, pool));
                //将恢复任务插入到集合中
                task = this.healTasks.putIfAbsent(poolKey, task);
                if (null == task) {
                    //如果之前没有创建，则直接运行任务
                    task = this.healTasks.get(poolKey);
                    task.run();
                }
            }
            try {
                //获取恢复的连接数目
                int numAfterHeal = task.get();
                if (logger.isDebugEnabled()) {
                    logger.debug("[NOTIFYME] - conn num after heal {}, expected {}, warmup {}",
                        numAfterHeal, url.getConnNum(), url.isConnWarmup());
                }
            } catch (InterruptedException e) {
                this.healTasks.remove(poolKey);
                throw e;
            } catch (ExecutionException e) {
                this.healTasks.remove(poolKey);
                Throwable cause = e.getCause();
                if (cause instanceof RemotingException) {
                    throw (RemotingException) cause;
                } else {
                    FutureTaskUtil.launderThrowable(cause);
                }
            }
            // heal task is one-off, remove from cache directly after run
            //连接恢复完毕，需要及时删除恢复任务
            this.healTasks.remove(poolKey);
        }
    }

    /**
     * a callable definition for initialize {@link ConnectionPool}
     *
     * @author tsui
     * @version $Id: ConnectionPoolCall.java, v 0.1 Mar 8, 2016 10:43:51 AM xiaomin.cxm Exp $
     */
    // TODO: 2018/4/23 by zmyer
    private class ConnectionPoolCall implements Callable<ConnectionPool> {
        //是否初始化连接
        private boolean whetherInitConnection;
        //连接地址信息
        private Url     url;

        /**
         * create a {@link ConnectionPool} but not init connections
         */
        // TODO: 2018/4/23 by zmyer
        public ConnectionPoolCall() {
            this.whetherInitConnection = false;
        }

        /**
         * create a {@link ConnectionPool} and init connections with the specified {@link Url}
         *
         * @param url
         */
        // TODO: 2018/4/23 by zmyer
        public ConnectionPoolCall(Url url) {
            this.whetherInitConnection = true;
            this.url = url;
        }

        // TODO: 2018/4/23 by zmyer
        @Override
        public ConnectionPool call() throws Exception {
            //创建连接池对象
            final ConnectionPool pool = new ConnectionPool(connectionSelectStrategy);
            if (whetherInitConnection) {
                try {
                    //开始初始化连接池
                    doCreate(this.url, pool, this.getClass().getSimpleName(), 1);
                } catch (Exception e) {
                    pool.removeAllAndTryClose();
                    throw e;
                }
            }
            //返回结果
            return pool;
        }

    }

    /**
     * a callable definition for healing connections in {@link ConnectionPool}
     *
     * @author tsui
     * @version $Id: HealConnectionCall.java, v 0.1 Jul 20, 2017 10:23:23 AM xiaomin.cxm Exp $
     */
    // TODO: 2018/4/23 by zmyer
    private class HealConnectionCall implements Callable<Integer> {
        private Url            url;
        private ConnectionPool pool;

        /**
         * create a {@link ConnectionPool} and init connections with the specified {@link Url}
         *
         * @param url
         */
        public HealConnectionCall(Url url, ConnectionPool pool) {
            this.url = url;
            this.pool = pool;
        }

        // TODO: 2018/4/23 by zmyer
        @Override
        public Integer call() throws Exception {
            //创建连接
            doCreate(this.url, this.pool, this.getClass().getSimpleName(), 0);
            //返回连接池大小
            return this.pool.size();
        }
    }

    /**
     * do create connections
     *
     * @param url
     * @param pool
     * @param taskName
     * @param syncCreateNumWhenNotWarmup you can specify this param to ensure at least desired number of connections available in sync way
     * @throws RemotingException
     */
    // TODO: 2018/4/23 by zmyer
    private void doCreate(final Url url, final ConnectionPool pool, final String taskName,
                          final int syncCreateNumWhenNotWarmup) throws RemotingException {
        final int actualNum = pool.size();
        final int expectNum = url.getConnNum();
        if (actualNum < expectNum) {
            if (logger.isDebugEnabled()) {
                logger.debug("actual num {}, expect num {}, task name {}", actualNum, expectNum,
                    taskName);
            }
            if (url.isConnWarmup()) {
                //如果开启了连接预热功能，在提前创建连接对象
                for (int i = actualNum; i < expectNum; ++i) {
                    Connection connection = create(url);
                    //将创建的连接对象插入到集合中
                    pool.add(connection);
                }
            } else {
                if (syncCreateNumWhenNotWarmup < 0 || syncCreateNumWhenNotWarmup > url.getConnNum()) {
                    throw new IllegalArgumentException(
                        "sync create number when not warmup should be [0," + url.getConnNum() + "]");
                }
                // create connection in sync way
                if (syncCreateNumWhenNotWarmup > 0) {
                    for (int i = 0; i < syncCreateNumWhenNotWarmup; ++i) {
                        //创建连接对象
                        Connection connection = create(url);
                        //插入集合
                        pool.add(connection);
                    }
                    if (syncCreateNumWhenNotWarmup == url.getConnNum()) {
                        return;
                    }
                }
                // initialize executor in lazy way
                //初始化执行器
                initializeExecutor();
                //标记异步开始
                pool.markAsyncCreationStart();// mark the start of async
                try {
                    this.asyncCreateConnectionExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                for (int i = pool.size(); i < url.getConnNum(); ++i) {
                                    Connection conn = null;
                                    try {
                                        //创建新的连接对象
                                        conn = create(url);
                                    } catch (RemotingException e) {
                                        logger
                                            .error(
                                                "Exception occurred in async create connection thread for {}, taskName {}",
                                                url.getUniqueKey(), taskName, e);
                                    }
                                    //将连接对象插入到池中
                                    pool.add(conn);
                                }
                            } finally {
                                //标记创建完毕
                                pool.markAsyncCreationDone();// mark the end of async
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    pool.markAsyncCreationDone();// mark the end of async when reject
                    throw e;
                }
            } // end of NOT warm up
        } // end of if
    }

    /**
     * initialize executor
     */
    // TODO: 2018/4/23 by zmyer
    private void initializeExecutor() {
        if (!this.executorInitialized) {
            this.executorInitialized = true;
            //创建异步建立连接执行器
            this.asyncCreateConnectionExecutor = new ThreadPoolExecutor(minPoolSize, maxPoolSize,
                keepAliveTime, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(queueSize),
                new NamedThreadFactory("Bolt-conn-warmup-executor"));
        }
    }

    // ~~~ getters and setters

    /**
     * Getter method for property <tt>connectionSelectStrategy</tt>.
     *
     * @return property value of connectionSelectStrategy
     */
    public ConnectionSelectStrategy getConnectionSelectStrategy() {
        return connectionSelectStrategy;
    }

    /**
     * Setter method for property <tt>connectionSelectStrategy</tt>.
     *
     * @param connectionSelectStrategy value to be assigned to property connectionSelectStrategy
     */
    public void setConnectionSelectStrategy(ConnectionSelectStrategy connectionSelectStrategy) {
        this.connectionSelectStrategy = connectionSelectStrategy;
    }

    /**
     * Getter method for property <tt>addressParser</tt>.
     *
     * @return property value of addressParser
     */
    public RemotingAddressParser getAddressParser() {
        return addressParser;
    }

    /**
     * Setter method for property <tt>addressParser</tt>.
     *
     * @param addressParser value to be assigned to property addressParser
     */
    public void setAddressParser(RemotingAddressParser addressParser) {
        this.addressParser = addressParser;
    }

    /**
     * Getter method for property <tt>connctionFactory</tt>.
     *
     * @return property value of connctionFactory
     */
    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    /**
     * Setter method for property <tt>connctionFactory</tt>.
     *
     * @param connectionFactory value to be assigned to property connctionFactory
     */
    public void setConnectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * Getter method for property <tt>connectionEventHandler</tt>.
     *
     * @return property value of connectionEventHandler
     */
    public ConnectionEventHandler getConnectionEventHandler() {
        return connectionEventHandler;
    }

    /**
     * Setter method for property <tt>connectionEventHandler</tt>.
     *
     * @param connectionEventHandler value to be assigned to property connectionEventHandler
     */
    public void setConnectionEventHandler(ConnectionEventHandler connectionEventHandler) {
        this.connectionEventHandler = connectionEventHandler;
    }

    /**
     * Getter method for property <tt>connectionEventListener</tt>.
     *
     * @return property value of connectionEventListener
     */
    public ConnectionEventListener getConnectionEventListener() {
        return connectionEventListener;
    }

    /**
     * Setter method for property <tt>connectionEventListener</tt>.
     *
     * @param connectionEventListener value to be assigned to property connectionEventListener
     */
    public void setConnectionEventListener(ConnectionEventListener connectionEventListener) {
        this.connectionEventListener = connectionEventListener;
    }

    /**
     * Getter method for property <tt>connPools</tt>.
     *
     * @return property value of connPools
     */
    public ConcurrentHashMap<String, RunStateRecordedFutureTask<ConnectionPool>> getConnPools() {
        return this.connTasks;
    }
}
