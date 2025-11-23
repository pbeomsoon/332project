#!/bin/bash
# Network connectivity test for distributed sorting

echo "=== Network Configuration ==="
echo "Hostname: $(hostname)"
echo ""
echo "IP Addresses:"
ip addr show | grep "inet " | grep -v "127.0.0.1"
echo ""

echo "=== Hosts File Entries ==="
grep -v "^#" /etc/hosts | grep -v "^$"
echo ""

echo "=== DNS Resolution Tests ==="
for host in localhost 127.0.0.1 10.1.25.21 2.2.2.254 vm-1-master; do
    echo "Testing: $host"
    getent hosts $host 2>/dev/null || echo "  Failed to resolve"
    echo ""
done

echo "=== Java Network Settings Test ==="
echo "Testing IPv4 preference..."
java -Djava.net.preferIPv4Stack=true -XshowSettings:properties -version 2>&1 | grep -i "ipv\|network" || echo "No IPv settings found"
echo ""

echo "=== Recommended Master Address ==="
echo "For same machine: localhost:34739"
echo "For local network: 10.1.25.21:34739"
echo "Avoid using: 2.2.2.254:34739 (secondary interface - may cause issues)"
