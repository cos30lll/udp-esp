"""Decode reported actor inputs, not final camera or client render state.
Fixed UpdateScript_500.dll: dispatcher 060038F8; Speed 06003CB3;
Move 06003E11; Grounded 060037F3; LifeState 06003D99.
Rotation 06003878/06021E30/06021E23; Squat 060038A3/06003876; UpDirection 06003B97.
Vector conversion: 06021E2E, int64 -> single -> divide by single scale.
"""
import re
import struct

SUPPORTED = {2: 'Rotation', 6: 'LifeState', 7: 'Speed', 16: 'Move', 32: 'Squat', 61: 'Grounded', 253: 'UpDirection'}
DECIMAL = re.compile(r'-?(?:0|[1-9][0-9]*)\Z')

def _single(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]

def _int64_single(value):
    # Round the integer directly, avoiding a possible int64->double->single tie.
    magnitude = abs(value)
    if magnitude == 0:
        return 0.0
    shift = magnitude.bit_length() - 24
    if shift > 0:
        high, low = divmod(magnitude, 1 << shift)
        half = 1 << (shift - 1)
        if low > half or (low == half and high & 1):
            high += 1
        magnitude = high << shift
    return _single(-magnitude if value < 0 else magnitude)

def _values(record):
    values = record.get('values_decimal')
    count = record.get('value_count')
    if record.get('values_complete') is not True or type(count) is not int or not isinstance(values, list) or len(values) != count:
        raise ValueError('incomplete_values')
    result = []
    for text in values:
        if not isinstance(text, str) or len(text) > 20 or DECIMAL.fullmatch(text) is None:
            raise ValueError('invalid_decimal_int64')
        value = int(text)
        if not -(1 << 63) <= value < (1 << 63):
            raise ValueError('int64_out_of_range')
        result.append(value)
    return result

def _vector(values, scale, offset):
    if len(values) < offset + 3:
        raise ValueError('vector_values_missing')
    denominator = _single(scale)
    converted = [_int64_single(value) for value in values[offset:offset + 3]]
    return dict(zip('xyz', [_single(value / denominator) if denominator > 0 else value for value in converted]))

def _quaternion(values, scale):
    # FOOBJALACEJ owns a reusable 32-element array. Short wire reports may
    # read old array slots natively, so never invent their absent components.
    if len(values) < 4:
        raise ValueError('quaternion_components_not_all_reported')
    denominator = _single(scale)
    converted = [_int64_single(value) for value in values[:4]]
    parts = [_single(v / denominator) if denominator > 0 else v for v in converted]
    squares = [_single(v*v) for v in parts]
    norm2 = _single(_single(_single(squares[0]+squares[1])+squares[2])+squares[3])
    identity_fallback = norm2 <= _single(0.000001)
    if identity_fallback:
        parts = [0.0, 0.0, 0.0, 1.0]
    # Native ODKLGNEJOME does not normalize a nonzero quaternion.
    return dict(zip('xyzw', parts)), identity_fallback

def decode_reported_action(record):
    kind = record.get('kind')
    if type(kind) is not int or kind not in SUPPORTED:
        return None
    out = {'action_name': SUPPORTED[kind], 'action_kind': kind,
           'status': 'reported_input_not_render_state',
           'camera_equivalence_verified': False,
           'dispatcher_evidence': '0x060038F8'}
    try:
        values = _values(record)
        if kind in (2, 7, 16, 253):
            scale = record.get('scale')
            if type(scale) is not int or not 0 <= scale <= 0xffffffff:
                raise ValueError('invalid_uint32_scale')
            if kind == 2:
                quaternion, fallback = _quaternion(values, scale)
                out.update(reported_actor_rotation=quaternion,
                           native_identity_fallback=fallback,
                           application_condition='conditional_actor_transform_update_not_camera_rotation',
                           evidence='0x06003878; 0x06021E30; 0x06021E23')
                return out
            vector = _vector(values, scale, 0)
            if kind == 253:
                out.update(reported_actor_up=vector,
                           application_condition='PHJEPKIAFGF_then_MKFOHANGPPA_FromToRotation',
                           evidence='0x06003B97; 0x0601551C; 0x0601559A; 0x06021E2E')
            elif kind == 7:
                out.update(reported_speed_vector=vector,
                           application_condition='native_handler_does_not_apply_when_life_state_equals_5',
                           evidence='0x06003CB3 IL_0041..IL_0099; 0x06021E2E')
            else:
                out.update(reported_move_vector=vector,
                           secondary_direction_present=len(values) >= 6,
                           evidence='0x06003E11 IL_0041..IL_0087; 0x06021E2E')
                if len(values) >= 6:
                    out['reported_secondary_direction'] = _vector(values, scale, 3)
        elif kind == 32:
            if not values:
                raise ValueError('squat_value_not_reported_native_handler_ignores')
            out.update(reported_squat_u32=values[0] & 0xffffffff,
                       reported_squat_active=(values[0] & 0xffffffff) > 0,
                       application_condition='virtual_base_setter_dispatches_to_actor_override',
                       evidence='0x060038F8 IL_05D4; 0x060038A3; 0x06015515; 0x06003876; 0x06003EB4')
        elif kind == 61:
            if not values:
                raise ValueError('grounded_value_missing')
            out.update(reported_grounded=values[0] == 1,
                       evidence='0x060037F3 IL_0000..IL_000D')
        else:
            if not values:
                raise ValueError('life_state_value_missing')
            out.update(reported_life_state_i32=((values[0] + (1 << 31)) % (1 << 32)) - (1 << 31),
                       evidence='0x06003D99 IL_0000..IL_0022')
        return out
    except (ValueError, OverflowError, struct.error) as exc:
        return {'action_name': SUPPORTED[kind], 'action_kind': kind,
                'status': 'invalid_reported_input', 'reason': str(exc),
                'camera_equivalence_verified': False}
