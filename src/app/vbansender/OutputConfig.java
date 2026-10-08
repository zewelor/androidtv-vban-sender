package app.vbansender;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Explicit VBAN destination and packet settings shared by finite and continuous senders. */
public class OutputConfig {
    public final InetSocketAddress destination;
    public final String stream;
    public final int packetFrames;

    public OutputConfig(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: DESTINATION_IPV4 PORT STREAM PACKET_FRAMES");
        }
        String[] octets = args[0].split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("Destination must be an IPv4 address");
        }
        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!octets[i].matches("0|[1-9][0-9]{0,2}")) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            address[i] = (byte) integer(octets[i], 0, 255, "IPv4 octet");
        }
        InetAddress ip = InetAddress.getByAddress(address);
        if (ip.isAnyLocalAddress() || ip.isMulticastAddress()
                || args[0].equals("255.255.255.255")) {
            throw new IllegalArgumentException("Destination must be a unicast IPv4 address");
        }
        destination = new InetSocketAddress(ip, integer(args[1], 1, 65535, "Port"));
        stream = args[2];
        new VbanPacket(stream);
        packetFrames = integer(args[3], 1, 256, "Packet frames");
    }

    static int integer(String value, int min, int max, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
        }
        return parsed;
    }
}
