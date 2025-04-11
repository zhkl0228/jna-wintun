package info.skyblond.jna.iphlp;

import com.sun.jna.Structure;

@Structure.FieldOrder({"InterfaceLuid", "InterfaceIndex",
        "DestinationPrefix", "NextHop", "SitePrefixLength",
        "ValidLifetime", "PreferredLifetime", "Metric",
        "Protocol", "Loopback", "AutoconfigureAddress",
        "Publish", "Immortal", "Age", "Origin"})
public class MIB_IPFORWARD_ROW2 extends Structure {
    public long InterfaceLuid;
    public int InterfaceIndex;
    public IP_ADDRESS_PREFIX DestinationPrefix;
    public SocketAddrINET NextHop;
    public int SitePrefixLength;
    public int ValidLifetime;
    public int PreferredLifetime;
    public int Metric;
    public int Protocol;
    public byte Loopback;
    public byte AutoconfigureAddress;
    public byte Publish;
    public byte Immortal;
    public int Age;
    public int Origin;
}
