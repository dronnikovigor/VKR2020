package one.transport.ut2.testing.tunnel;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import one.transport.ut2.testing.entity.Configuration;
import one.transport.ut2.testing.tunnel.jni.Tunnel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

public class TunnelInterface {
    public final Statistic statistic = new Statistic();
    @NotNull
    private final CopyOnWriteArrayList<Configuration.Device> bannedDevices = new CopyOnWriteArrayList<>();
    private final Deque<PacketWithTimestamp> delayedPackets = new ConcurrentLinkedDeque<>();
    @NotNull
    private final Tunnel tunnel;
    @NotNull
    private final Configuration.Device[] devices;
    /* caches */
    private final Map<Configuration.Device, DevicePacketsCache> recvFromClientsCaches = new HashMap<>();
    private final Map<Configuration.Device, DevicePacketsCache> recvFromServersCaches = new HashMap<>();
    public int bandwidth;
    public int speed;
    public double speedRate;
    public PacketLoss.LossParams lossParams;
    /* threads */
    @Nullable
    private ReceivingThread receivingThread = null;
    @Nullable
    private SendingThread sendingThread = null;

    public TunnelInterface(@NotNull Tunnel tunnel, @NotNull Configuration.Device[] devices) {
        this.tunnel = tunnel;
        this.devices = devices;
    }

    private double calcDelay() {
        return (double) bandwidth / 2;
    }

    public Statistic flushStat() {
        Statistic copy = new Statistic(this.statistic);
        statistic.clear();
        return copy;
    }

    private void initDeviceCaches() {
        for (Configuration.Device device : devices) {
            //creating buckets only for clients
            if (device.type == Configuration.Device.Type.CLIENT) {
                recvFromClientsCaches.put(device, new DevicePacketsCache((int) (speed * (1 - speedRate))));
                recvFromServersCaches.put(device, new DevicePacketsCache((int) (speed * speedRate)));
            }
        }
    }

    private void processPacket(PacketWithTimestamp packetWithTimestamp) {
        long timestamp = packetWithTimestamp.timestamp;
        Packet packet = packetWithTimestamp.packet;

        for (Configuration.Device bannedDevice : bannedDevices) {
            if (bannedDevice.isDstOrSrc(packet)) {
                //shouldn't send packet to device
                return;
            }
        }

        Configuration.Device srcDevice = null;
        Configuration.Device dstDevice = null;
        for (Configuration.Device device : devices) {
            if (device.isSrc(packet)) {
                srcDevice = device;
            }
            if (device.isDst(packet)) {
                dstDevice = device;
            }
        }

        if (srcDevice == null || dstDevice == null) {
            return;
        }

        /* collecting UDP stat */
        if (packet.protocol == Packet.Protocol.UDP) {
            final int srcId = packet.getSrcAddress()[3] & 0xFF;
            final String magic = packet.getMagic();

            statistic.clients.ut2PacketsStat.compute(srcId, (key, map) -> {
                if (map == null) {
                    map = new HashMap<>();
                }
                map.compute(magic, (magicKey, counter) -> counter == null ? 1 : counter + 1);
                return map;
            });
        }

        if (srcDevice.type == Configuration.Device.Type.CLIENT) {
            /* adding to sending deque of client device */
            statistic.clients.recvFromClients++;
            DevicePacketsCache cache = recvFromClientsCaches.get(srcDevice);
            cache.pendingPackets.add(packet);
        } else if (dstDevice.type == Configuration.Device.Type.CLIENT) {
            /* adding to receiving deque of client device */
            statistic.servers.recvFromServers++;
            DevicePacketsCache cache = recvFromServersCaches.get(dstDevice);
            cache.pendingPackets.add(packet);
        } else {
            /* from server to server packets delivered without delay */
            statistic.servers.betweenServers++;
            trySendPacketToDst(packet, dstDevice);
        }
    }

    private void trySendPacket(Packet packet) {
        for (Configuration.Device device : devices) {
            if (trySendPacketToDst(packet, device)) {
                break;
            }
        }
    }

    private boolean trySendPacketToDst(Packet packet, Configuration.Device dstDevice) {
        if (dstDevice.apply(packet)) {
            if (dstDevice.type == Configuration.Device.Type.CLIENT)
                statistic.servers.sentToClients++;
            else
                statistic.clients.sentToServers++;
            tunnel.writePacket(packet.getData());
            return true;
        }
        return false;
    }

    public void start() {
        statistic.init();

        initDeviceCaches();

        if (receivingThread != null || sendingThread != null) {
            throw new IllegalStateException("Interface has been already started");
        }
        receivingThread = new ReceivingThread();
        sendingThread = new SendingThread();


        receivingThread.start();
        sendingThread.start();
    }

    public void stop() {
        if (receivingThread == null || sendingThread == null) {
            throw new IllegalStateException("Interface isn't running");
        }
        receivingThread.interrupt();
        sendingThread.interrupt();

        try {
            receivingThread.join();
            receivingThread = null;
        } catch (InterruptedException e) {
            //todo log
            e.printStackTrace();
        }
        try {
            sendingThread.join();
            sendingThread = null;
        } catch (InterruptedException e) {
            //todo log
            e.printStackTrace();
        }
    }

    private static class DevicePacketsCache {
        /* receiving thread adding packets to this deque and sending thread extracting */
        final Deque<Packet> pendingPackets = new ConcurrentLinkedDeque<>();
        final Bucket bucket;

        DevicePacketsCache(int rate) {
            Bandwidth limit = Bandwidth.simple(rate, Duration.ofSeconds(1));
            bucket = Bucket4j.builder().addLimit(limit).build();
        }

    }

    private static class PacketWithTimestamp {
        final Packet packet;
        final long timestamp;

        private PacketWithTimestamp(Packet packet, long timestamp) {
            this.packet = packet;
            this.timestamp = timestamp;
        }
    }

    public static class Statistic {

        public Clients clients;
        public Servers servers;

        Statistic() {
        }

        Statistic(Statistic stats) {
            this.clients = new Clients(stats.clients);
            this.servers = new Servers(stats.servers);
        }

        void init() {
            this.clients = new Clients();
            this.servers = new Servers();
        }

        void clear() {
            this.clients.clear();
            this.servers.clear();
        }

        public static class Clients {
            long recvFromClients;
            long processedFromClients;
            long sentToServers;
            final Map<Integer, HashMap<String, Integer>> ut2PacketsStat;

            Clients() {
                this.ut2PacketsStat = new HashMap<>();
            }

            Clients(Clients clients) {
                this.recvFromClients = clients.recvFromClients;
                this.processedFromClients = clients.processedFromClients;
                this.sentToServers = clients.sentToServers;

                this.ut2PacketsStat = new HashMap<>(clients.ut2PacketsStat);
            }

            void clear() {
                recvFromClients = 0;
                processedFromClients = 0;
                sentToServers = 0;
                ut2PacketsStat.clear();
            }

            public Double getPacketLoss() {
                return BigDecimal.valueOf(100 - (double) sentToServers / recvFromClients * 100)
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();
            }
        }

        public static class Servers {
            long recvFromServers;
            long processedFromServers;
            long sentToClients;

            long betweenServers;

            Servers() {
            }

            Servers(Servers servers) {
                this.recvFromServers = servers.recvFromServers;
                this.processedFromServers = servers.processedFromServers;
                this.sentToClients = servers.sentToClients;
                this.betweenServers = servers.betweenServers;
            }

            void clear() {
                recvFromServers = 0;
                processedFromServers = 0;
                sentToClients = 0;

                betweenServers = 0;
            }

            public double getPacketLoss() {
                return BigDecimal.valueOf(100 - (double) sentToClients / recvFromServers * 100)
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();
            }
        }
    }

    class ReceivingThread extends Thread {
        @Override
        public void run() {
            while (!isInterrupted()) {
                byte[] bytes = tunnel.readPacket(1000);

                if (bytes != null) {
                    Packet packet = new Packet(bytes);
                    delayedPackets.add(new PacketWithTimestamp(packet, System.currentTimeMillis()));
                }
            }
        }

    }

    class SendingThread extends Thread {
        final PacketLoss sendToClientsLF = new PacketLoss(lossParams);
        final PacketLoss sendToServersLF = new PacketLoss(lossParams);

        @Override
        public void run() {
            while (!isInterrupted()) {
                for (PacketWithTimestamp packetWithTimestamp = delayedPackets.peek();
                     packetWithTimestamp != null && System.currentTimeMillis() - packetWithTimestamp.timestamp
                             > calcDelay();
                     packetWithTimestamp = delayedPackets.peek()) {
                    processPacket(packetWithTimestamp);
                    delayedPackets.pop();
                }

                for (Map.Entry<Configuration.Device, DevicePacketsCache> entry : recvFromClientsCaches.entrySet()) {
                    DevicePacketsCache packetsCache = entry.getValue();
                    Deque<Packet> pendingPackets = packetsCache.pendingPackets;
                    while (pendingPackets.size() > 0) {
                        Packet packet = pendingPackets.peek();
                        //ip header starts from 4th byte
                        if (packetsCache.bucket.tryConsume(packet.getLength() - 4)) {
                            statistic.clients.processedFromClients++;
                            if (sendToServersLF.loss(statistic.clients.processedFromClients))
                                pendingPackets.pop();
                            else {
                                pendingPackets.pop();
                                trySendPacket(packet);
                            }
                        } else {
                            break;
                        }
                    }
                }

                for (Map.Entry<Configuration.Device, DevicePacketsCache> entry : recvFromServersCaches.entrySet()) {
                    Configuration.Device dstDevice = entry.getKey();
                    DevicePacketsCache packetsCache = entry.getValue();
                    Deque<Packet> pendingPackets = packetsCache.pendingPackets;
                    while (pendingPackets.size() > 0) {
                        Packet packet = pendingPackets.peek();
                        //ip header starts from 4th byte
                        if (packetsCache.bucket.tryConsume(packet.getLength() - 4)) {
                            statistic.servers.processedFromServers++;
                            if (sendToClientsLF.loss(statistic.servers.processedFromServers))
                                pendingPackets.pop();
                            else {
                                pendingPackets.pop();
                                trySendPacketToDst(packet, dstDevice);
                            }
                        } else {
                            break;
                        }
                    }
                }
            }
        }
    }
}
