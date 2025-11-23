# Network Troubleshooting Guide

## Problem
Worker fails to connect to Master with `java.nio.channels.UnsupportedAddressTypeException`

## Root Cause
The address `2.2.2.254` is on a secondary network interface (ens224) and Java/gRPC/Netty is having issues connecting to it, likely due to:
- IPv4/IPv6 address family conflicts
- Multiple IPs resolving to the same hostname
- Network interface routing issues

## Solution

### Quick Fix (Same Machine)
If Master and Workers are on the same machine:
```bash
# Start Master
master 3

# Connect Worker using localhost
worker localhost:34739 -I input1 -O output
```

### Fix for Distributed Setup
If Master and Workers are on different machines:

1. **Use the primary network interface (10.1.25.21)**
```bash
# On Master machine
master 3
# Note the port (e.g., 10.1.25.21:34739)

# On Worker machines
worker 10.1.25.21:34739 -I input1 -O output
```

2. **Verify network connectivity first**
```bash
# From worker machine, test connectivity
ping -c 2 10.1.25.21
telnet 10.1.25.21 34739
```

### Alternative: Force IPv4 with JVM Options

If you still want to use 2.2.2.254, modify the worker script:

```bash
# Edit worker script to add more JVM options
java -Djava.net.preferIPv4Stack=true \
     -Djava.net.preferIPv4Addresses=true \
     -Dio.netty.noPreferDirect=true \
     -cp "$JAR_FILE" distsort.worker.Worker "$@"
```

### Debug Steps

1. **Run network test**
```bash
chmod +x test_network.sh
./test_network.sh
```

2. **Verify Master is listening**
```bash
# After starting master
netstat -tlnp | grep 34739
# or
ss -tlnp | grep 34739
```

3. **Test with nc (netcat)**
```bash
# On worker machine
nc -zv 10.1.25.21 34739
```

## Recommended Configuration

For your VM setup with 2 network interfaces:
- **Use 10.1.25.21** for distributed sorting (primary interface)
- **Avoid 2.2.2.254** unless necessary (secondary interface)
- **Use localhost/127.0.0.1** for local testing

## Why 2.2.2.254 Failed

1. The address is on a secondary interface (ens224: 2.2.2.254/24)
2. Your `/etc/hosts` has both 10.1.25.21 and 2.2.2.254 mapped to vm-1-master
3. Java's `InetAddress.getByName()` may resolve to multiple addresses
4. Netty's socket connection fails when the address family is ambiguous

## Verification

After fixing, you should see:
```
13:29:18.613 [main] INFO  distsort.worker.Worker$ - Starting Worker...
13:29:19.094 [main] INFO  distsort.worker.Worker - Worker worker-xxxxx registered successfully
```

Instead of:
```
io.grpc.StatusRuntimeException: UNKNOWN
Caused by: java.nio.channels.UnsupportedAddressTypeException
```
