package info.skyblond.vpn;

import java.util.Scanner;

public class WintunVpnDemo {

    public static void main(String[] args) {
        if(args.length < 2) {
            System.err.println("WintunVpnDemo host port");
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        try {
            WintunVpn vpn = new WintunVpn(host, port);
            vpn.start();

            Scanner scanner = new Scanner(System.in);
            String line;
            while((line = scanner.nextLine()) != null) {
                if ("q".equalsIgnoreCase(line) ||
                        "quit".equalsIgnoreCase(line) ||
                        "exit".equalsIgnoreCase(line)) {
                    vpn.stop();
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

}
