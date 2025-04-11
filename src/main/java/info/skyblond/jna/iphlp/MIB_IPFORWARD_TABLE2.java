package info.skyblond.jna.iphlp;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

@Structure.FieldOrder({ "numEntries", "table" })
public class MIB_IPFORWARD_TABLE2 extends Structure {
    public int numEntries;
    public MIB_IPFORWARD_ROW2[] table = new MIB_IPFORWARD_ROW2[1];

    public MIB_IPFORWARD_TABLE2(Pointer buf) {
        super(buf);
        read();
    }

    @Override
    public void read() {
        // First element contains array size
        this.numEntries = getPointer().getInt(0);
        if (this.numEntries > 0) {
            table = (MIB_IPFORWARD_ROW2[]) new MIB_IPFORWARD_ROW2().toArray(this.numEntries);
            super.read();
        } else {
            table = new MIB_IPFORWARD_ROW2[0];
        }
    }
}
