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
package org.apache.accumulo.monitor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.accumulo.fate.util.UtilWaitThread.sleepUninterruptibly;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.impl.MasterClient;
import org.apache.accumulo.core.client.impl.Table;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.gc.thrift.GCMonitorService;
import org.apache.accumulo.core.gc.thrift.GCStatus;
import org.apache.accumulo.core.master.thrift.MasterClientService;
import org.apache.accumulo.core.master.thrift.MasterMonitorInfo;
import org.apache.accumulo.core.master.thrift.TableInfo;
import org.apache.accumulo.core.master.thrift.TabletServerStatus;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.tabletserver.thrift.ActiveScan;
import org.apache.accumulo.core.tabletserver.thrift.TabletClientService.Client;
import org.apache.accumulo.core.trace.DistributedTrace;
import org.apache.accumulo.core.trace.Tracer;
import org.apache.accumulo.core.util.Daemon;
import org.apache.accumulo.core.util.HostAndPort;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.core.util.ServerServices;
import org.apache.accumulo.core.util.ServerServices.Service;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.fate.util.LoggingRunnable;
import org.apache.accumulo.fate.zookeeper.ZooLock.LockLossReason;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeMissingPolicy;
import org.apache.accumulo.server.Accumulo;
import org.apache.accumulo.server.AccumuloServerContext;
import org.apache.accumulo.server.HighlyAvailableService;
import org.apache.accumulo.server.ServerOpts;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.conf.ServerConfigurationFactory;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.fs.VolumeManagerImpl;
import org.apache.accumulo.server.metrics.MetricsSystemHelper;
import org.apache.accumulo.server.monitor.LogService;
import org.apache.accumulo.server.problems.ProblemReports;
import org.apache.accumulo.server.problems.ProblemType;
import org.apache.accumulo.server.security.SecurityUtil;
import org.apache.accumulo.server.util.Halt;
import org.apache.accumulo.server.util.TableInfoUtil;
import org.apache.accumulo.server.util.time.SimpleTimer;
import org.apache.accumulo.server.zookeeper.ZooLock;
import org.apache.accumulo.server.zookeeper.ZooReaderWriter;
import org.apache.zookeeper.KeeperException;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.mvc.MvcFeature;
import org.glassfish.jersey.server.mvc.freemarker.FreemarkerMvcFeature;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serve master statistics with an embedded web server.
 */
public class Monitor implements HighlyAvailableService {

    private static final Logger log = LoggerFactory.getLogger(Monitor.class);

    private static final int REFRESH_TIME = 5;

    private static AtomicLong lastRecalc = new AtomicLong(0L);

    private static double totalIngestRate = 0.0;

    private static double totalQueryRate = 0.0;

    private static double totalScanRate = 0.0;

    private static long totalEntries = 0L;

    private static int totalTabletCount = 0;

    private static long totalHoldTime = 0;

    private static long totalLookups = 0;

    private static int totalTables = 0;

    public static HighlyAvailableService HA_SERVICE_INSTANCE = null;

    private static final AtomicBoolean monitorInitialized = new AtomicBoolean(false);

    private static class MaxList<T> extends LinkedList<Pair<Long, T>> {

        private static final long serialVersionUID = 1L;

        private long maxDelta;

        public MaxList(long maxDelta) {
            this.maxDelta = maxDelta;
        }

        @Override
        public boolean add(Pair<Long, T> obj) {
            boolean result = super.add(obj);
            if (obj.getFirst() - get(0).getFirst() > maxDelta) {
                remove(0);
            }
            return result;
        }
    }

    private static final int MAX_TIME_PERIOD = 60 * 60 * 1000;

    private static final List<Pair<Long, Double>> loadOverTime = Collections.synchronizedList(new MaxList<Double>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Double>> ingestRateOverTime = Collections.synchronizedList(new MaxList<Double>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Double>> ingestByteRateOverTime = Collections.synchronizedList(new MaxList<Double>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Integer>> minorCompactionsOverTime = Collections.synchronizedList(new MaxList<Integer>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Integer>> majorCompactionsOverTime = Collections.synchronizedList(new MaxList<Integer>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Double>> lookupsOverTime = Collections.synchronizedList(new MaxList<Double>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Integer>> queryRateOverTime = Collections.synchronizedList(new MaxList<Integer>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Integer>> scanRateOverTime = Collections.synchronizedList(new MaxList<Integer>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Double>> queryByteRateOverTime = Collections.synchronizedList(new MaxList<Double>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Double>> indexCacheHitRateOverTime = Collections.synchronizedList(new MaxList<Double>(MAX_TIME_PERIOD));

    private static final List<Pair<Long, Double>> dataCacheHitRateOverTime = Collections.synchronizedList(new MaxList<Double>(MAX_TIME_PERIOD));

    private static EventCounter lookupRateTracker = new EventCounter();

    private static EventCounter indexCacheHitTracker = new EventCounter();

    private static EventCounter indexCacheRequestTracker = new EventCounter();

    private static EventCounter dataCacheHitTracker = new EventCounter();

    private static EventCounter dataCacheRequestTracker = new EventCounter();

    private static volatile boolean fetching = false;

    private static MasterMonitorInfo mmi;

    private static Map<Table.ID, Map<ProblemType, Integer>> problemSummary = Collections.emptyMap();

    private static Exception problemException;

    private static GCStatus gcStatus;

    private static Instance instance;

    private static ServerConfigurationFactory config;

    private static AccumuloServerContext context;

    private static EmbeddedWebServer server;

    private ZooLock monitorLock;

    private static final String DEFAULT_INSTANCE_NAME = "(Unavailable)";

    public static final AtomicReference<String> cachedInstanceName = new AtomicReference<>(DEFAULT_INSTANCE_NAME);

    private static class EventCounter {

        Map<String, Pair<Long, Long>> prevSamples = new HashMap<>();

        Map<String, Pair<Long, Long>> samples = new HashMap<>();

        Set<String> serversUpdated = new HashSet<>();

        void startingUpdates() {
            serversUpdated.clear();
        }

        void updateTabletServer(String name, long sampleTime, long numEvents) {
            Pair<Long, Long> newSample = new Pair<>(sampleTime, numEvents);
            Pair<Long, Long> lastSample = samples.get(name);
            if (lastSample == null || !lastSample.equals(newSample)) {
                samples.put(name, newSample);
                if (lastSample != null) {
                    prevSamples.put(name, lastSample);
                }
            }
            serversUpdated.add(name);
        }

        void finishedUpdating() {
            samples.keySet().retainAll(serversUpdated);
            prevSamples.keySet().retainAll(serversUpdated);
        }

        double calculateRate() {
            double totalRate = 0;
            for (Entry<String, Pair<Long, Long>> entry : prevSamples.entrySet()) {
                Pair<Long, Long> prevSample = entry.getValue();
                Pair<Long, Long> sample = samples.get(entry.getKey());
                totalRate += (sample.getSecond() - prevSample.getSecond()) / ((sample.getFirst() - prevSample.getFirst()) / (double) 1000);
            }
            return totalRate;
        }

        long calculateCount() {
            long count = 0;
            for (Entry<String, Pair<Long, Long>> entry : prevSamples.entrySet()) {
                Pair<Long, Long> prevSample = entry.getValue();
                Pair<Long, Long> sample = samples.get(entry.getKey());
                count += sample.getSecond() - prevSample.getSecond();
            }
            return count;
        }
    }

    public static void fetchData() {
        double totalIngestRate = 0.;
        double totalIngestByteRate = 0.;
        double totalQueryRate = 0.;
        double totalQueryByteRate = 0.;
        double totalScanRate = 0.;
        long totalEntries = 0;
        int totalTabletCount = 0;
        long totalHoldTime = 0;
        long totalLookups = 0;
        boolean retry = true;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRecalc.get() < REFRESH_TIME * 1000) {
            return;
        }
        synchronized (Monitor.class) {
            if (cachedInstanceName.get().equals(DEFAULT_INSTANCE_NAME)) {
                SimpleTimer.getInstance(config.getSystemConfiguration()).schedule(new TimerTask() {

                    @Override
                    public void run() {
                        synchronized (Monitor.class) {
                            if (cachedInstanceName.get().equals(DEFAULT_INSTANCE_NAME)) {
                                final String instanceName = HdfsZooInstance.getInstance().getInstanceName();
                                if (null != instanceName) {
                                    cachedInstanceName.set(instanceName);
                                }
                            }
                        }
                    }
                }, 0);
            }
        }
        synchronized (Monitor.class) {
            if (fetching) {
                return;
            }
            fetching = true;
        }
        try {
            while (retry) {
                MasterClientService.Iface client = null;
                try {
                    client = MasterClient.getConnection(context);
                    if (client != null) {
                        mmi = client.getMasterStats(Tracer.traceInfo(), context.rpcCreds());
                        retry = false;
                    } else {
                        mmi = null;
                    }
                    Monitor.gcStatus = fetchGcStatus();
                } catch (Exception e) {
                    mmi = null;
                    log.info("Error fetching stats: ", e);
                } finally {
                    if (client != null) {
                        MasterClient.close(client);
                    }
                }
                if (mmi == null) {
                    sleepUninterruptibly(1, TimeUnit.SECONDS);
                }
            }
            if (mmi != null) {
                int majorCompactions = 0;
                int minorCompactions = 0;
                lookupRateTracker.startingUpdates();
                indexCacheHitTracker.startingUpdates();
                indexCacheRequestTracker.startingUpdates();
                dataCacheHitTracker.startingUpdates();
                dataCacheRequestTracker.startingUpdates();
                for (TabletServerStatus server : mmi.tServerInfo) {
                    TableInfo summary = TableInfoUtil.summarizeTableStats(server);
                    totalIngestRate += summary.ingestRate;
                    totalIngestByteRate += summary.ingestByteRate;
                    totalQueryRate += summary.queryRate;
                    totalScanRate += summary.scanRate;
                    totalQueryByteRate += summary.queryByteRate;
                    totalEntries += summary.recs;
                    totalHoldTime += server.holdTime;
                    totalLookups += server.lookups;
                    majorCompactions += summary.majors.running;
                    minorCompactions += summary.minors.running;
                    lookupRateTracker.updateTabletServer(server.name, server.lastContact, server.lookups);
                    indexCacheHitTracker.updateTabletServer(server.name, server.lastContact, server.indexCacheHits);
                    indexCacheRequestTracker.updateTabletServer(server.name, server.lastContact, server.indexCacheRequest);
                    dataCacheHitTracker.updateTabletServer(server.name, server.lastContact, server.dataCacheHits);
                    dataCacheRequestTracker.updateTabletServer(server.name, server.lastContact, server.dataCacheRequest);
                }
                lookupRateTracker.finishedUpdating();
                indexCacheHitTracker.finishedUpdating();
                indexCacheRequestTracker.finishedUpdating();
                dataCacheHitTracker.finishedUpdating();
                dataCacheRequestTracker.finishedUpdating();
                int totalTables = 0;
                for (TableInfo tInfo : mmi.tableMap.values()) {
                    totalTabletCount += tInfo.tablets;
                    totalTables++;
                }
                Monitor.totalIngestRate = totalIngestRate;
                Monitor.totalTables = totalTables;
                totalIngestByteRate = totalIngestByteRate / 1000000.0;
                Monitor.totalQueryRate = totalQueryRate;
                Monitor.totalScanRate = totalScanRate;
                totalQueryByteRate = totalQueryByteRate / 1000000.0;
                Monitor.totalEntries = totalEntries;
                Monitor.totalTabletCount = totalTabletCount;
                Monitor.totalHoldTime = totalHoldTime;
                Monitor.totalLookups = totalLookups;
                ingestRateOverTime.add(new Pair<>(currentTime, totalIngestRate));
                ingestByteRateOverTime.add(new Pair<>(currentTime, totalIngestByteRate));
                double totalLoad = 0.;
                for (TabletServerStatus status : mmi.tServerInfo) {
                    if (status != null) {
                        totalLoad += status.osLoad;
                    }
                }
                loadOverTime.add(new Pair<>(currentTime, totalLoad));
                minorCompactionsOverTime.add(new Pair<>(currentTime, minorCompactions));
                majorCompactionsOverTime.add(new Pair<>(currentTime, majorCompactions));
                lookupsOverTime.add(new Pair<>(currentTime, lookupRateTracker.calculateRate()));
                queryRateOverTime.add(new Pair<>(currentTime, (int) totalQueryRate));
                queryByteRateOverTime.add(new Pair<>(currentTime, totalQueryByteRate));
                scanRateOverTime.add(new Pair<>(currentTime, (int) totalScanRate));
                calcCacheHitRate(indexCacheHitRateOverTime, currentTime, indexCacheHitTracker, indexCacheRequestTracker);
                calcCacheHitRate(dataCacheHitRateOverTime, currentTime, dataCacheHitTracker, dataCacheRequestTracker);
            }
            try {
                Monitor.problemSummary = ProblemReports.getInstance(getContext()).summarize();
                Monitor.problemException = null;
            } catch (Exception e) {
                log.info("Failed to obtain problem reports ", e);
                Monitor.problemSummary = Collections.emptyMap();
                Monitor.problemException = e;
            }
        } finally {
            synchronized (Monitor.class) {
                fetching = false;
                lastRecalc.set(currentTime);
            }
        }
    }

    private static void calcCacheHitRate(List<Pair<Long, Double>> hitRate, long currentTime, EventCounter cacheHits, EventCounter cacheReq) {
        long req = cacheReq.calculateCount();
        if (req > 0) {
            hitRate.add(new Pair<>(currentTime, cacheHits.calculateCount() / (double) cacheReq.calculateCount()));
        } else {
            hitRate.add(new Pair<Long, Double>(currentTime, null));
        }
    }

    private static GCStatus fetchGcStatus() {
        GCStatus result = null;
        HostAndPort address = null;
        try {
            ZooReaderWriter zk = ZooReaderWriter.getInstance();
            String path = ZooUtil.getRoot(instance) + Constants.ZGC_LOCK;
            List<String> locks = zk.getChildren(path, null);
            if (locks != null && locks.size() > 0) {
                Collections.sort(locks);
                address = new ServerServices(new String(zk.getData(path + "/" + locks.get(0), null), UTF_8)).getAddress(Service.GC_CLIENT);
                GCMonitorService.Client client = ThriftUtil.getClient(new GCMonitorService.Client.Factory(), address, new AccumuloServerContext(instance, config));
                try {
                    result = client.getStatus(Tracer.traceInfo(), getContext().rpcCreds());
                } finally {
                    ThriftUtil.returnClient(client);
                }
            }
        } catch (Exception ex) {
            log.warn("Unable to contact the garbage collector at " + address, ex);
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        final String app = "monitor";
        ServerOpts opts = new ServerOpts();
        opts.parseArgs(app, args);
        String hostname = opts.getAddress();
        SecurityUtil.serverLogin(SiteConfiguration.getInstance());
        VolumeManager fs = VolumeManagerImpl.get();
        instance = HdfsZooInstance.getInstance();
        config = new ServerConfigurationFactory(instance);
        context = new AccumuloServerContext(instance, config);
        log.info("Version " + Constants.VERSION);
        log.info("Instance " + instance.getInstanceID());
        MetricsSystemHelper.configure(Monitor.class.getSimpleName());
        Accumulo.init(fs, instance, config, app);
        Monitor monitor = new Monitor();
        Monitor.HA_SERVICE_INSTANCE = monitor;
        DistributedTrace.enable(hostname, app, config.getSystemConfiguration());
        try {
            monitor.run(hostname);
        } finally {
            DistributedTrace.disable();
        }
    }

    private static long START_TIME;

    public void run(String hostname) {
        Monitor.START_TIME = System.currentTimeMillis();
        int ports[] = config.getSystemConfiguration().getPort(Property.MONITOR_PORT);
        for (int port : ports) {
            try {
                log.debug("Creating monitor on port {}", port);
                server = new EmbeddedWebServer(hostname, port);
                server.addServlet(getDefaultServlet(), "/resources/*");
                server.addServlet(getRestServlet(), "/rest/*");
                server.addServlet(getViewServlet(), "/*");
                server.start();
                break;
            } catch (Throwable ex) {
                log.error("Unable to start embedded web server", ex);
            }
        }
        if (!server.isRunning()) {
            throw new RuntimeException("Unable to start embedded web server on ports: " + Arrays.toString(ports));
        }
        try {
            getMonitorLock();
        } catch (Exception e) {
            log.error("Failed to get Monitor ZooKeeper lock");
            throw new RuntimeException(e);
        }
        String advertiseHost = hostname;
        if (advertiseHost.equals("0.0.0.0")) {
            try {
                advertiseHost = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                log.error("Unable to get hostname", e);
            }
        }
        log.debug("Using {} to advertise monitor location in ZooKeeper", hostname);
        try {
            String monitorAddress = HostAndPort.fromParts(advertiseHost, server.getPort()).toString();
            ZooReaderWriter.getInstance().putPersistentData(ZooUtil.getRoot(instance) + Constants.ZMONITOR_HTTP_ADDR, monitorAddress.getBytes(UTF_8), NodeExistsPolicy.OVERWRITE);
            log.info("Set monitor address in zookeeper to {}", monitorAddress);
        } catch (Exception ex) {
            log.error("Unable to set monitor HTTP address in zookeeper", ex);
        }
        if (null != advertiseHost) {
            LogService.startLogListener(Monitor.getContext().getConfiguration(), instance.getInstanceID(), advertiseHost);
        } else {
            log.warn("Not starting log4j listener as we could not determine address to use");
        }
        new Daemon(new LoggingRunnable(log, new ZooKeeperStatus()), "ZooKeeperStatus").start();
        new Daemon(new LoggingRunnable(log, new Runnable() {

            @Override
            public void run() {
                while (true) {
                    try {
                        Monitor.fetchData();
                    } catch (Exception e) {
                        log.warn("{}", e.getMessage(), e);
                    }
                    sleepUninterruptibly(333, TimeUnit.MILLISECONDS);
                }
            }
        }), "Data fetcher").start();
        new Daemon(new LoggingRunnable(log, new Runnable() {

            @Override
            public void run() {
                while (true) {
                    try {
                        Monitor.fetchScans();
                    } catch (Exception e) {
                        log.warn("{}", e.getMessage(), e);
                    }
                    sleepUninterruptibly(5, TimeUnit.SECONDS);
                }
            }
        }), "Scan scanner").start();
        monitorInitialized.set(true);
    }

    private ServletHolder getDefaultServlet() {
        return new ServletHolder(new DefaultServlet() {

            private static final long serialVersionUID = 1L;

            @Override
            public Resource getResource(String pathInContext) {
                return Resource.newClassPathResource(pathInContext);
            }
        });
    }

    private ServletHolder getViewServlet() {
        final ResourceConfig rc = new ResourceConfig().packages("org.apache.accumulo.monitor.view").register(new LoggingFeature(java.util.logging.Logger.getLogger(this.getClass().getSimpleName()))).register(FreemarkerMvcFeature.class).property(MvcFeature.TEMPLATE_BASE_PATH, "/templates").property(ServerProperties.BV_SEND_ERROR_IN_RESPONSE, true);
        return new ServletHolder(new ServletContainer(rc));
    }

    private ServletHolder getRestServlet() {
        final ResourceConfig rc = new ResourceConfig().packages("org.apache.accumulo.monitor.rest").register(new LoggingFeature(java.util.logging.Logger.getLogger(this.getClass().getSimpleName()))).register(JacksonFeature.class).property(ServerProperties.BV_SEND_ERROR_IN_RESPONSE, true);
        return new ServletHolder(new ServletContainer(rc));
    }

    public static class ScanStats {

        public final long scanCount;

        public final Long oldestScan;

        public final long fetched;

        ScanStats(List<ActiveScan> active) {
            this.scanCount = active.size();
            long oldest = -1;
            for (ActiveScan scan : active) {
                oldest = Math.max(oldest, scan.age);
            }
            this.oldestScan = oldest < 0 ? null : oldest;
            this.fetched = System.currentTimeMillis();
        }
    }

    static final Map<HostAndPort, ScanStats> allScans = new HashMap<>();

    public static Map<HostAndPort, ScanStats> getScans() {
        synchronized (allScans) {
            return new HashMap<>(allScans);
        }
    }

    public static void fetchScans() throws Exception {
        if (instance == null) {
            return;
        }
        Connector c = context.getConnector();
        for (String server : c.instanceOperations().getTabletServers()) {
            final HostAndPort parsedServer = HostAndPort.fromString(server);
            Client tserver = ThriftUtil.getTServerClient(parsedServer, context);
            try {
                List<ActiveScan> scans = tserver.getActiveScans(null, context.rpcCreds());
                synchronized (allScans) {
                    allScans.put(parsedServer, new ScanStats(scans));
                }
            } catch (Exception ex) {
                log.debug("Failed to get active scans from {}", server, ex);
            } finally {
                ThriftUtil.returnClient(tserver);
            }
        }
        Iterator<Entry<HostAndPort, ScanStats>> entryIter = allScans.entrySet().iterator();
        long now = System.currentTimeMillis();
        while (entryIter.hasNext()) {
            Entry<HostAndPort, ScanStats> entry = entryIter.next();
            if (now - entry.getValue().fetched > 5 * 60 * 1000) {
                entryIter.remove();
            }
        }
    }

    /**
   * Get the monitor lock in ZooKeeper
   */
    private void getMonitorLock() throws KeeperException, InterruptedException {
        final String zRoot = ZooUtil.getRoot(instance);
        final String monitorPath = zRoot + Constants.ZMONITOR;
        final String monitorLockPath = zRoot + Constants.ZMONITOR_LOCK;
        ZooReaderWriter zoo = ZooReaderWriter.getInstance();
        if (zoo.exists(monitorPath)) {
            byte[] data = zoo.getData(monitorPath, null);
            if (0 != data.length) {
                zoo.recursiveDelete(monitorPath, NodeMissingPolicy.SKIP);
                zoo.putPersistentData(monitorPath, new byte[0], NodeExistsPolicy.FAIL);
                zoo.putPersistentData(monitorLockPath, new byte[0], NodeExistsPolicy.FAIL);
            } else {
                if (!zoo.exists(monitorLockPath)) {
                    zoo.putPersistentData(monitorLockPath, new byte[0], NodeExistsPolicy.FAIL);
                }
            }
        } else {
            zoo.putPersistentData(zRoot + Constants.ZMONITOR, new byte[0], NodeExistsPolicy.FAIL);
            if (!zoo.exists(monitorLockPath)) {
                zoo.putPersistentData(monitorLockPath, new byte[0], NodeExistsPolicy.FAIL);
            }
        }
        while (true) {
            MoniterLockWatcher monitorLockWatcher = new MoniterLockWatcher();
            monitorLock = new ZooLock(monitorLockPath);
            monitorLock.lockAsync(monitorLockWatcher, new byte[0]);
            monitorLockWatcher.waitForChange();
            if (monitorLockWatcher.acquiredLock) {
                break;
            }
            if (!monitorLockWatcher.failedToAcquireLock) {
                throw new IllegalStateException("monitor lock in unknown state");
            }
            monitorLock.tryToCancelAsyncLockOrUnlock();
            sleepUninterruptibly(getContext().getConfiguration().getTimeInMillis(Property.MONITOR_LOCK_CHECK_INTERVAL), TimeUnit.MILLISECONDS);
        }
        log.info("Got Monitor lock.");
    }

    /**
   * Async Watcher for monitor lock
   */
    private static class MoniterLockWatcher implements ZooLock.AsyncLockWatcher {

        boolean acquiredLock = false;

        boolean failedToAcquireLock = false;

        @Override
        public void lostLock(LockLossReason reason) {
            Halt.halt("Monitor lock in zookeeper lost (reason = " + reason + "), exiting!", -1);
        }

        @Override
        public void unableToMonitorLockNode(final Throwable e) {
            Halt.halt(-1, new Runnable() {

                @Override
                public void run() {
                    log.error("No longer able to monitor Monitor lock node", e);
                }
            });
        }

        @Override
        public synchronized void acquiredLock() {
            if (acquiredLock || failedToAcquireLock) {
                Halt.halt("Zoolock in unexpected state AL " + acquiredLock + " " + failedToAcquireLock, -1);
            }
            acquiredLock = true;
            notifyAll();
        }

        @Override
        public synchronized void failedToAcquireLock(Exception e) {
            log.warn("Failed to get monitor lock " + e);
            if (acquiredLock) {
                Halt.halt("Zoolock in unexpected state FAL " + acquiredLock + " " + failedToAcquireLock, -1);
            }
            failedToAcquireLock = true;
            notifyAll();
        }

        public synchronized void waitForChange() {
            while (!acquiredLock && !failedToAcquireLock) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    public static MasterMonitorInfo getMmi() {
        return mmi;
    }

    public static int getTotalTables() {
        return totalTables;
    }

    public static int getTotalTabletCount() {
        return totalTabletCount;
    }

    public static long getTotalEntries() {
        return totalEntries;
    }

    public static double getTotalIngestRate() {
        return totalIngestRate;
    }

    public static double getTotalQueryRate() {
        return totalQueryRate;
    }

    public static double getTotalScanRate() {
        return totalScanRate;
    }

    public static long getTotalHoldTime() {
        return totalHoldTime;
    }

    public static Exception getProblemException() {
        return problemException;
    }

    public static Map<Table.ID, Map<ProblemType, Integer>> getProblemSummary() {
        return problemSummary;
    }

    public static GCStatus getGcStatus() {
        return gcStatus;
    }

    public static long getTotalLookups() {
        return totalLookups;
    }

    public static long getStartTime() {
        return START_TIME;
    }

    public static List<Pair<Long, Double>> getLoadOverTime() {
        return new ArrayList<>(loadOverTime);
    }

    public static List<Pair<Long, Double>> getIngestRateOverTime() {
        return new ArrayList<>(ingestRateOverTime);
    }

    public static List<Pair<Long, Double>> getIngestByteRateOverTime() {
        return new ArrayList<>(ingestByteRateOverTime);
    }

    public static List<Pair<Long, Integer>> getMinorCompactionsOverTime() {
        return new ArrayList<>(minorCompactionsOverTime);
    }

    public static List<Pair<Long, Integer>> getMajorCompactionsOverTime() {
        return new ArrayList<>(majorCompactionsOverTime);
    }

    public static List<Pair<Long, Double>> getLookupsOverTime() {
        return new ArrayList<>(lookupsOverTime);
    }

    public static double getLookupRate() {
        return lookupRateTracker.calculateRate();
    }

    public static List<Pair<Long, Integer>> getQueryRateOverTime() {
        return new ArrayList<>(queryRateOverTime);
    }

    public static List<Pair<Long, Integer>> getScanRateOverTime() {
        return new ArrayList<>(scanRateOverTime);
    }

    public static List<Pair<Long, Double>> getQueryByteRateOverTime() {
        return new ArrayList<>(queryByteRateOverTime);
    }

    public static List<Pair<Long, Double>> getIndexCacheHitRateOverTime() {
        return new ArrayList<>(indexCacheHitRateOverTime);
    }

    public static List<Pair<Long, Double>> getDataCacheHitRateOverTime() {
        return new ArrayList<>(dataCacheHitRateOverTime);
    }

    public static AccumuloServerContext getContext() {
        return context;
    }

    @Override
    public boolean isActiveService() {
        return monitorInitialized.get();
    }
}
