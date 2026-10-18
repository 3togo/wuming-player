#!/usr/bin/env python3
"""
Simulates a buggy DLNA renderer (like Coocaa TV) that makes multiple parallel
connections to the phone's proxy server. This allows non-interactive testing
of the proxy's connection handling behavior.
"""

import requests
import threading
import time
import sys
import os

# Configuration
BASE_URL = os.environ.get("PROXY_URL", "http://127.0.0.1:9978")
STREAM_URL = f"{BASE_URL}/proxy?do=stream&stream=test-stream-001"
NUM_PARALLEL_CONNECTIONS = 10
REQUEST_INTERVAL = 0.02  # 20ms between requests (simulates TV behavior)

class ConnectionThread(threading.Thread):
    def __init__(self, thread_id):
        super().__init__()
        self.thread_id = thread_id
        self.bytes_read = 0
        self.success = False
        self.error = None
        self.start_time = None
        self.end_time = None

    def run(self):
        self.start_time = time.time()
        try:
            response = requests.get(STREAM_URL, stream=True, timeout=10)
            if response.status_code != 200:
                self.error = f"HTTP {response.status_code}: {response.text}"
                self.end_time = time.time()
                return

            self.success = True
            for chunk in response.iter_content(chunk_size=8192):
                self.bytes_read += len(chunk)
                # Simulate TV behavior: stop after reading some data
                if self.bytes_read > 1024 * 1024:  # 1MB limit per connection
                    break
        except Exception as e:
            self.error = str(e)
        finally:
            self.end_time = time.time()

def main():
    print(f"Testing proxy at: {BASE_URL}")
    print(f"Stream URL: {STREAM_URL}")
    print(f"Parallel connections: {NUM_PARALLEL_CONNECTIONS}")
    print(f"Request interval: {REQUEST_INTERVAL}s")
    print("-" * 60)

    # Check if proxy is running
    try:
        response = requests.get(f"{BASE_URL}/proxy?do=check", timeout=2)
        print(f"Proxy health check: HTTP {response.status_code}")
    except Exception as e:
        print(f"WARNING: Proxy health check failed: {e}")
        print("Make sure the app is running and the proxy is accessible")
        sys.exit(1)

    threads = []
    start_time = time.time()

    # Launch parallel connections
    for i in range(NUM_PARALLEL_CONNECTIONS):
        t = ConnectionThread(i)
        t.start()
        threads.append(t)
        time.sleep(REQUEST_INTERVAL)
        print(f"Launched connection {i+1}/{NUM_PARALLEL_CONNECTIONS}")

    # Wait for all threads to complete
    for t in threads:
        t.join(timeout=15)

    elapsed = time.time() - start_time

    # Analyze results
    print("\n" + "=" * 60)
    print("RESULTS:")
    print("=" * 60)

    successful = sum(1 for t in threads if t.success)
    failed = sum(1 for t in threads if t.error)
    total_bytes = sum(t.bytes_read for t in threads)

    print(f"Total time: {elapsed:.2f}s")
    print(f"Successful connections: {successful}/{NUM_PARALLEL_CONNECTIONS}")
    print(f"Failed connections: {failed}/{NUM_PARALLEL_CONNECTIONS}")
    print(f"Total bytes read: {total_bytes:,} ({total_bytes / 1024 / 1024:.2f} MB)")
    print(f"Average throughput: {total_bytes / elapsed / 1024:.2f} KB/s")

    print("\nPer-connection details:")
    for t in threads:
        duration = (t.end_time - t.start_time) if t.end_time and t.start_time else 0
        status = "OK" if t.success else f"FAIL: {t.error}"
        print(f"  Thread {t.thread_id}: {t.bytes_read:,} bytes in {duration:.2f}s - {status}")

    # Determine if the fix is working
    if successful == 1 and failed == NUM_PARALLEL_CONNECTIONS - 1:
        print("\n✓ GOOD: Only one connection succeeded, duplicates were rejected")
    elif successful > 1:
        print(f"\n✗ BAD: {successful} connections succeeded (should be 1)")
        print("  The proxy is accepting duplicate connections!")
    else:
        print("\n✗ BAD: No connections succeeded")
        print("  The proxy may be rejecting all connections or the stream is unavailable")

if __name__ == "__main__":
    main()