package info.skyblond.jna;

import info.skyblond.jna.wintun.ForwardTable;
import info.skyblond.jna.wintun.WintunAdapter;
import info.skyblond.wintun.VpnWintunAdapter;

import java.util.List;

public class IPHelperTest {

    public static void main(String[] args) {
        try (VpnWintunAdapter adapter = VpnWintunAdapter.createVpnAdapter()) {
            adapter.setDefaultAdapter();
            List<ForwardTable> list = WintunAdapter.Companion.listIpv4ForwardTable();
            for(ForwardTable table : list) {
                System.out.println(table);
            }
        } catch(Exception e) {
            e.printStackTrace(System.err);
        }
    }

}
