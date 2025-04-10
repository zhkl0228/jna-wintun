package info.skyblond.jna;

import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.IPHlpAPI;
import info.skyblond.jna.wintun.AdapterIPAddress;
import info.skyblond.jna.wintun.WintunAdapter;
import info.skyblond.jna.wintun.WintunLib;
import info.skyblond.jna.wintun.WintunSession;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV6Packet;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * route delete 0.0.0.0
 * route add 0.0.0.0 mask 0.0.0.0 10.1.10.1 metric 5
 * route add <server ip> mask 255.255.255.255 <primary gateway ip>
 */
public class WintunDemo {

    public static void main(String[] args) {
        System.out.printf("Current wintun version: %s%n", WintunLib.getINSTANCE().WintunGetRunningDriverVersion());

        String guid = Guid.GUID.newGuid().toGuidString();
        try (WintunAdapter adapter = new WintunAdapter("Wintun", "Wintun", guid)) {
            adapter.setMTU(IPHlpAPI.AF_INET, 1500);

            InetAddress ip = Inet4Address.getByName("10.1.10.1");
            System.out.printf("Set ip to: %s%n", ip);
            adapter.associateIp(ip, 24);

            for(AdapterIPAddress address : adapter.listAssociatedAddresses(IPHlpAPI.AF_INET6)) {
                System.out.printf("Associated address: %s%n", address.getIp());
                adapter.dissociateIp(address.getIp());
            }

            try (WintunSession session = adapter.newSession(0x800000)) {
                System.out.printf("New session: %s, mtu=%s%n", session, adapter.getMTU(IPHlpAPI.AF_INET));
                while(true) {
                    byte[] packet = session.readPacket();
                    if(packet == null) {
                        continue;
                    }
                    if(packet.length == 0) {
                        break;
                    }
                    int b = packet[0] & 0xf0;
                    boolean isV6 = b == 0x60;
                    System.out.printf("Get IPv%d packet from OS%n\tSize: %d bytes%n", isV6 ? 6 : 4, packet.length);
                    if (isV6) {
                        IpV6Packet v6Packet = IpV6Packet.newPacket(packet, 0, packet.length);
                        System.out.println(v6Packet);
                    } else {
                        IpV4Packet v4Packet = IpV4Packet.newPacket(packet, 0, packet.length);
                        System.out.println(v4Packet);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

}
