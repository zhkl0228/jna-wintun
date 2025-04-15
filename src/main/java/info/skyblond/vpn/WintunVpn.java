package info.skyblond.vpn;

import com.sun.jna.platform.win32.IPHlpAPI;
import info.skyblond.jna.wintun.AdapterIPAddress;
import info.skyblond.jna.wintun.NativeException;
import info.skyblond.jna.wintun.WintunSession;
import info.skyblond.wintun.VpnWintunAdapter;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * WintunVpn 192.168.1.28 20240
 */
public class WintunVpn {

    private static final String VPN_CLIENT_IP = "10.1.10.1";
    private static final int MTU = 10000;
    private static final byte VPN_MAGIC = 0xe;

    private class StreamForward implements Runnable {
        private final DataInput dataInput;
        private final WintunSession session;
        private StreamForward(DataInput dataInput, WintunSession session) {
            this.dataInput = dataInput;
            this.session = session;
        }
        @Override
        public void run() {
            try {
                byte[] packet = new byte[MTU];
                while (!canStop) {
                    int length = dataInput.readUnsignedShort();
                    if (length > MTU) {
                        throw new IOException("Packet too long: " + length);
                    }
                    dataInput.readFully(packet, 0, length);
                    if(length > 0) {
                        for (int i = 0; i < length; i++) {
                            packet[i] ^= VPN_MAGIC;
                        }
                        session.writePacket(packet, 0, length);
                    }
                }
            } catch(SocketException ignored) {
            } catch(Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    private boolean canStop;

    public void start() {
        if(canStop) {
            throw new IllegalStateException("Can't start VPN after stop");
        }
        Thread thread = new Thread(() -> {
            try (VpnWintunAdapter adapter = VpnWintunAdapter.createVpnAdapter()) {
                adapter.setMTU(IPHlpAPI.AF_INET, MTU);
                InetAddress ip = Inet4Address.getByName(VPN_CLIENT_IP);
                adapter.associateIp(ip, 24);
                for(AdapterIPAddress ipAddress : adapter.listAssociatedAddresses(IPHlpAPI.AF_INET6)) {
                    adapter.dissociateIp(ipAddress.getIp());
                }
                startNative(adapter);
                System.out.println("VPN exited");
            } catch(Exception e) {
                e.printStackTrace(System.err);
            }
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public void stop() {
        canStop = true;
        if (vpnSocket != null) {
            try {
                vpnSocket.close();
                vpnSocket = null;
            } catch (IOException ignored) {
            }
        }
    }

    private Socket vpnSocket;

    private void startNative(VpnWintunAdapter adapter) throws IOException, NativeException {
        try (Socket socket = new Socket();
             WintunSession session = adapter.newSession(0x800000)) {
            long start = System.currentTimeMillis();
            socket.connect(vpnServer, 15000);
            vpnSocket = socket;
            configAdapter(adapter);
            System.out.printf("Connected to %s in %dms%n", vpnServer, System.currentTimeMillis() - start);
            try (InputStream inputStream = socket.getInputStream();
                 OutputStream outputStream = socket.getOutputStream()) {
                DataOutput output = new DataOutputStream(outputStream);
                int osType = 0x3;
                if (configData != null) {
                    osType |= 0x80;
                }
                output.writeByte(osType);
                if (configData != null) {
                    Locale locale = Locale.getDefault();
                    try(ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        Properties properties = new Properties();
                        properties.put("locale", locale.toString());
                        properties.put("language", locale.getLanguage());
                        properties.put("country", locale.getCountry());
                        properties.put("config", configData);
                        properties.store(baos, "Vpn config properties");
                        String config = baos.toString("UTF-8");
                        output.writeUTF(config);
                    }
                }
                Thread thread = new Thread(new StreamForward(new DataInputStream(inputStream), session));
                thread.start();
                while (!canStop) {
                    byte[] packet = session.readPacket(100);
                    if(packet == null) {
                        Thread.yield();
                        continue;
                    }
                    if(packet.length == 0) {
                        break;
                    }
                    output.writeShort(packet.length);
                    for(int i = 0; i < packet.length; i++) {
                        packet[i] ^= VPN_MAGIC;
                    }
                    output.write(packet, 0, packet.length);
                    outputStream.flush();
                }
            } catch(SocketException ignored) {}
        } finally {
            canStop = true;
        }
    }

    private void configAdapter(VpnWintunAdapter adapter) {
        // Exclude IP ranges
        List<IPUtil.CIDR> listExclude = new ArrayList<>();

        {
            InetAddress address = vpnServer.getAddress();
            IPUtil.CIDR local = new IPUtil.CIDR(address, address.getAddress().length * 8);
            listExclude.add(local);
        }

        // DNS address
        for (InetAddress dns : getDns()) {
            if (dns instanceof Inet4Address) {
//                adapter.addDnsServer(dns);
                listExclude.add(new IPUtil.CIDR(dns.getHostAddress(), 24));
            }
        }

        listExclude.add(new IPUtil.CIDR("127.0.0.0", 8)); // localhost

        // USB tethering 192.168.42.x
        // Wi-Fi tethering 192.168.43.x
        listExclude.add(new IPUtil.CIDR("192.168.42.0", 23));
        // Wi-Fi direct 192.168.49.x
        listExclude.add(new IPUtil.CIDR("192.168.49.0", 24));

        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni != null && ni.isUp() && !ni.isLoopback() &&
                        ni.getName() != null && !ni.getName().startsWith("tun"))
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (ia.getAddress() instanceof Inet4Address) {
                            IPUtil.CIDR local = new IPUtil.CIDR(ia.getAddress(), ia.getNetworkPrefixLength());
                            listExclude.add(local);
                        }
                    }
            }
        } catch (SocketException ignored) {
        }

        // Broadcast
        listExclude.add(new IPUtil.CIDR("224.0.0.0", 3));

        Collections.sort(listExclude);

        try {
            InetAddress start = InetAddress.getByName("0.0.0.0");
            for (IPUtil.CIDR exclude : listExclude) {
                for (IPUtil.CIDR include : IPUtil.toCIDR(start, IPUtil.minus1(exclude.getStart()))) {
                    try {
                        adapter.addRoute(include.address, include.prefix);
                    } catch (Throwable ex) {
                        ex.printStackTrace(System.err);
                    }
                }
                start = IPUtil.plus1(exclude.getEnd());
            }
            for (IPUtil.CIDR include : IPUtil.toCIDR("224.0.0.0", "255.255.255.255"))
                try {
                    adapter.addRoute(include.address, include.prefix);
                } catch (Throwable ex) {
                    ex.printStackTrace(System.err);
                }
        } catch (UnknownHostException ex) {
            ex.printStackTrace(System.err);
        }
    }

    private Collection<? extends InetAddress> getDns() {
        return Collections.emptyList();
    }

    private final InetSocketAddress vpnServer;
    private final byte[] configData;

    public WintunVpn(String host, int port) {
        this(new InetSocketAddress(host, port));
    }

    public WintunVpn(InetSocketAddress vpnServer) {
        this(vpnServer, null);
    }

    public WintunVpn(InetSocketAddress vpnServer, byte[] configData) {
        this.vpnServer = vpnServer;
        this.configData = configData;
    }

}
