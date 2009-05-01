/* 
 * Copyright (c) 2007-2008, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.impl;

import com.hazelcast.impl.BaseManager.PacketProcessor;
import com.hazelcast.impl.BaseManager.Processable;
import com.hazelcast.nio.PacketQueue;
import com.hazelcast.nio.PacketQueue.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClusterService implements Runnable, Constants {
    protected static Logger logger = Logger.getLogger(ClusterService.class.getName());

    private static final ClusterService instance = new ClusterService();

    private static final long PERIODIC_CHECK_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    private static final long UTILIZATION_CHECK_INTERVAL = TimeUnit.SECONDS.toNanos(10);

    protected final boolean DEBUG = Build.DEBUG;

    protected final BlockingQueue queue;

    protected volatile boolean running = true;

    protected final List lsBuffer = new ArrayList(2000);

    protected long start = 0;

    protected long totalProcessTime = 0;

    protected long lastPeriodicCheck = 0;

    private final BaseManager.PacketProcessor[] packetProcessors = new BaseManager.PacketProcessor[300];

    private ClusterService() {
        this.queue = new LinkedBlockingQueue();
    }

    public static ClusterService get() {
        return instance;
    }

    void registerPacketProcessor(int operation, BaseManager.PacketProcessor packetProcessor) {
        if (packetProcessors[operation] != null) {
            logger.log(Level.SEVERE, operation + " is registered already with "
                    + packetProcessors[operation]);
        }
        packetProcessors[operation] = packetProcessor;
    }

    public void enqueueAndReturn(final Object message) {
        try {
            queue.put(message);
        } catch (final InterruptedException e) {
            Node.get().handleInterruptedException(Thread.currentThread(), e);
        }
    }

    public void process(final Object obj) {
        if (!running) return;
        final long processStart = System.nanoTime();
        if (obj instanceof PacketQueue.Packet) {
            final Packet packet = (Packet) obj;
            final MemberImpl memberFrom = ClusterManager.get().getMember(packet.conn.getEndPoint());
            if (memberFrom != null) {
                memberFrom.didRead();
            }
            if (packet.operation < 0 || packet.operation >= packetProcessors.length) {
                logger.log(Level.SEVERE, "Unknown operation " + packet.operation);
                return;
            }
            PacketProcessor packetProcessor = packetProcessors[packet.operation];
            if (packetProcessor == null) {
                logger.log(Level.SEVERE, "No Packet processor found for operation : "
                        + packet.operation);
            }
            packetProcessor.process(packet);
        } else if (obj instanceof Processable) {
            ((Processable) obj).process();
        } else {
            logger.log(Level.SEVERE, "Cannot process. Unknown object: " + obj);
        }
        final long processEnd = System.nanoTime();
        final long elipsedTime = processEnd - processStart;
        totalProcessTime += elipsedTime;
        final long duration = (processEnd - start);
//		if (duration > UTILIZATION_CHECK_INTERVAL) {
//			if (DEBUG) {
//				logger.log(Level.FINEST, "ServiceProcessUtilization: "
//						+ ((totalProcessTime * 100) / duration) + " %");
//			}
//			start = processEnd;
//			totalProcessTime = 0;
//		}
    }

    public void run() {
        while (running) {
            Object obj = null;
            try {
                lsBuffer.clear();
                queue.drainTo(lsBuffer);
                final int size = lsBuffer.size();
                if (size > 0) {
                    for (int i = 0; i < size; i++) {
                        obj = lsBuffer.get(i);
                        checkPeriodics();
                        process(obj);
                    }
                    lsBuffer.clear();
                } else {
                    obj = queue.poll(100, TimeUnit.MILLISECONDS);
                    checkPeriodics();
                    if (obj != null) {
                        process(obj);
                    }
                }
            } catch (final InterruptedException e) {
                Node.get().handleInterruptedException(Thread.currentThread(), e);
            } catch (final Throwable e) {
                logger.log(Level.FINEST, e + ",  message: " + e + ", obj=" + obj, e);
                e.printStackTrace();
                System.out.println("Exception when handling " + obj);
            }
        }
        lsBuffer.clear();
        queue.clear();
    }

    public void run3() {
        Object obj = null;
        while (running) {
            try {
                obj = queue.take();
                process(obj);
            } catch (final InterruptedException e) {
                Node.get().handleInterruptedException(Thread.currentThread(), e);
            } catch (final Exception e) {
                logger.log(Level.FINEST, e + ",  message: " + e.getMessage() + "  obj=" + obj, e);
            }
        }
    }

    public void start() {
        totalProcessTime = 0;
        lastPeriodicCheck = 0;
        start = System.nanoTime();
        running = true;
    }

    public void stop() {
        running = false;
    }

    @Override
    public String toString() {
        return "ClusterService queueSize=" + queue.size() + " master= " + Node.get().master()
                + " master= " + Node.get().getMasterAddress();
    }

    private void checkPeriodics() {
        final long now = System.nanoTime();
        if ((now - lastPeriodicCheck) > PERIODIC_CHECK_INTERVAL) {
            ClusterManager.get().heartBeater();
            ClusterManager.get().checkScheduledActions();
            lastPeriodicCheck = now;
        }
    }

}
