package info.skyblond.jna.iphlp;

import com.sun.jna.Structure;

@Structure.FieldOrder({"Prefix", "PrefixLength"})
public class IP_ADDRESS_PREFIX extends Structure {
    public SocketAddrINET Prefix;
    public byte PrefixLength;
}
