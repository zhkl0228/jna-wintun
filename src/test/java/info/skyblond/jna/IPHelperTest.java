package info.skyblond.jna;

import info.skyblond.jna.wintun.ForwardTable;
import info.skyblond.jna.wintun.WintunAdapter;

import java.util.List;

public class IPHelperTest {

    public static void main(String[] args) {
        List<ForwardTable> list = WintunAdapter.Companion.listIpv4ForwardTable();
        System.out.println(list);
    }

}
