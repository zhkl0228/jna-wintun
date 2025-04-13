package info.skyblond.vpn;

import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.IPHlpAPI;
import info.skyblond.jna.wintun.AdapterIPAddress;
import info.skyblond.jna.wintun.NativeException;
import info.skyblond.jna.wintun.WintunAdapter;
import info.skyblond.jna.wintun.WintunSession;

import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.Properties;

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
            String guid = Guid.GUID.newGuid().toGuidString();
            try (WintunAdapter adapter = new WintunAdapter("Wintun", "Wintun", guid)) {
                adapter.setMTU(IPHlpAPI.AF_INET, MTU);
                InetAddress ip = Inet4Address.getByName(VPN_CLIENT_IP);
                adapter.associateIp(ip, 24);
                for(AdapterIPAddress ipAddress : adapter.listAssociatedAddresses(IPHlpAPI.AF_INET6)) {
                    adapter.dissociateIp(ipAddress.getIp());
                }
                startNative(adapter);
            } catch(Exception e) {
                e.printStackTrace(System.err);
            }
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public void stop() {
        canStop = true;
    }

    private void startNative(WintunAdapter adapter) throws IOException, NativeException {
        try (Socket socket = new Socket();
             WintunSession session = adapter.newSession(0x800000)) {
            socket.connect(vpnServer, 15000);
            System.out.println("Connected to " + vpnServer);
            adapter.setDefaultAdapter();
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
                    byte[] packet = session.readPacket();
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
            }
        } finally {
            canStop = true;
        }
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
