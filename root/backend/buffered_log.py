"""Bounded batched evidence logging, isolated from the decoder/state lock.

No worker is started by construction. Production starts it in State.consume;
offline capture replay can call flush/close synchronously without any service.
"""
from collections import deque
import threading


class BufferedJsonLog:
    def __init__(self, path):
        self.file = path.open('a', encoding='utf-8', buffering=131072)
        self.pending = deque()
        self.pending_chars = 0
        self.lock = threading.Lock()
        self.io_lock = threading.Lock()
        self.stop = threading.Event()
        self.worker = None
        self.closed = False
        self.dropped = 0
        self.error = None

    def tell(self):
        return self.file.tell()

    def write(self, line):
        # Loss limits affect diagnostic records only, never the decoded state.
        with self.lock:
            if self.closed or self.pending_chars + len(line) > 2 * 1024 * 1024 or len(self.pending) >= 8192:
                self.dropped += 1
                return 0
            self.pending.append(line)
            self.pending_chars += len(line)
        return len(line)

    def metrics(self):
        with self.lock:
            return {'pending_lines': len(self.pending), 'pending_chars': self.pending_chars,
                    'dropped_lines': self.dropped, 'last_error': self.error}

    def flush(self):
        # The file write happens here, never in write() or inside State.lock.
        with self.io_lock:
            with self.lock:
                lines = list(self.pending)
                self.pending.clear()
                self.pending_chars = 0
            try:
                if lines:
                    self.file.write(''.join(lines))
                self.file.flush()
            except (OSError, ValueError) as exc:
                with self.lock:
                    self.dropped += len(lines)
                    self.error = str(exc)[:200]

    def _run(self):
        while not self.stop.wait(0.10):
            self.flush()

    def start(self):
        if self.worker is None:
            self.worker = threading.Thread(target=self._run, name='udp-evidence-log', daemon=True)
            self.worker.start()

    def close(self):
        with self.lock:
            if self.closed:
                return
            self.closed = True
        self.stop.set()
        if self.worker is not None:
            self.worker.join()
        self.flush()
        self.file.close()
