#!/usr/bin/env python3
"""Measure VBAN packet timing on an existing receiver without binding its UDP port."""
import argparse
import ipaddress
import json
import math
import os
import pathlib
import re
import selectors
import shlex
import struct
import subprocess
import sys
import time

CAPTURE_SNAPLEN = 70
MAX_CAPTURE_BYTES = 32 * 1024 * 1024
MAX_STDERR_BYTES = 64 * 1024
SSH_CONNECT_TIMEOUT_SECONDS = 10
WALL_DEADLINE_MARGIN_SECONDS = 20
TCPDUMP_STOP_GRACE_SECONDS = 3
VBAN_SAMPLE_RATE_HZ = 48_000
VBAN_CHANNELS = 2
VBAN_FRAME_BYTES = 4


class MeasurementError(Exception):
    """A malformed capture or unsuccessful observation."""


def _percentile(ordered, percent):
    if not ordered:
        return None
    index = max(0, math.ceil(percent / 100 * len(ordered)) - 1)
    return round(ordered[index] / 1_000_000, 3)


def _parse_stream_name(vban_header, packet_number):
    raw = bytes(vban_header[8:24])
    name = raw.split(b"\0", 1)[0]
    if not name or any(byte < 32 or byte > 126 for byte in name):
        raise MeasurementError(f"packet {packet_number}: invalid VBAN stream name")
    if raw != name.ljust(16, b"\0"):
        raise MeasurementError(f"packet {packet_number}: invalid VBAN stream name padding")
    return name.decode("ascii")


def _parse_packet(frame, original_length, packet_number, expected_sender, expected_port):
    if len(frame) < CAPTURE_SNAPLEN:
        raise MeasurementError(
            f"packet {packet_number}: truncated Ethernet/IPv4/UDP/VBAN header "
            f"({len(frame)} of {CAPTURE_SNAPLEN} bytes)"
        )
    if len(frame) > CAPTURE_SNAPLEN:
        raise MeasurementError(
            f"packet {packet_number}: capture contains bytes beyond the 70-byte header boundary"
        )

    if frame[12:14] != b"\x08\x00":
        raise MeasurementError(f"packet {packet_number}: unsupported Ethernet protocol (expected IPv4)")

    ip = frame[14:34]
    if ip[0] != 0x45:
        raise MeasurementError(f"packet {packet_number}: IPv4 header must have IHL 20 bytes")
    ip_total_length = int.from_bytes(ip[2:4], "big")
    fragment = int.from_bytes(ip[6:8], "big")
    if fragment & 0x3FFF:
        raise MeasurementError(f"packet {packet_number}: fragmented IPv4 packet")
    if ip[9] != 17:
        raise MeasurementError(f"packet {packet_number}: IPv4 packet is not UDP")

    source_ip = str(ipaddress.IPv4Address(bytes(ip[12:16])))
    destination_ip = str(ipaddress.IPv4Address(bytes(ip[16:20])))
    udp = frame[34:42]
    source_port = int.from_bytes(udp[0:2], "big")
    destination_port = int.from_bytes(udp[2:4], "big")
    udp_length = int.from_bytes(udp[4:6], "big")
    if source_ip != expected_sender or destination_port != expected_port:
        raise MeasurementError(
            f"packet {packet_number}: packet does not match requested sender/port filter"
        )
    if ip_total_length != 20 + udp_length:
        raise MeasurementError(f"packet {packet_number}: inconsistent IPv4 and UDP lengths")
    if original_length < 14 + ip_total_length:
        raise MeasurementError(f"packet {packet_number}: original packet length is shorter than IPv4 length")
    if udp_length < 8 + 28:
        raise MeasurementError(f"packet {packet_number}: UDP payload is shorter than the VBAN header")

    vban = frame[42:70]
    if vban[0:4] != b"VBAN":
        raise MeasurementError(f"packet {packet_number}: unsupported VBAN signature")
    if vban[4] != 3:
        raise MeasurementError(f"packet {packet_number}: VBAN stream is not 48 kHz")
    if vban[6] != 1:
        raise MeasurementError(f"packet {packet_number}: VBAN stream is not stereo")
    if vban[7] != 1:
        raise MeasurementError(f"packet {packet_number}: VBAN stream is not S16LE")

    pcm_frames = vban[5] + 1
    expected_udp_length = 8 + 28 + pcm_frames * VBAN_FRAME_BYTES
    if udp_length != expected_udp_length:
        raise MeasurementError(
            f"packet {packet_number}: declared VBAN frame/payload size does not match UDP length"
        )
    stream_name = _parse_stream_name(vban, packet_number)
    sequence = int.from_bytes(vban[24:28], "little")
    return {
        "source_ip": source_ip,
        "source_port": source_port,
        "destination_ip": destination_ip,
        "destination_port": destination_port,
        "stream": stream_name,
        "sequence": sequence,
        "pcm_frames": pcm_frames,
    }


def _percentile_summary(intervals_ns):
    ordered = sorted(intervals_ns)
    return {
        "count": len(intervals_ns),
        "p50": _percentile(ordered, 50),
        "p95": _percentile(ordered, 95),
        "p99": _percentile(ordered, 99),
        "max": round(max(intervals_ns) / 1_000_000, 3) if intervals_ns else None,
        "count_over_20ms": sum(value > 20_000_000 for value in intervals_ns),
        "count_over_50ms": sum(value > 50_000_000 for value in intervals_ns),
        "percentile_method": "nearest rank",
    }


def parse_pcap(capture, expected_sender, expected_port):
    """Parse classic PCAP bytes without retaining packet payloads or sample data."""
    if len(capture) < 24:
        raise MeasurementError("truncated PCAP global header")

    magic = bytes(capture[:4])
    formats = {
        b"\xd4\xc3\xb2\xa1": ("<", 1_000_000),
        b"\xa1\xb2\xc3\xd4": (">", 1_000_000),
        b"\x4d\x3c\xb2\xa1": ("<", 1_000_000_000),
        b"\xa1\xb2\x3c\x4d": (">", 1_000_000_000),
    }
    if magic not in formats:
        raise MeasurementError("unsupported PCAP magic (classic microsecond/nanosecond PCAP required)")
    byte_order, fraction_scale = formats[magic]
    major, minor, _timezone, _sigfigs, snaplen, linktype = struct.unpack(
        byte_order + "HHiIII", capture[4:24]
    )
    if (major, minor) != (2, 4):
        raise MeasurementError(f"unsupported PCAP version {major}.{minor}; classic PCAP 2.4 required")
    if snaplen < CAPTURE_SNAPLEN:
        raise MeasurementError(f"PCAP snaplen {snaplen} is below the required 70-byte header")
    if linktype != 1:
        raise MeasurementError(f"unsupported PCAP linktype {linktype}; Ethernet linktype 1 required")

    view = memoryview(capture)
    offset = 24
    endian = "big" if byte_order == ">" else "little"
    packet_count = 0
    pcm_frames = 0
    duplicate_packets = 0
    out_of_order_packets = 0
    intervals_ns = []
    source_ports = set()
    identity = None
    seen_sequences = set()
    sequence_offsets = set()
    first_sequence = None
    highest_offset = -1
    first_timestamp_ns = None
    previous_timestamp_ns = None

    while offset < len(view):
        if len(view) - offset < 16:
            raise MeasurementError(f"truncated PCAP record header after packet {packet_count}")
        seconds = int.from_bytes(view[offset:offset + 4], endian)
        fraction = int.from_bytes(view[offset + 4:offset + 8], endian)
        captured_length = int.from_bytes(view[offset + 8:offset + 12], endian)
        original_length = int.from_bytes(view[offset + 12:offset + 16], endian)
        offset += 16
        if fraction >= fraction_scale:
            raise MeasurementError(f"packet {packet_count + 1}: invalid PCAP timestamp fraction")
        if captured_length > CAPTURE_SNAPLEN:
            raise MeasurementError(
                f"packet {packet_count + 1}: PCAP captured more than the 70-byte header boundary"
            )
        if captured_length > snaplen or original_length < captured_length:
            raise MeasurementError(f"packet {packet_count + 1}: invalid PCAP captured/original length")
        if captured_length > len(view) - offset:
            raise MeasurementError(f"truncated packet data for PCAP packet {packet_count + 1}")
        frame = view[offset:offset + captured_length]
        offset += captured_length

        packet_count += 1
        packet = _parse_packet(frame, original_length, packet_count, expected_sender, expected_port)
        packet_identity = (packet["source_ip"], packet["stream"],
                           packet["source_port"], packet["destination_ip"])
        if identity is None:
            identity = packet_identity
            first_sequence = packet["sequence"]
        elif packet_identity != identity:
            raise MeasurementError(
                f"packet {packet_count}: sender/stream identity changed during measurement"
            )

        timestamp_ns = seconds * 1_000_000_000 + fraction * (1_000_000_000 // fraction_scale)
        if previous_timestamp_ns is not None:
            interval = timestamp_ns - previous_timestamp_ns
            if interval < 0:
                raise MeasurementError(f"packet {packet_count}: PCAP timestamps moved backwards")
            intervals_ns.append(interval)
        if first_timestamp_ns is None:
            first_timestamp_ns = timestamp_ns
        previous_timestamp_ns = timestamp_ns

        sequence = packet["sequence"]
        source_ports.add(packet["source_port"])
        pcm_frames += packet["pcm_frames"]
        if sequence in seen_sequences:
            duplicate_packets += 1
            continue
        seen_sequences.add(sequence)
        offset_from_start = (sequence - first_sequence) & 0xFFFFFFFF
        if offset_from_start >= 0x80000000:
            out_of_order_packets += 1
            continue
        if offset_from_start < highest_offset:
            out_of_order_packets += 1
        else:
            highest_offset = offset_from_start
        sequence_offsets.add(offset_from_start)

    if packet_count == 0:
        raise MeasurementError("no packets captured; a successful measurement requires traffic")

    ordered_offsets = sorted(sequence_offsets)
    sequence_gaps = sum(max(0, current - previous - 1)
                        for previous, current in zip(ordered_offsets, ordered_offsets[1:]))
    observed_duration_ns = previous_timestamp_ns - first_timestamp_ns
    return {
        "packets": packet_count,
        "pcm_frames": pcm_frames,
        "sequence_gaps": sequence_gaps,
        "duplicate_packets": duplicate_packets,
        "out_of_order_packets": out_of_order_packets,
        "observed_duration_ms": round(observed_duration_ns / 1_000_000, 3),
        "sender": {
            "ip": identity[0],
            "stream": identity[1],
            "source_udp_ports": sorted(source_ports),
            "destination_port": expected_port,
        },
        "format": {
            "sample_rate_hz": VBAN_SAMPLE_RATE_HZ,
            "channels": VBAN_CHANNELS,
            "sample_format": "S16LE",
            "bytes_per_frame": VBAN_FRAME_BYTES,
        },
        "inter_arrival_ms": _percentile_summary(intervals_ns),
    }


def build_ssh_command(ssh_host, ssh_config, interface, sender_ip, port, seconds):
    """Construct the SSH argument vector and safely quoted remote shell command."""
    remote = [
        "timeout", "--signal=TERM", f"--kill-after={TCPDUMP_STOP_GRACE_SECONDS}",
        f"{seconds}s", "tcpdump", "-i", interface, "-nn", "-U", "-s",
        str(CAPTURE_SNAPLEN), "-w", "-", "ip and udp and src host " + sender_ip
        + " and dst port " + str(port),
    ]
    command = ["ssh", "-T"]
    if ssh_config:
        command.extend(["-F", ssh_config])
    command.extend([
        "-o", "BatchMode=yes",
        "-o", f"ConnectTimeout={SSH_CONNECT_TIMEOUT_SECONDS}",
        ssh_host,
        shlex.join(remote),
    ])
    return command


def _append_tail(current, chunk, limit):
    current.extend(chunk)
    if len(current) > limit:
        del current[:-limit]


def _stop_process(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=2)


def run_capture(command, seconds):
    wall_deadline = seconds + SSH_CONNECT_TIMEOUT_SECONDS + WALL_DEADLINE_MARGIN_SECONDS
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   bufsize=0, close_fds=True)
    except OSError as error:
        raise MeasurementError(f"could not start SSH capture: {error}") from error

    output = bytearray()
    stderr_tail = bytearray()
    selector = selectors.DefaultSelector()
    for stream, label in ((process.stdout, "stdout"), (process.stderr, "stderr")):
        os.set_blocking(stream.fileno(), False)
        selector.register(stream, selectors.EVENT_READ, label)
    deadline = time.monotonic() + wall_deadline
    failure = None
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                failure = f"SSH capture exceeded its {wall_deadline}s wall deadline"
                break
            events = selector.select(min(remaining, 1.0))
            for key, _ in events:
                try:
                    chunk = os.read(key.fileobj.fileno(), 64 * 1024)
                except OSError as error:
                    failure = f"failed reading SSH {key.data}: {error}"
                    break
                if not chunk:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                if key.data == "stdout":
                    if len(output) + len(chunk) > MAX_CAPTURE_BYTES:
                        failure = f"PCAP capture exceeded the {MAX_CAPTURE_BYTES}-byte in-memory limit"
                        break
                    output.extend(chunk)
                else:
                    _append_tail(stderr_tail, chunk, MAX_STDERR_BYTES)
            if failure:
                break
        if failure is None:
            return_code = process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        failure = "SSH capture process did not exit after closing its output streams"
    finally:
        selector.close()
        if failure is not None:
            _stop_process(process)
            for stream in (process.stdout, process.stderr):
                if stream and not stream.closed:
                    try:
                        os.set_blocking(stream.fileno(), False)
                        chunk = os.read(stream.fileno(), MAX_STDERR_BYTES)
                        if stream is process.stderr and chunk:
                            _append_tail(stderr_tail, chunk, MAX_STDERR_BYTES)
                    except OSError:
                        pass
                    stream.close()

    stderr_text = bytes(stderr_tail).decode("utf-8", errors="replace").strip()
    if failure:
        detail = f"\nSSH/tcpdump stderr:\n{stderr_text}" if stderr_text else ""
        raise MeasurementError(f"{failure}{detail}")
    if return_code not in (0, 124):
        detail = f"\nSSH/tcpdump stderr:\n{stderr_text}" if stderr_text else "\nSSH/tcpdump stderr was empty"
        raise MeasurementError(f"SSH/tcpdump capture failed with exit status {return_code}{detail}")
    return output, stderr_text, return_code


def parse_tcpdump_counters(stderr_text):
    patterns = {
        "packets_captured": r"^\s*(\d+)\s+packets captured\s*$",
        "packets_received_by_filter": r"^\s*(\d+)\s+packets received by filter\s*$",
        "packets_dropped_by_kernel": r"^\s*(\d+)\s+packets dropped by kernel\s*$",
    }
    counters = {}
    for field, pattern in patterns.items():
        match = re.search(pattern, stderr_text, re.IGNORECASE | re.MULTILINE)
        counters[field] = int(match.group(1)) if match else None
    return counters


def _arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", required=True, help="SSH host or config alias")
    parser.add_argument("--ssh-config", help="optional local SSH config file")
    parser.add_argument("--interface", default="eth0", help="receiver interface (default: eth0)")
    parser.add_argument("--sender-ip", required=True, help="VBAN sender IPv4 address")
    parser.add_argument("--port", type=int, default=6980, help="receiver UDP port (default: 6980)")
    parser.add_argument("--seconds", type=int, default=60, help="finite capture duration, 1..3600")
    parser.add_argument("--output", required=True, help="path for the JSON metrics report")
    args = parser.parse_args(argv)

    if not args.ssh_host or args.ssh_host.startswith("-") or any(ch.isspace() for ch in args.ssh_host):
        parser.error("ssh-host must be a non-option SSH destination without whitespace")
    if args.ssh_config and not args.ssh_config.strip():
        parser.error("ssh-config must not be empty")
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", args.interface):
        parser.error("interface must contain only letters, digits, '.', '_', ':', or '-' (up to 64 chars)")
    try:
        args.sender_ip = str(ipaddress.IPv4Address(args.sender_ip))
    except ipaddress.AddressValueError:
        parser.error("sender-ip must be a valid IPv4 address")
    if not 1 <= args.port <= 65535:
        parser.error("port must be in 1..65535")
    if not 1 <= args.seconds <= 3600:
        parser.error("seconds must be in 1..3600")
    if not args.output.strip():
        parser.error("output must not be empty")
    return args


def main(argv=None):
    args = _arguments(argv)
    command = build_ssh_command(args.ssh_host, args.ssh_config, args.interface,
                                args.sender_ip, args.port, args.seconds)
    try:
        capture, stderr_text, _return_code = run_capture(command, args.seconds)
        report = parse_pcap(capture, args.sender_ip, args.port)
        report.update({
            "schema_version": 1,
            "result": "PASS_PACKET_MEASUREMENT",
            "requested_duration_seconds": args.seconds,
            "tcpdump": parse_tcpdump_counters(stderr_text),
            "limitations": [
                "Packet timing and sequence counters do not measure physical audio quality.",
                "No observed UDP sequence gaps do not prove there was no capture loss before or elsewhere in the observation path.",
                "PCM payload bytes are not retained or analyzed.",
            ],
        })
        output_path = pathlib.Path(args.output).expanduser()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except (MeasurementError, OSError) as error:
        print(f"measure-wire: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    print(f"Metrics: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
