package info.skyblond.vpn;

import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.IPHlpAPI;
import info.skyblond.jna.wintun.AdapterIPAddress;
import info.skyblond.jna.wintun.NativeException;
import info.skyblond.jna.wintun.WintunAdapter;
import info.skyblond.jna.wintun.WintunSession;

import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * WintunVpn 192.168.1.28 20240
 */
public class WintunVpn implements Runnable {

    private static final String VPN_CLIENT_IP = "10.1.10.1";
    private static final int MTU = 10000;
    private static final byte VPN_MAGIC = 0xe;

    public static void main(String[] args) {
        if(args.length < 2) {
            System.err.println("WintunVpn host port");
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        try {
            WintunVpn vpn = new WintunVpn(new InetSocketAddress(Inet4Address.getByName(host), port));
            Thread thread = new Thread(vpn, "WintunVpn");
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();

            Scanner scanner = new Scanner(System.in);
            String line;
            while((line = scanner.nextLine()) != null) {
                if ("q".equalsIgnoreCase(line) ||
                        "quit".equalsIgnoreCase(line) ||
                        "exit".equalsIgnoreCase(line)) {
                    vpn.canStop = true;
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    @Override
    public void run() {
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
    }

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

    private void startNative(WintunAdapter adapter) throws IOException, NativeException {
        try (Socket socket = new Socket();
             WintunSession session = adapter.newSession(0x800000)) {
            socket.connect(vpnServer, 15000);
            try (InputStream inputStream = socket.getInputStream();
                 OutputStream outputStream = socket.getOutputStream()) {
                DataOutput output = new DataOutputStream(outputStream);
                int osType = 0x3;
                output.writeByte(osType);
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

    private WintunVpn(InetSocketAddress vpnServer) {
        this.vpnServer = vpnServer;
    }

}
