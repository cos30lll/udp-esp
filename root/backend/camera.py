"""Passive outgoing 3/851 reported-view decoder for this fixed build.
No process access, sockets, default FOV, or inferred remote-player camera.
"""
import math

class CameraDecodeError(ValueError):
    pass

class Reader:
    def __init__(self, data):
        self.data = memoryview(data)
        self.pos = 0
    def varint(self):
        value = 0
        for shift in range(0, 70, 7):
            if self.pos >= len(self.data):
                raise CameraDecodeError('truncated camera varint')
            byte = self.data[self.pos]
            self.pos += 1
            if shift == 63 and byte > 1:
                raise CameraDecodeError('camera uint64 overflow')
            value |= (byte & 127) << shift
            if byte < 128:
                return value
        raise CameraDecodeError('overlong camera varint')
    def block(self):
        size = self.varint()
        if size > len(self.data) - self.pos:
            raise CameraDecodeError('truncated camera block')
        start = self.pos
        self.pos += size
        return self.data[start:self.pos]

def vector(data, existing=None):
    r = Reader(data)
    result = dict(existing) if existing is not None else dict(x=0.0, y=0.0, z=0.0)
    while r.pos < len(r.data):
        tag = r.varint()
        field, wire = tag >> 3, tag & 7
        if field not in (1, 2, 3) or wire != 5:
            raise CameraDecodeError('unverified reported-view vector field')
        value = r.varint()
        if value >= (1 << 32):
            raise CameraDecodeError('reported-view uint32 overflow')
        result['xyz'[field - 1]] = ((value >> 1) ^ -(value & 1)) / 100.0
    return result

def decode_view(data):
    r = Reader(data)
    vectors = {}
    reported_flag = None
    present = set()
    while r.pos < len(r.data):
        tag = r.varint()
        field, wire = tag >> 3, tag & 7
        present.add(field)
        if field in (1, 3, 4, 5) and wire == 2:
            vectors[field] = vector(r.block(), vectors.get(field))
        elif field == 2 and wire == 0:
            reported_flag = bool(r.varint())
        else:
            raise CameraDecodeError('unverified 3/851 camera field')
    result = {'status': 'view_field_absent', 'yaw_deg': None, 'pitch_deg': None,
              'camera_hangle_mod360': None, 'direction': vectors.get(3),
              # Preserve wire inputs without assigning unverified camera semantics.
              'reported_vectors': {str(k): dict(v) for k, v in sorted(vectors.items())},
              'reported_flag_field2': reported_flag,
              'present_fields': sorted(present), 'source': 'outbound_3_851_field3',
              'angle_convention': 'yaw atan2(x,z) [0,360); pitch positive down',
              'render_camera_equivalence': 'not_verified', 'quantized': True,
              'fov_deg': None, 'fov_source': None,
              'fov_status': 'realtime_source_not_identified',
              'fov_config_deg': None, 'roll_deg': None}
    if 3 not in vectors:
        return result
    x, y, z = (vectors[3][key] for key in 'xyz')
    horizontal = math.hypot(x, z)
    norm = math.hypot(horizontal, y)
    if norm == 0:
        result['status'] = 'zero_view_vector'
        return result
    yaw = math.degrees(math.atan2(x, z)) % 360.0 if horizontal > 0 else None
    result.update(status='decoded', yaw_deg=yaw,
                  pitch_deg=math.degrees(math.atan2(-y, horizontal)),
                  camera_hangle_mod360=(-yaw) % 360.0 if yaw is not None else None,
                  direction_norm=norm)
    return result
