package info.skyblond.jna.wintun

import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.IPHlpAPI
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinError
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import info.skyblond.jna.iphlp.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Represent a Wintun adapter.
 * Create or open the tun device when initializing.
 *
 * Note: You need run as admin to create tun.
 *
 * NOT thread-safe.
 * */
class WintunAdapter(
    /**
     * The name of the tun adapter
     * */
    val name: String,
    /**
     * The type of the tun adapter.
     * Null for open existing one.
     * Required when creating new adapter.
     * */
    type: String?,
    /**
     * The guid of the tun device.
     * Required when creating new adapter.
     * Null for system decide, aka the GUID is chosen by the system at random,
     * and hence a new NLA entry is created for each new adapter.
     * */
    guid: String? = null
) : AutoCloseable {

    private val wintunLib: WintunLib = WintunLib.INSTANCE
    private val ipHelperLib: ExtendedIPHlpAPI = ExtendedIPHlpAPI.INSTANCE

    companion object {

        fun listIpv4ForwardTable(): List<ForwardTable> {
            val pointerByReference = PointerByReference()
            val err = ExtendedIPHlpAPI.INSTANCE.GetIpForwardTable2(IPHlpAPI.AF_INET, pointerByReference)
            // something wrong
            if (err != WinError.NO_ERROR && err != WinError.ERROR_NOT_FOUND)
                throw NativeException("Failed to list ip forward table", err)
            // no ip, return empty list
            if (err != WinError.NO_ERROR) return emptyList()
            val table = MIB_IPFORWARD_TABLE2(pointerByReference.value)
            check(table.numEntries == table.table.size) {
                "MIB_IPFORWARD_TABLE2 size not match. Expect ${table.numEntries}, actual: ${table.table.size}"
            }
            val result = table.table.map {
                it.DestinationPrefix.Prefix.setType(Short::class.java)
                val destination = when (it.DestinationPrefix.Prefix.si_family.toInt()) {
                    IPHlpAPI.AF_INET -> {
                        val v4 = it.DestinationPrefix.Prefix.getTypedValue(SocketAddrIn::class.java) as SocketAddrIn
                        Inet4Address.getByAddress(v4.sin_addr.copyOf())
                    }

                    IPHlpAPI.AF_INET6 -> {
                        val v6 = it.DestinationPrefix.Prefix.getTypedValue(SocketAddrIn6::class.java) as SocketAddrIn6
                        Inet6Address.getByAddress(v6.sin6_addr.copyOf())
                    }

                    else -> error("Unknown si family: ${it.DestinationPrefix.Prefix.si_family}")
                }
                it.NextHop.setType(Short::class.java)
                val nextHop = when (it.NextHop.si_family.toInt()) {
                    IPHlpAPI.AF_INET -> {
                        val v4 = it.NextHop.getTypedValue(SocketAddrIn::class.java) as SocketAddrIn
                        Inet4Address.getByAddress(v4.sin_addr.copyOf())
                    }

                    IPHlpAPI.AF_INET6 -> {
                        val v6 = it.NextHop.getTypedValue(SocketAddrIn6::class.java) as SocketAddrIn6
                        Inet6Address.getByAddress(v6.sin6_addr.copyOf())
                    }

                    else -> error("Unknown si family: ${it.NextHop.si_family}")
                }
                ForwardTable(
                    interfaceIndex = it.InterfaceIndex,
                    destination = destination,
                    prefixLength = it.DestinationPrefix.PrefixLength.toUByte(),
                    nextHop = nextHop,
                    metric = it.Metric
                )
            }
            ExtendedIPHlpAPI.INSTANCE.FreeMibTable(table.pointer)
            return result
        }
    }

    /**
     * Open a existing tun device.
     * */
    constructor(name: String) : this(name, null, null)

    private val adapter: WintunAdapterHandler

    init {
        adapter = if (type == null) { // open
            wintunLib.WintunOpenAdapter(WString(name))
                ?: throw NativeException("Failed to open tun device `$name`", Kernel32.INSTANCE.GetLastError())
        } else { // create
            wintunLib.WintunCreateAdapter(WString(name), WString(type), guid?.let { Guid.GUID.fromString(it) })
                ?: throw NativeException(
                    "Failed to create tun device `$name` (type: $type)",
                    Kernel32.INSTANCE.GetLastError()
                )
        }
    }

    /**
     * Get the LUID of this adapter.
     * */
    private fun getLuid(): Long {
        val result = LongByReference()
        wintunLib.WintunGetAdapterLUID(adapter, result)
        return result.value
    }

    fun setDefaultAdapter() {
        val row = MIB_IPFORWARD_ROW2()
        ipHelperLib.InitializeIpForwardEntry(row)
        row.InterfaceLuid = getLuid()
        row.DestinationPrefix.PrefixLength = 0
        row.DestinationPrefix.Prefix.setType(SocketAddrIn::class.java)
        row.DestinationPrefix.Prefix.Ipv4.sin_family = IPHlpAPI.AF_INET.toShort()
        row.DestinationPrefix.Prefix.Ipv4.sin_port = 0
        row.DestinationPrefix.Prefix.Ipv4.sin_addr = Inet4Address.getByName("0.0.0.0").address
        row.SitePrefixLength = 0
        row.Metric = 0
        val err = ipHelperLib.CreateIpForwardEntry2(row)
        if (err != WinError.NO_ERROR)
            throw NativeException("Failed to set default router", err)
    }

    /**
     * List all ip address related to this adapter.
     *
     * @param ipFamily Must be [IPHlpAPI.AF_INET], [IPHlpAPI.AF_INET6] or [IPHlpAPI.AF_UNSPEC]
     * @return List of [AdapterIPAddress], representing an IP.
     * */
    fun listAssociatedAddresses(ipFamily: Int): List<AdapterIPAddress> {
        val pointerByReference = PointerByReference()
        val err = ipHelperLib.GetUnicastIpAddressTable(ipFamily, pointerByReference)
        // something wrong
        if (err != WinError.NO_ERROR && err != WinError.ERROR_NOT_FOUND)
            throw NativeException("Failed to list unicast ip addresses", err)
        // no ip, return empty list
        if (err != WinError.NO_ERROR) return emptyList()
        // parsing pointer
        val table = MibUnicastIPAddressTable(pointerByReference.value)
        check(table.NumEntries == table.Table.size) {
            "MIB_UNICASTIPADDRESS_TABLE size not match. Expect ${table.NumEntries}, actual: ${table.Table.size}"
        }
        val luid = getLuid()
        val result = table.Table
            .filter { it.InterfaceLuid == luid }
            .map {
                it.Address.setType(Short::class.java)
                val ip = when (it.Address.si_family.toInt()) {
                    IPHlpAPI.AF_INET -> {
                        val v4 = it.Address.getTypedValue(SocketAddrIn::class.java) as SocketAddrIn
                        Inet4Address.getByAddress(v4.sin_addr.copyOf())
                    }

                    IPHlpAPI.AF_INET6 -> {
                        val v6 = it.Address.getTypedValue(SocketAddrIn6::class.java) as SocketAddrIn6
                        Inet6Address.getByAddress(v6.sin6_addr.copyOf())
                    }

                    else -> error("Unknown si family: ${it.Address.si_family}")
                }
                AdapterIPAddress(
                    ip = ip,
                    prefixLength = it.OnLinkPrefixLength.toUByte(),
                    validLifeTime = it.ValidLifetime.toUInt(),
                    preferredLifeTime = it.PreferredLifetime.toUInt(),
                    creationTimeStamp = it.CreationTimeStamp
                )
            }
        ipHelperLib.FreeMibTable(table.pointer)
        return result
    }

    /**
     * Create and initialize a [MibUnicastIPAddressRow], fill the luid and ip.
     * */
    private fun createMibUnicastIpAddressRow(address: InetAddress): MibUnicastIPAddressRow {
        val row = MibUnicastIPAddressRow()
        ipHelperLib.InitializeUnicastIpAddressEntry(row)
        row.InterfaceLuid = getLuid()
        when (address) {
            is Inet4Address -> {
                row.Address.setType(SocketAddrIn::class.java)
                row.Address.Ipv4.sin_family = IPHlpAPI.AF_INET.toShort()
                row.Address.Ipv4.sin_port = 0
                row.Address.Ipv4.sin_addr = address.address
            }

            is Inet6Address -> {
                row.Address.setType(SocketAddrIn6::class.java)
                row.Address.Ipv6.sin6_family = IPHlpAPI.AF_INET6.toShort()
                row.Address.Ipv6.sin6_port = 0
                row.Address.Ipv6.sin6_addr = address.address
            }
        }
        return row
    }

    /**
     * Add an [ip] to this adapter.
     *
     * @return true if created, false means address already exists.
     * */
    fun associateIp(ip: InetAddress, prefixLength: Int): Boolean {
        return associateIp(AdapterIPAddress(ip = ip, prefixLength = prefixLength.toUByte()))
    }

    /**
     * Add an [AdapterIPAddress] to this adapter.
     *
     * @return true if created, false means address already exists.
     * */
    fun associateIp(adapterIPAddress: AdapterIPAddress): Boolean {
        // create a new row
        val row = createMibUnicastIpAddressRow(adapterIPAddress.ip)
        row.OnLinkPrefixLength = adapterIPAddress.prefixLength.toByte()
        row.ValidLifetime = adapterIPAddress.validLifeTime.toInt()
        row.PreferredLifetime = adapterIPAddress.preferredLifeTime.toInt()
        return ipHelperLib.CreateUnicastIpAddressEntry(row).let { err ->
            if (err != WinError.NO_ERROR && err != WinError.ERROR_OBJECT_ALREADY_EXISTS)
                throw NativeException("Failed to create new MIB_UNICASTIPADDRESS_ROW", err)
            err == WinError.NO_ERROR // false: duplicated
        }
    }

    /**
     * Update the key. The [AdapterIPAddress.ip] must not change.
     *
     * @return ture if changed, false means ip not found.
     * */
    fun updateIp(adapterIPAddress: AdapterIPAddress): Boolean {
        // create a new row
        val row = createMibUnicastIpAddressRow(adapterIPAddress.ip)
        // fetch the latest
        ipHelperLib.GetUnicastIpAddressEntry(row).let { err ->
            if (err != WinError.NO_ERROR && err != WinError.ERROR_NOT_FOUND)
                throw NativeException("Failed to get unicast ip entry", err)
            if (err != WinError.NO_ERROR) return false // not found
        }
        row.PreferredLifetime = adapterIPAddress.preferredLifeTime.toInt()
        row.OnLinkPrefixLength = adapterIPAddress.prefixLength.toByte()
        row.ValidLifetime = adapterIPAddress.validLifeTime.toInt()
        ipHelperLib.SetUnicastIpAddressEntry(row).let { err ->
            if (err != WinError.NO_ERROR)
                throw NativeException("Failed to update MIB_UNICASTIPADDRESS_ROW", err)
        }
        return true
    }

    /**
     * Remove an ip from the adapter
     * */
    fun dissociateIp(ip: InetAddress) {
        val row = createMibUnicastIpAddressRow(ip)
        ipHelperLib.DeleteUnicastIpAddressEntry(row).let { err ->
            if (err != WinError.NO_ERROR)
                throw NativeException("Failed deleting ip", err)
        }
    }

    /**
     * Get the MTU of this adapter on the given ipFamily.
     *
     * @param ipFamily Must be [IPHlpAPI.AF_INET] or [IPHlpAPI.AF_INET6]
     * */
    fun getMTU(ipFamily: Int): Int {
        val ipInterfaceRow = MibIPInterfaceRow()
        ipHelperLib.InitializeIpInterfaceEntry(ipInterfaceRow)
        ipInterfaceRow.InterfaceLuid = getLuid()
        ipInterfaceRow.Family = ipFamily
        ipHelperLib.GetIpInterfaceEntry(ipInterfaceRow).let { err ->
            if (err != WinError.NO_ERROR)
                throw NativeException("Failed getting MIB_IPINTERFACE_ROW", err)
        }
        return ipInterfaceRow.NlMtu
    }

    /**
     * Set the MTU of this adapter on the given ipFamily.
     *
     * @param ipFamily Must be [IPHlpAPI.AF_INET] or [IPHlpAPI.AF_INET6]
     * @param mtu The new mtu value. Although it's [UInt], the range is [UShort].
     * */
    fun setMTU(ipFamily: Int, mtu: Int) {
        val ipInterfaceRow = MibIPInterfaceRow()
        ipHelperLib.InitializeIpInterfaceEntry(ipInterfaceRow)
        ipInterfaceRow.Family = ipFamily
        ipInterfaceRow.InterfaceLuid = getLuid()
        ipHelperLib.GetIpInterfaceEntry(ipInterfaceRow).let { err ->
            if (err != WinError.NO_ERROR)
                throw NativeException("Failed getting MIB_IPINTERFACE_ROW", err)
        }
        ipInterfaceRow.NlMtu = mtu
        ipInterfaceRow.SitePrefixLength = 0
        ipHelperLib.SetIpInterfaceEntry(ipInterfaceRow).let { err ->
            if (err != WinError.NO_ERROR)
                throw NativeException("Failed setting new MTU", err)
        }
    }

    /**
     * Create a new session associated with this adapter, so you can read/write
     * ip packets.
     *
     * @param capacity Ring capacity of the adapter, must in range of [WintunLib.WINTUN_MIN_RING_CAPACITY]
     * and [WintunLib.WINTUN_MAX_RING_CAPACITY]
     * */
    fun newSession(capacity: Int): WintunSession {
        require(capacity in WintunLib.WINTUN_MIN_RING_CAPACITY..WintunLib.WINTUN_MAX_RING_CAPACITY) {
            "The ring capacity must not smaller than ${WintunLib.WINTUN_MIN_RING_CAPACITY}, and must not bigger than ${WintunLib.WINTUN_MAX_RING_CAPACITY}"
        }
        val handler = wintunLib.WintunStartSession(adapter, capacity)
            ?: throw NativeException("Failed to create session (size: $capacity)", Kernel32.INSTANCE.GetLastError())
        return WintunSession(wintunLib, handler, capacity)
    }

    override fun close() {
        wintunLib.WintunCloseAdapter(adapter)
    }
}
