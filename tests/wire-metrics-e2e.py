#!/usr/bin/env python3
"""Synthetic Ethernet/IPv4/UDP/VBAN headers for the passive wire analyzer."""
import importlib.util
import ipaddress
import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("measure_wire", ROOT / "tools" / "measure-wire.py")
measure_wire = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(measure_wire)

SENDER = "192.0.2.10"
RECEIVER = "192.0.2.20"
PORT = 6980


def packet(counter, frames=256, stream="VBANTX", source_port=50000, *,
           sample_rate=3, channels=1, sample_format=1, fragment=0, ihl=5,
           udp_length=None, ip_protocol=17, dest_port=PORT):
    name = stream.encode("ascii")
    if len(name) > 16:
        raise ValueError("fixture stream is too long")
    vban = (b"VBAN" + bytes((sample_rate, frames - 1, channels, sample_format))
            + name.ljust(16, b"\0") + struct.pack("<I", counter))
    pcm_bytes = frames * 4
    actual_udp_length = 8 + len(vban) + pcm_bytes
    udp = struct.pack("!HHHH", source_port, dest_port,
                      actual_udp_length if udp_length is None else udp_length, 0)
    ip_length = ihl * 4 + (actual_udp_length if udp_length is None else udp_length)
    ip = bytearray(struct.pack("!BBHHHBBH4s4s", (4 << 4) | ihl, 0, ip_length,
                               0, fragment, 64, ip_protocol, 0,
                               ipaddress.IPv4Address(SENDER).packed,
                               ipaddress.IPv4Address(RECEIVER).packed))
    eth = b"\x00" * 12 + b"\x08\x00"
    header = bytes(eth + ip + udp + vban)
    # Preserve the requested malformed IHL while making a normal packet header
    # exactly 70 bytes; production validation rejects IHL values other than 5.
    original_length = 14 + ip_length
    return header[:70], original_length


def pcap(records, *, byte_order="<", precision="micro", snaplen=70, linktype=1):
    if precision == "micro":
        magic = bytes.fromhex("d4c3b2a1") if byte_order == "<" else bytes.fromhex("a1b2c3d4")
        scale = 1_000_000
    else:
        magic = bytes.fromhex("4d3cb2a1") if byte_order == "<" else bytes.fromhex("a1b23c4d")
        scale = 1_000_000_000
    result = bytearray(magic + struct.pack(byte_order + "HHiIII", 2, 4, 0, 0, snaplen, linktype))
    for seconds, fraction, frame, original_length in records:
        result.extend(struct.pack(byte_order + "IIII", seconds, fraction, len(frame), original_length))
        result.extend(frame)
    return bytes(result)


def records(counters, *, times=None, frame_counts=None, stream="VBANTX"):
    times = times or [(100, index * 10_000) for index in range(len(counters))]
    frame_counts = frame_counts or [256] * len(counters)
    return [(sec, frac, *packet(counter, frames, stream))
            for counter, (sec, frac), frames in zip(counters, times, frame_counts)]


def analyze(capture, **kwargs):
    return measure_wire.parse_pcap(capture, SENDER, PORT, **kwargs)


def expect_error(capture, message):
    try:
        analyze(capture)
    except measure_wire.MeasurementError as error:
        assert message in str(error), (message, str(error))
    else:
        raise AssertionError(f"expected MeasurementError containing {message!r}")


def main():
    # Continuous, lost, duplicate, and out-of-order counters share one report.
    sample = records([0xFFFFFFFE, 0xFFFFFFFF, 1, 1, 0, 4, 3])
    report = analyze(pcap(sample))
    assert report["packets"] == 7
    assert report["pcm_frames"] == 7 * 256
    assert report["sequence_gaps"] == 1
    assert report["duplicate_packets"] == 1
    assert report["out_of_order_packets"] == 2
    assert report["sender"]["ip"] == SENDER
    assert report["sender"]["stream"] == "VBANTX"

    reordered = analyze(pcap(records([10, 12, 11])))
    assert reordered["sequence_gaps"] == 0, reordered
    assert reordered["out_of_order_packets"] == 1, reordered
    continuous = analyze(pcap(records([10, 11, 12])))
    assert continuous["sequence_gaps"] == 0

    changed_port = [(100, 0, *packet(0, source_port=50000)),
                    (100, 10000, *packet(0, source_port=50001))]
    expect_error(pcap(changed_port), "identity changed")

    variable = analyze(pcap(records([1, 2], frame_counts=[1, 256])))
    assert variable["pcm_frames"] == 257
    assert variable["observed_duration_ms"] == 10
    assert variable["inter_arrival_ms"]["count"] == 1

    # Both microsecond and nanosecond classic-PCAP timestamps, both endiannesses.
    for order, precision in (("<", "micro"), (">", "micro"),
                             ("<", "nano"), (">", "nano")):
        first_fraction = 1234 if precision == "micro" else 1234567
        second_fraction = first_fraction + (1000 if precision == "micro" else 1_000_000)
        two = records([4, 5], times=[(12, first_fraction), (12, second_fraction)])
        parsed = analyze(pcap(two, byte_order=order, precision=precision))
        assert parsed["packets"] == 2
        assert parsed["observed_duration_ms"] == 1

    # Threshold counts are strictly greater than the requested threshold.
    threshold_times = [(100, 0), (100, 20_000), (100, 70_000), (100, 121_001)]
    # The first two intervals are exactly 20ms and 50ms, then the last exceeds 50ms.
    threshold = analyze(pcap(records([10, 11, 12, 13], times=threshold_times)))
    assert threshold["inter_arrival_ms"]["count_over_20ms"] == 2
    assert threshold["inter_arrival_ms"]["count_over_50ms"] == 1

    counters = measure_wire.parse_tcpdump_counters(
        "7 packets captured\n8 packets received by filter\n1 packets dropped by kernel\n"
    )
    assert counters == {
        "packets_captured": 7,
        "packets_received_by_filter": 8,
        "packets_dropped_by_kernel": 1,
    }

    expect_error(b"", "truncated PCAP global header")
    expect_error(pcap([]), "no packets")
    good = pcap(records([1]))
    expect_error(good[:-1], "truncated packet data")
    expect_error(pcap(records([1]), linktype=101), "unsupported PCAP linktype")
    expect_error(pcap([(100, 0, packet(1)[0][:-1], packet(1)[1])]), "truncated Ethernet/IPv4/UDP/VBAN header")
    expect_error(pcap([(100, 0, packet(1, sample_rate=2)[0], packet(1)[1])]), "48 kHz")
    expect_error(pcap([(100, 0, packet(1, channels=0)[0], packet(1)[1])]), "stereo")
    expect_error(pcap([(100, 0, packet(1, sample_format=2)[0], packet(1)[1])]), "S16LE")
    expect_error(pcap([(100, 0, packet(1, fragment=0x2000)[0], packet(1)[1])]), "fragmented IPv4")
    expect_error(pcap([(100, 0, packet(1, ihl=6)[0], packet(1)[1])]), "IHL 20")
    expect_error(pcap([(100, 0, packet(1, udp_length=1056)[0], packet(1, udp_length=1056)[1])]), "declared VBAN frame/payload size")
    expect_error(pcap([(100, 0, packet(1, ip_protocol=6)[0], packet(1)[1])]), "not UDP")
    expect_error(pcap([(100, 0, packet(1, dest_port=1234)[0], packet(1)[1])]), "does not match requested sender/port filter")

    command = measure_wire.build_ssh_command(
        "receiver.example", None, "eth0", SENDER, PORT, 60)
    assert "BatchMode=yes" in command
    assert "ConnectTimeout=10" in command
    assert command[-1].find("timeout --signal=TERM") >= 0
    assert "-s 70 -w -" in command[-1]
    assert "src host 192.0.2.10 and dst port 6980" in command[-1]

    # Exercise the pipe collector with a local synthetic child. No SSH/ADB call.
    fixture = pcap(records([20]))
    child = ("import sys; sys.stdout.buffer.write(bytes.fromhex(" + repr(fixture.hex())
             + ")); sys.stderr.write('1 packets dropped by kernel\\n'); sys.exit(124)")
    captured, stderr, return_code = measure_wire.run_capture([sys.executable, "-c", child], 1)
    assert captured == fixture
    assert "1 packets dropped by kernel" in stderr
    assert return_code == 124

    failing_child = "import sys; sys.stderr.write('tcpdump was killed\\n'); sys.exit(137)"
    try:
        measure_wire.run_capture([sys.executable, "-c", failing_child], 1)
    except measure_wire.MeasurementError as error:
        assert "exit status 137" in str(error)
        assert "tcpdump was killed" in str(error)
    else:
        raise AssertionError("non-timeout child failure must be rejected")

    print("PASS wire metrics synthetic PCAP cases")


if __name__ == "__main__":
    main()
