package info.skyblond.jna;

import com.sun.jna.platform.win32.Guid;
import info.skyblond.jna.wintun.ForwardTable;
import info.skyblond.jna.wintun.WintunAdapter;

import java.util.List;

public class IPHelperTest {

    public static void main(String[] args) {
        String guid = Guid.GUID.newGuid().toGuidString();
        try (WintunAdapter adapter = new WintunAdapter("Wintun", "Wintun", guid)) {
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
