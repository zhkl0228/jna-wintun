package info.skyblond.wintun;

import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.platform.win32.WinError;
import info.skyblond.jna.iphlp.ExtendedIPHlpAPI;
import info.skyblond.jna.iphlp.MIB_IPFORWARD_ROW2;
import info.skyblond.jna.iphlp.SocketAddrIn;
import info.skyblond.jna.iphlp.SocketAddrIn6;
import info.skyblond.jna.wintun.NativeException;
import info.skyblond.jna.wintun.WintunAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class VpnWintunAdapter extends WintunAdapter {

    public static VpnWintunAdapter createVpnAdapter() {
        String guid = Guid.GUID.newGuid().toGuidString();
        return new VpnWintunAdapter("Wintun", "Wintun", guid);
    }

    private VpnWintunAdapter(@NotNull String name, @Nullable String type, @Nullable String guid) {
        super(name, type, guid);
    }

    public void addRoute(@NotNull InetAddress address, int prefixLength) throws NativeException {
        MIB_IPFORWARD_ROW2 row = new MIB_IPFORWARD_ROW2();
        ExtendedIPHlpAPI.getINSTANCE().InitializeIpForwardEntry(row);
        row.InterfaceLuid = getLuid();
        row.DestinationPrefix.PrefixLength = (byte) prefixLength;
        if (address instanceof Inet4Address) {
            row.DestinationPrefix.Prefix.setType(SocketAddrIn.class);
            row.DestinationPrefix.Prefix.Ipv4.sin_family = IPHlpAPI.AF_INET;
            row.DestinationPrefix.Prefix.Ipv4.sin_port = 0;
            row.DestinationPrefix.Prefix.Ipv4.sin_addr = address.getAddress();
        } else if(address instanceof Inet6Address) {
            row.DestinationPrefix.Prefix.setType(SocketAddrIn6.class);
            row.DestinationPrefix.Prefix.Ipv6.sin6_family = IPHlpAPI.AF_INET6;
            row.DestinationPrefix.Prefix.Ipv6.sin6_port = 0;
            row.DestinationPrefix.Prefix.Ipv6.sin6_addr = address.getAddress();
        } else {
            throw new IllegalArgumentException("Unknown IP address: " + address);
        }
        row.SitePrefixLength = 0;
        row.Metric = 0;
        int err = ExtendedIPHlpAPI.getINSTANCE().CreateIpForwardEntry2(row);
        if (err != WinError.NO_ERROR) {
            throw new NativeException("Failed add route", err);
        }
    }

    public void setDefaultAdapter() throws NativeException {
        try {
            addRoute(Inet4Address.getByName("0.0.0.0"), 0);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("setDefaultAdapter", e);
        }
    }

}
